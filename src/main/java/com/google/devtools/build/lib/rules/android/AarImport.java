// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.android.databinding.DataBinding;
import com.google.devtools.build.lib.rules.android.databinding.DataBindingV2Provider;
import com.google.devtools.build.lib.rules.java.ImportDepsCheckActionBuilder;
import com.google.devtools.build.lib.rules.java.JavaCommon;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaCompilationArtifacts;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.ImportDepsCheckingLevel;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaToolchainProvider;
import com.google.devtools.build.lib.rules.java.ProguardLibrary;
import com.google.devtools.build.lib.rules.java.ProguardSpecProvider;
import com.google.devtools.build.lib.starlarkbuildapi.android.DataBindingV2ProviderApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An implementation for the aar_import rule.
 *
 * <p>AAR files are zip archives that contain an Android Manifest, JARs, resources, assets, native
 * libraries, Proguard configuration and lint jars. Currently the aar_import rule supports AARs with
 * an AndroidManifest.xml, classes.jar, libs/, res/ and jni/. Assets are not yet supported.
 *
 * @see <a href="http://tools.android.com/tech-docs/new-build-system/aar-format">AAR Format</a>
 */
public class AarImport implements RuleConfiguredTargetFactory {
  private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
  private static final String MERGED_JAR = "classes_and_libs_merged.jar";
  private static final String PROGUARD_SPEC = "proguard.txt";

  private static final ParamFileInfo WORKERS_FORCED_PARAM_FILE_INFO =
      ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED).setUseAlways(true).build();

  private final JavaSemantics javaSemantics;
  private final AndroidSemantics androidSemantics;

  protected AarImport(JavaSemantics javaSemantics, AndroidSemantics androidSemantics) {
    this.javaSemantics = javaSemantics;
    this.androidSemantics = androidSemantics;
  }

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    androidSemantics.checkForMigrationTag(ruleContext);
    AndroidSdkProvider.verifyPresence(ruleContext);

    RuleConfiguredTargetBuilder ruleBuilder = new RuleConfiguredTargetBuilder(ruleContext);
    Artifact aar = ruleContext.getPrerequisiteArtifact("aar");

    Artifact allAarJars = createAarTreeArtifact(ruleContext, "jars");
    Artifact jarMergingParams = createAarArtifact(ruleContext, "jar_merging_params");
    ruleContext.registerAction(
        createAarEmbeddedJarsExtractorActions(ruleContext, aar, allAarJars, jarMergingParams));
    Artifact mergedJar = createAarArtifact(ruleContext, MERGED_JAR);
    ruleContext.registerAction(
        createAarJarsMergingActions(ruleContext, allAarJars, mergedJar, jarMergingParams));

    // AndroidManifest.xml is required in every AAR.
    Artifact androidManifestArtifact = createAarArtifact(ruleContext, ANDROID_MANIFEST);

    SpecialArtifact resources = createAarTreeArtifact(ruleContext, "resources");
    SpecialArtifact assets = createAarTreeArtifact(ruleContext, "assets");
    SpecialArtifact databindingBrFiles = createAarTreeArtifact(ruleContext, "data-binding-br");
    SpecialArtifact databindingSetterStoreFiles =
        createAarTreeArtifact(ruleContext, "data-binding-setter_store");
    ruleContext.registerAction(
        createAarResourcesExtractorActions(
            ruleContext, aar, resources, assets, databindingBrFiles, databindingSetterStoreFiles));

    AndroidDataContext dataContext = androidSemantics.makeContextForNative(ruleContext);
    StampedAndroidManifest manifest = AndroidManifest.forAarImport(androidManifestArtifact);

    boolean neverlink = JavaCommon.isNeverLink(ruleContext);

    ValidatedAndroidResources validatedResources =
        AndroidResources.forAarImport(resources)
            .process(
                ruleContext,
                dataContext,
                manifest,
                DataBinding.contextFrom(ruleContext, dataContext.getAndroidConfig()),
                neverlink);

    MergedAndroidAssets mergedAssets =
        AndroidAssets.forAarImport(assets)
            .process(dataContext, AssetDependencies.fromRuleDeps(ruleContext, neverlink));

    ResourceApk resourceApk = ResourceApk.of(validatedResources, mergedAssets, null, null);

    // There isn't really any use case for building an aar_import target on its own, so the files to
    // build could be empty. The R class JAR and merged JARs are added here as a check for Bazel
    // developers so that `bazel build java/com/my_aar_import` will fail if the resource processing
    // or JAR merging steps fail.
    NestedSet<Artifact> filesToBuild =
        NestedSetBuilder.<Artifact>stableOrder()
            .add(resourceApk.getValidatedResources().getJavaClassJar())
            .add(mergedJar)
            .build();

    Artifact nativeLibs = createAarArtifact(ruleContext, "native_libs.zip");
    ruleContext.registerAction(createAarNativeLibsFilterActions(ruleContext, aar, nativeLibs));

    JavaRuleOutputJarsProvider.Builder jarProviderBuilder =
        new JavaRuleOutputJarsProvider.Builder()
            .addJavaOutput(JavaOutput.builder().setClassJar(mergedJar).build());

    ImmutableList<TransitiveInfoCollection> targets =
        ImmutableList.<TransitiveInfoCollection>builder()
            .addAll(ruleContext.getPrerequisites("exports"))
            .addAll(ruleContext.getPrerequisites("deps"))
            .build();
    JavaCommon common =
        new JavaCommon(
            ruleContext,
            javaSemantics,
            /* sources = */ ImmutableList.of(),
            /* compileDeps = */ targets,
            /* runtimeDeps = */ targets,
            /* bothDeps = */ targets);
    javaSemantics.checkRule(ruleContext, common);

    JavaConfiguration javaConfig = ruleContext.getFragment(JavaConfiguration.class);
    JavaCompilationArtifacts.Builder javaCompilationArtifactsBuilder =
        new JavaCompilationArtifacts.Builder();

    javaCompilationArtifactsBuilder
        .addRuntimeJar(mergedJar)
        .addCompileTimeJarAsFullJar(mergedJar)
        // Allow direct dependents to compile against un-merged R classes
        .addCompileTimeJarAsFullJar(
            ruleContext.getImplicitOutputArtifact(AndroidRuleClasses.ANDROID_RESOURCES_CLASS_JAR));

    Artifact jdepsArtifact = null;
    // Don't register import deps checking actions if the level is off. Since it's off, the
    // check isn't useful anyway, so don't waste resources running it.
    if (javaConfig.getImportDepsCheckingLevel() != ImportDepsCheckingLevel.OFF) {
      jdepsArtifact = createAarArtifact(ruleContext, "jdeps.proto");
      javaCompilationArtifactsBuilder.setCompileTimeDependencies(jdepsArtifact);
      ImportDepsCheckActionBuilder.newBuilder()
          .bootclasspath(getBootclasspath(ruleContext))
          .declareDeps(getCompileTimeJarsFromCollection(targets, /*isDirect=*/ true))
          .transitiveDeps(getCompileTimeJarsFromCollection(targets, /*isDirect=*/ false))
          .checkJars(NestedSetBuilder.<Artifact>stableOrder().add(mergedJar).build())
          .importDepsCheckingLevel(javaConfig.getImportDepsCheckingLevel())
          .jdepsOutputArtifact(jdepsArtifact)
          .ruleLabel(ruleContext.getLabel())
          .buildAndRegister(ruleContext);
    }

    common.setJavaCompilationArtifacts(javaCompilationArtifactsBuilder.build());

    // We pass jdepsArtifact to create the action of extracting ANDROID_MANIFEST. Note that
    // this action does not need jdepsArtifact. The only reason is that we need to check the
    // dependencies of this aar_import, and we need to put its result on the build graph so that the
    // dependency checking action is called.
    ruleContext.registerAction(
        createSingleFileExtractorActions(
            ruleContext, aar, ANDROID_MANIFEST, jdepsArtifact, androidManifestArtifact));

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        common.collectJavaCompilationArgs(/* isNeverLink= */ JavaCommon.isNeverLink(ruleContext));

    // Wire up the source jar for the current target and transitive source jars from dependencies.
    ImmutableList<Artifact> srcJars = ImmutableList.of();
    Artifact srcJar = ruleContext.getPrerequisiteArtifact("srcjar");
    NestedSetBuilder<Artifact> transitiveJavaSourceJarBuilder = NestedSetBuilder.stableOrder();
    if (srcJar != null) {
      srcJars = ImmutableList.of(srcJar);
      transitiveJavaSourceJarBuilder.add(srcJar);
    }
    for (TransitiveInfoCollection target : ruleContext.getPrerequisites("exports")) {
      transitiveJavaSourceJarBuilder.addTransitive(JavaInfo.transitiveSourceJars(target));
    }
    NestedSet<Artifact> transitiveJavaSourceJars = transitiveJavaSourceJarBuilder.build();
    JavaSourceJarsProvider javaSourceJarsProvider =
        JavaSourceJarsProvider.create(transitiveJavaSourceJars, srcJars);

    JavaInfo.Builder javaInfoBuilder =
        JavaInfo.Builder.create()
            .setRuntimeJars(ImmutableList.of(mergedJar))
            .setJavaConstraints(ImmutableList.of("android"))
            .setNeverlink(JavaCommon.isNeverLink(ruleContext))
            .javaCompilationArgs(javaCompilationArgsProvider)
            .javaSourceJars(javaSourceJarsProvider)
            .javaRuleOutputs(jarProviderBuilder.build());

    common.addTransitiveInfoProviders(
        ruleBuilder, javaInfoBuilder, filesToBuild, /*classJar=*/ null);

    DataBindingV2Provider dataBindingV2Provider =
        createDatabindingProvider(ruleContext, databindingBrFiles, databindingSetterStoreFiles);

    resourceApk.addToConfiguredTargetBuilder(
        ruleBuilder,
        ruleContext.getLabel(),
        /* includeStarlarkApiProvider = */ false,
        /* isLibrary = */ true);

    ruleBuilder
        .setFilesToBuild(filesToBuild)
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY)
        .addNativeDeclaredProvider(dataBindingV2Provider)
        .addNativeDeclaredProvider(new ProguardSpecProvider(extractProguardSpecs(ruleContext, aar)))
        .addNativeDeclaredProvider(
            new AndroidNativeLibsInfo(
                AndroidCommon.collectTransitiveNativeLibs(ruleContext).add(nativeLibs).build()))
        .addStarlarkDeclaredProvider(javaInfoBuilder.build());
    if (jdepsArtifact != null) {
      // Add the deps check result so that we can unit test it.
      ruleBuilder.addOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL, jdepsArtifact);
    }
    return ruleBuilder.build();
  }

  private static NestedSet<Artifact> getCompileTimeJarsFromCollection(
      ImmutableList<TransitiveInfoCollection> deps, boolean isDirect) throws RuleErrorException {
    JavaCompilationArgsProvider provider = JavaCompilationArgsProvider.legacyFromTargets(deps);
    return isDirect ? provider.getDirectCompileTimeJars() : provider.getTransitiveCompileTimeJars();
  }

  /**
   * Collect Proguard Specs from transitives and proguard.txt if it exists in the AAR file. In the
   * case the proguard.txt file does exists, we need to extract it from the AAR file
   */
  private NestedSet<Artifact> extractProguardSpecs(RuleContext ruleContext, Artifact aar)
      throws RuleErrorException {

    NestedSet<Artifact> proguardSpecs =
        new ProguardLibrary(ruleContext).collectProguardSpecs(ImmutableSet.of("deps", "exports"));

    Artifact proguardSpecArtifact = createAarArtifact(ruleContext, PROGUARD_SPEC);

    ruleContext.registerAction(
        createAarEmbeddedProguardExtractorActions(ruleContext, aar, proguardSpecArtifact));

    NestedSetBuilder<Artifact> builder = NestedSetBuilder.naiveLinkOrder();
    return builder.addTransitive(proguardSpecs).add(proguardSpecArtifact).build();
  }

  private static boolean isPersistentAarExtractor(RuleContext ruleContext) {
    AndroidConfiguration androidConfig =
        ruleContext.getConfiguration().getFragment(AndroidConfiguration.class);
    return androidConfig.persistentAarExtractor();
  }

  /**
   * Returns either the worker param file or null depending on whether persistent worker mode is on.
   */
  @Nullable
  private static ParamFileInfo getParamFileInfo(RuleContext ruleContext) {
    return isPersistentAarExtractor(ruleContext) ? WORKERS_FORCED_PARAM_FILE_INFO : null;
  }

  /** Adds worker-related execution requirements if worker mode enabled. */
  private static void modifyExecutionInfo(
      RuleContext ruleContext, SpawnAction.Builder actionBuilder) {
    if (isPersistentAarExtractor(ruleContext)) {
      ImmutableMap.Builder<String, String> executionInfo = ImmutableMap.builder();
      executionInfo.putAll(ExecutionRequirements.WORKER_MODE_ENABLED);
      executionInfo.put(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL, "json");
      actionBuilder.setExecutionInfo(executionInfo.buildOrThrow());
    }
  }

  /**
   * Creates action to extract embedded Proguard.txt from an AAR. If the file is not found, an empty
   * file will be created
   */
  private static SpawnAction createAarEmbeddedProguardExtractorActions(
      RuleContext ruleContext, Artifact aar, Artifact proguardSpecArtifact) {
    SpawnAction.Builder actionBuilder = new SpawnAction.Builder();
    ParamFileInfo paramFileInfo = getParamFileInfo(ruleContext);
    modifyExecutionInfo(ruleContext, actionBuilder);
    return actionBuilder
        .useDefaultShellEnvironment()
        .setExecutable(
            ruleContext.getExecutablePrerequisite(AarImportBaseRule.AAR_EMBEDDED_PROGUARD_EXTACTOR))
        .setMnemonic("AarEmbeddedProguardExtractor")
        .setProgressMessage("Extracting proguard.txt from %s", aar.getFilename())
        .addInput(aar)
        .addOutput(proguardSpecArtifact)
        .addCommandLine(
            CustomCommandLine.builder()
                .addExecPath("--input_aar", aar)
                .addExecPath("--output_proguard_file", proguardSpecArtifact)
                .build(),
            paramFileInfo)
        .build(ruleContext);
  }

  private NestedSet<Artifact> getBootclasspath(RuleContext ruleContext) throws RuleErrorException {
    if (AndroidCommon.getAndroidConfig(ruleContext).desugarJava8()) {
      return NestedSetBuilder.<Artifact>stableOrder()
          .addTransitive(
              ruleContext
                  .getPrerequisite("$desugar_java8_extra_bootclasspath")
                  .getProvider(FileProvider.class)
                  .getFilesToBuild())
          .add(AndroidSdkProvider.fromRuleContext(ruleContext).getAndroidJar())
          .build();
    } else {
      return NestedSetBuilder.<Artifact>stableOrder()
          .add(AndroidSdkProvider.fromRuleContext(ruleContext).getAndroidJar())
          .build();
    }
  }

  /**
   * Creates an action to extract a file (specified by the parameter filename) from an AAR file.
   * Note that the parameter jdepsOutputArtifact is not necessary for this action. Conversely, the
   * action of checking dependencies for aar_import needs this action instead. Therefore we add the
   * output artifact of import_deps_checker to this extraction action as input. Therefore, the
   * dependency checking will run each time.
   */
  private static SpawnAction createSingleFileExtractorActions(
      RuleContext ruleContext,
      Artifact aar,
      String filename,
      @Nullable Artifact jdepsOutputArtifact,
      Artifact outputArtifact) {
    SpawnAction.Builder builder =
        new SpawnAction.Builder()
            .useDefaultShellEnvironment()
            .setExecutable(ruleContext.getExecutablePrerequisite(AarImportBaseRule.ZIPPER))
            .setMnemonic("AarFileExtractor")
            .setProgressMessage("Extracting %s from %s", filename, aar.getFilename())
            .addInput(aar)
            .addOutput(outputArtifact)
            .addCommandLine(
                CustomCommandLine.builder()
                    .addExecPath("x", aar)
                    .addPath("-d", outputArtifact.getExecPath().getParentDirectory())
                    .addDynamicString(filename)
                    .build());
    if (jdepsOutputArtifact != null) {
      builder.addInput(jdepsOutputArtifact);
    }
    return builder.build(ruleContext);
  }

  private static SpawnAction createAarResourcesExtractorActions(
      RuleContext ruleContext,
      Artifact aar,
      Artifact resourcesDir,
      Artifact assetsDir,
      Artifact databindingBrFiles,
      Artifact databindingSetterStoreFiles) {

    SpawnAction.Builder actionBuilder = new SpawnAction.Builder();
    ParamFileInfo paramFileInfo = getParamFileInfo(ruleContext);
    modifyExecutionInfo(ruleContext, actionBuilder);
    return actionBuilder
        .useDefaultShellEnvironment()
        .setExecutable(
            ruleContext.getExecutablePrerequisite(AarImportBaseRule.AAR_RESOURCES_EXTRACTOR))
        .setMnemonic("AarResourcesExtractor")
        .setProgressMessage("Extracting AAR Resources for %{label}")
        .addInput(aar)
        .addOutput(resourcesDir)
        .addOutput(assetsDir)
        .addOutput(databindingBrFiles)
        .addOutput(databindingSetterStoreFiles)
        .addCommandLine(
            CustomCommandLine.builder()
                .addExecPath("--input_aar", aar)
                .addExecPath("--output_res_dir", resourcesDir)
                .addExecPath("--output_assets_dir", assetsDir)
                .addExecPath("--output_databinding_br_dir", databindingBrFiles)
                .addExecPath("--output_databinding_setter_store_dir", databindingSetterStoreFiles)
                .build(),
            paramFileInfo)
        .build(ruleContext);
  }

  private static SpawnAction createAarEmbeddedJarsExtractorActions(
      RuleContext ruleContext,
      Artifact aar,
      Artifact jarsTreeArtifact,
      Artifact singleJarParamFile) {
    SpawnAction.Builder actionBuilder = new SpawnAction.Builder();
    ParamFileInfo paramFileInfo = getParamFileInfo(ruleContext);
    modifyExecutionInfo(ruleContext, actionBuilder);
    return actionBuilder
        .useDefaultShellEnvironment()
        .setExecutable(
            ruleContext.getExecutablePrerequisite(AarImportBaseRule.AAR_EMBEDDED_JARS_EXTACTOR))
        .setMnemonic("AarEmbeddedJarsExtractor")
        .setProgressMessage("Extracting classes.jar and libs/*.jar from %s", aar.getFilename())
        .addInput(aar)
        .addOutput(jarsTreeArtifact)
        .addOutput(singleJarParamFile)
        .addCommandLine(
            CustomCommandLine.builder()
                .addExecPath("--input_aar", aar)
                .addExecPath("--output_dir", jarsTreeArtifact)
                .addExecPath("--output_singlejar_param_file", singleJarParamFile)
                .build(),
            paramFileInfo)
        .build(ruleContext);
  }

  private static SpawnAction createAarJarsMergingActions(
      RuleContext ruleContext, Artifact jarsTreeArtifact, Artifact mergedJar, Artifact paramFile)
      throws RuleErrorException {
    SpawnAction.Builder builder = new SpawnAction.Builder().useDefaultShellEnvironment();
    FilesToRunProvider singleJar = JavaToolchainProvider.from(ruleContext).getSingleJar();
    return builder
        .setExecutable(singleJar)
        .setMnemonic("AarJarsMerger")
        .setProgressMessage("Merging AAR embedded jars for %{label}")
        .addInput(jarsTreeArtifact)
        .addOutput(mergedJar)
        .addInput(paramFile)
        .addCommandLine(
            CustomCommandLine.builder()
                .addExecPath("--output", mergedJar)
                .add("--dont_change_compression")
                .add("--normalize")
                .addPrefixedExecPath("@", paramFile)
                .build())
        .build(ruleContext);
  }

  private static SpawnAction createAarNativeLibsFilterActions(
      RuleContext ruleContext, Artifact aar, Artifact outputZip) throws RuleErrorException {

    BuildConfigurationValue configuration = ruleContext.getConfiguration();

    String cpu = null;
    AndroidConfiguration androidConfiguration =
        configuration.getFragment(AndroidConfiguration.class);

    if (androidConfiguration.incompatibleUseToolchainResolution()) {

      // Maps a CPU name as used in an AAR to the corresponding CPU constraint.
      ImmutableMap<String, ConstraintValueInfo> aarCpuToConstraint =
          ImmutableMap.of(
              "arm64-v8a",
                  ruleContext.getPrerequisite("$constraint_arm64", ConstraintValueInfo.PROVIDER),
              "armeabi-v7a",
                  ruleContext.getPrerequisite("$constraint_armv7", ConstraintValueInfo.PROVIDER),
              "x86", ruleContext.getPrerequisite("$constraint_x86", ConstraintValueInfo.PROVIDER),
              "x86_64",
                  ruleContext.getPrerequisite("$constraint_x86_64", ConstraintValueInfo.PROVIDER));

      for (Map.Entry<String, ConstraintValueInfo> e : aarCpuToConstraint.entrySet()) {
        if (ruleContext.targetPlatformHasConstraint(e.getValue())) {
          cpu = e.getKey();
          break;
        }
      }

      if (cpu == null) {
        throw ruleContext.throwWithRuleError(
            String.format(
                "Target platform %s does not match one of the applicable CPU constraints for"
                    + " aar_import %s. Applicable CPU constraints are listed in"
                    + " https://blog.bazel.build/2023/11/15/android-platforms.html",
                ruleContext.getToolchainContexts().getTargetPlatform().label(),
                ruleContext.getLabel()));
      }

    } else {
      cpu = configuration.getCpu();
    }

    SpawnAction.Builder actionBuilder = new SpawnAction.Builder();
    ParamFileInfo paramFileInfo = getParamFileInfo(ruleContext);
    modifyExecutionInfo(ruleContext, actionBuilder);
    return actionBuilder
        .useDefaultShellEnvironment()
        .setExecutable(
            ruleContext.getExecutablePrerequisite(AarImportBaseRule.AAR_NATIVE_LIBS_ZIP_CREATOR))
        .setMnemonic("AarNativeLibsFilter")
        .setProgressMessage("Filtering AAR native libs by architecture for %{label}")
        .addInput(aar)
        .addOutput(outputZip)
        .addCommandLine(
            CustomCommandLine.builder()
                .addExecPath("--input_aar", aar)
                .add("--cpu", cpu)
                .addExecPath("--output_zip", outputZip)
                .build(),
            paramFileInfo)
        .build(ruleContext);
  }

  private static DataBindingV2Provider createDatabindingProvider(
      RuleContext ruleContext,
      SpecialArtifact databindingBrFiles,
      SpecialArtifact databindingSetterStoreFiles) {

    List<? extends DataBindingV2ProviderApi<Artifact>> databindingProvidersFromDeps =
        ruleContext.getPrerequisites("deps", DataBindingV2Provider.PROVIDER);

    Iterable<? extends DataBindingV2ProviderApi<Artifact>> databindingProvidersFromExports =
        ruleContext.getPrerequisites("exports", DataBindingV2Provider.PROVIDER);

    DataBindingV2Provider dataBindingV2Provider =
        DataBindingV2Provider.createProvider(
            databindingSetterStoreFiles,
            /* classInfoFile= */ null,
            databindingBrFiles,
            ruleContext.getRule().getLabel().toString(),
            // TODO: The aar's Java package isn't available during analysis (it's in the manifest
            // inside the aar, or can maybe be inferred elsewhere). This is mostly used for
            // constructing  a nice error message if multiple android_library rules try to generate
            // databinding conflicting classes into the same Java package, so it's not as important
            // for aars.
            /* javaPackage= */ null,
            databindingProvidersFromDeps,
            databindingProvidersFromExports);

    return dataBindingV2Provider;
  }

  private static Artifact createAarArtifact(RuleContext ruleContext, String name) {
    return ruleContext.getUniqueDirectoryArtifact(
        "_aar", name, ruleContext.getBinOrGenfilesDirectory());
  }

  private static SpecialArtifact createAarTreeArtifact(RuleContext ruleContext, String name) {
    PathFragment rootRelativePath = ruleContext.getUniqueDirectory("_aar/unzipped/" + name);
    return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
  }
}
