// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.IterablesChain;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.cmdline.SymbolGenerator;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.android.ZipFilterBuilder.CheckHashMismatchMode;
import com.google.devtools.build.lib.rules.android.databinding.DataBindingContext;
import com.google.devtools.build.lib.rules.android.databinding.DataBindingV2Provider;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.LinkOptions;
import com.google.devtools.build.lib.rules.java.BootClassPathInfo;
import com.google.devtools.build.lib.rules.java.ClasspathConfiguredFragment;
import com.google.devtools.build.lib.rules.java.JavaCommon;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider.ClasspathType;
import com.google.devtools.build.lib.rules.java.JavaCompilationArtifacts;
import com.google.devtools.build.lib.rules.java.JavaCompilationHelper;
import com.google.devtools.build.lib.rules.java.JavaCompileOutputs;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaTargetAttributes;
import com.google.devtools.build.lib.rules.java.JavaUtil;
import com.google.devtools.build.lib.rules.java.proto.GeneratedExtensionRegistryProvider;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * A helper class for android rules.
 *
 * <p>Helps create the java compilation as well as handling the exporting of the java compilation
 * artifacts to the other rules.
 */
public class AndroidCommon {

  public static final InstrumentationSpec ANDROID_COLLECTION_SPEC =
      JavaCommon.JAVA_COLLECTION_SPEC.withDependencyAttributes(
          "application_resources",
          "deps",
          "data",
          "exports",
          "instruments",
          "runtime_deps",
          "binary_under_test");

  private static final ImmutableSet<String> TRANSITIVE_ATTRIBUTES =
      ImmutableSet.of("application_resources", "deps", "exports");

  private static final int DEX_THREADS = 5;
  private static final ResourceSet DEX_RESOURCE_SET =
      ResourceSet.createWithRamCpu(/* memoryMb= */ 4096.0, /* cpu= */ DEX_THREADS);

  public static final <T extends Info> Iterable<T> getTransitivePrerequisites(
      RuleContext ruleContext, BuiltinProvider<T> key) {
    IterablesChain.Builder<T> builder = IterablesChain.builder();
    AttributeMap attributes = ruleContext.attributes();
    for (String attr : TRANSITIVE_ATTRIBUTES) {
      if (attributes.has(attr, BuildType.LABEL_LIST)) {
        builder.add(ruleContext.getPrerequisites(attr, key));
      }
    }
    return builder.build();
  }

  private final RuleContext ruleContext;
  private final JavaCommon javaCommon;
  private final boolean asNeverLink;

  private NestedSet<Artifact> filesToBuild;
  private NestedSet<Artifact> transitiveNeverlinkLibraries =
      NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private JavaCompilationArgsProvider javaCompilationArgs = JavaCompilationArgsProvider.EMPTY;
  private NestedSet<Artifact> jarsProducedForRuntime;
  private Artifact classJar;
  private JavaCompileOutputs<Artifact> outputs;
  private Artifact iJar;
  private Artifact srcJar;
  private Artifact resourceSourceJar;
  private GeneratedExtensionRegistryProvider generatedExtensionRegistryProvider;
  private final JavaSourceJarsProvider.Builder javaSourceJarsProviderBuilder =
      JavaSourceJarsProvider.builder();
  private final JavaRuleOutputJarsProvider.Builder javaRuleOutputJarsProviderBuilder =
      JavaRuleOutputJarsProvider.builder();
  private AndroidIdlHelper idlHelper;

  public AndroidCommon(JavaCommon javaCommon) {
    this(javaCommon, JavaCommon.isNeverLink(javaCommon.getRuleContext()));
  }

  /**
   * Creates a new AndroidCommon.
   *
   * @param common the JavaCommon instance
   * @param asNeverLink Boolean to indicate if this rule should be treated as a compile time dep by
   *     consuming rules.
   */
  public AndroidCommon(JavaCommon common, boolean asNeverLink) {
    this.ruleContext = common.getRuleContext();
    this.asNeverLink = asNeverLink;
    this.javaCommon = common;
  }

  /**
   * Collects the transitive neverlink dependencies.
   *
   * @param ruleContext the context of the rule neverlink deps are to be computed for
   * @param deps the targets to be treated as dependencies
   * @param runtimeJars the runtime jars produced by the rule (non-transitive)
   * @return a nested set of the neverlink deps.
   */
  public static NestedSet<Artifact> collectTransitiveNeverlinkLibraries(
      RuleContext ruleContext,
      Iterable<? extends TransitiveInfoCollection> deps,
      NestedSet<Artifact> runtimeJars)
      throws RuleErrorException {
    NestedSetBuilder<Artifact> neverlinkedRuntimeJars = NestedSetBuilder.naiveLinkOrder();
    for (AndroidNeverLinkLibrariesProvider provider :
        AnalysisUtils.getProviders(deps, AndroidNeverLinkLibrariesProvider.PROVIDER)) {
      neverlinkedRuntimeJars.addTransitive(provider.getTransitiveNeverLinkLibraries());
    }

    if (JavaCommon.isNeverLink(ruleContext)) {
      neverlinkedRuntimeJars.addTransitive(runtimeJars);
      for (TransitiveInfoCollection dep : deps) {
        neverlinkedRuntimeJars.addTransitive(JavaInfo.transitiveRuntimeJars(dep));
      }
    }
    return neverlinkedRuntimeJars.build();
  }

  /**
   * Creates an action that converts {@code jarToDex} to a dex file. The output will be stored in
   * the {@link com.google.devtools.build.lib.actions.Artifact} {@code dxJar}.
   */
  public static void createDexAction(
      RuleContext ruleContext,
      Artifact jarToDex,
      Artifact classesDex,
      List<String> dexOptions,
      int minSdkVersion,
      Artifact mainDexList)
      throws RuleErrorException {
    CustomCommandLine.Builder commandLine = CustomCommandLine.builder();
    commandLine.add("--dex");

    commandLine.addAll(dexOptions);
    if (minSdkVersion > 0) {
      commandLine.add("--min_sdk_version", Integer.toString(minSdkVersion));
    }
    commandLine.add("--multi-dex");
    if (mainDexList != null) {
      commandLine.addPrefixedExecPath("--main-dex-list=", mainDexList);
    }
    commandLine.addPrefixedExecPath("--output=", classesDex);
    commandLine.addExecPath(jarToDex);

    SpawnAction.Builder builder =
        new SpawnAction.Builder()
            .useDefaultShellEnvironment()
            .setExecutable(AndroidSdkProvider.fromRuleContext(ruleContext).getDx())
            .addInput(jarToDex)
            .addOutput(classesDex)
            .setProgressMessage("Converting %s to dex format", jarToDex.getExecPathString())
            .setMnemonic("AndroidDexer")
            .addCommandLine(commandLine.build())
            // TODO(ulfjack): Use 1 CPU if multidex is true?
            .setResources(DEX_RESOURCE_SET);
    if (mainDexList != null) {
      builder.addInput(mainDexList);
    }
    ruleContext.registerAction(builder.build(ruleContext));
  }

  public static AndroidIdeInfoProvider createAndroidIdeInfoProvider(
      RuleContext ruleContext,
      AndroidIdlHelper idlHelper,
      JavaOutput resourceJarJavaOutput,
      Artifact aar,
      ResourceApk resourceApk,
      Artifact zipAlignedApk,
      Iterable<Artifact> apksUnderTest,
      NativeLibs nativeLibs) {
    AndroidIdeInfoProvider.Builder ideInfoProviderBuilder =
        new AndroidIdeInfoProvider.Builder()
            .setIdlClassJar(idlHelper.getIdlClassJar())
            .setIdlSourceJar(idlHelper.getIdlSourceJar())
            .setResourceJarJavaOutput(resourceJarJavaOutput)
            .setAar(aar)
            .setNativeLibs(nativeLibs.getMap())
            .addIdlImportRoot(idlHelper.getIdlImportRoot())
            .addIdlSrcs(idlHelper.getIdlSources())
            .addIdlGeneratedJavaFiles(idlHelper.getIdlGeneratedJavaSources())
            .addAllApksUnderTest(apksUnderTest);

    if (zipAlignedApk != null) {
      ideInfoProviderBuilder.setApk(zipAlignedApk);
    }

    // If the rule defines resources, put those in the IDE info.
    if (AndroidResources.definesAndroidResources(ruleContext.attributes())) {
      ideInfoProviderBuilder
          .setDefinesAndroidResources(true)
          // Sets the possibly merged manifest and the raw manifest.
          .setGeneratedManifest(resourceApk.getManifest())
          .setManifest(ruleContext.getPrerequisiteArtifact("manifest"))
          .setJavaPackage(getJavaPackage(ruleContext))
          .setResourceApk(resourceApk.getArtifact());
    }

    return ideInfoProviderBuilder.build();
  }

  /**
   * Gets the Java package for the current target.
   *
   * @deprecated If no custom_package is specified, this method will derive the Java package from
   *     the package path, even if that path is not a valid Java path. Use {@code
   *     AndroidManifest#getAndroidPackage(RuleContext)}} instead.
   */
  @Deprecated
  public static String getJavaPackage(RuleContext ruleContext) {
    AttributeMap attributes = ruleContext.attributes();
    if (attributes.isAttributeValueExplicitlySpecified("custom_package")) {
      return attributes.get("custom_package", Type.STRING);
    }
    return getDefaultJavaPackage(ruleContext.getRule());
  }

  private static String getDefaultJavaPackage(Rule rule) {
    PathFragment nameFragment = rule.getPackage().getNameFragment();
    String packageName = JavaUtil.getJavaFullClassname(nameFragment);
    if (packageName != null) {
      return packageName;
    } else {
      // This is a workaround for libraries that don't follow the standard Bazel package format
      return nameFragment.getPathString().replace('/', '.');
    }
  }

  @Nullable
  static PathFragment getSourceDirectoryRelativePathFromResource(Artifact resource) {
    PathFragment resourceDir = AndroidResources.findResourceDir(resource);
    if (resourceDir == null) {
      return null;
    }
    return trimTo(resource.getRootRelativePath(), resourceDir);
  }

  /**
   * Finds the rightmost occurrence of the needle and returns subfragment of the haystack from left
   * to the end of the occurrence inclusive of the needle.
   *
   * <pre>
   * `Example:
   *   Given the haystack:
   *     res/research/handwriting/res/values/strings.xml
   *   And the needle:
   *     res
   *   Returns:
   *     res/research/handwriting/res
   * </pre>
   */
  static PathFragment trimTo(PathFragment haystack, PathFragment needle) {
    if (needle.equals(PathFragment.EMPTY_FRAGMENT)) {
      return haystack;
    }
    List<String> needleSegments = needle.splitToListOfSegments();
    // Compute the overlap offset for duplicated parts of the needle.
    int[] overlap = new int[needleSegments.size() + 1];
    // Start overlap at -1, as it will cancel out the increment in the search.
    // See http://en.wikipedia.org/wiki/Knuth%E2%80%93Morris%E2%80%93Pratt_algorithm for the
    // details.
    overlap[0] = -1;
    for (int i = 0, j = -1; i < needleSegments.size(); j++, i++, overlap[i] = j) {
      while (j >= 0 && !needleSegments.get(i).equals(needleSegments.get(j))) {
        // Walk the overlap until the bound is found.
        j = overlap[j];
      }
    }
    // TODO(corysmith): reverse the search algorithm.
    // Keep the index of the found so that the rightmost index is taken.
    List<String> haystackSegments = haystack.splitToListOfSegments();
    int found = -1;
    for (int i = 0, j = 0; i < haystackSegments.size(); i++) {

      while (j >= 0 && !haystackSegments.get(i).equals(needleSegments.get(j))) {
        // Not matching, walk the needle index to attempt another match.
        j = overlap[j];
      }
      j++;
      // Needle index is exhausted, so the needle must match.
      if (j == needleSegments.size()) {
        // Record the found index + 1 to be inclusive of the end index.
        found = i + 1;
        // Subtract one from the needle index to restart the search process
        j = j - 1;
      }
    }
    if (found != -1) {
      // Return the subsection of the haystack.
      return haystack.subFragment(0, found);
    }
    throw new IllegalArgumentException(String.format("%s was not found in %s", needle, haystack));
  }

  public static NestedSetBuilder<Artifact> collectTransitiveNativeLibs(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> transitiveNativeLibs = NestedSetBuilder.naiveLinkOrder();
    Iterable<AndroidNativeLibsInfo> infos =
        getTransitivePrerequisites(ruleContext, AndroidNativeLibsInfo.PROVIDER);
    for (AndroidNativeLibsInfo nativeLibsZipsInfo : infos) {
      transitiveNativeLibs.addTransitive(nativeLibsZipsInfo.getNativeLibs());
    }
    return transitiveNativeLibs;
  }

  static boolean getExportsManifest(RuleContext ruleContext) {
    // AndroidLibraryBaseRule has exports_manifest but AndroidBinaryBaseRule does not.
    // ResourceContainers are built for both, so we must check if exports_manifest is present.
    if (!ruleContext.attributes().has("exports_manifest", BuildType.TRISTATE)) {
      return false;
    }
    TriState attributeValue = ruleContext.attributes().get("exports_manifest", BuildType.TRISTATE);

    // If the rule does not have the Android configuration fragment, we default to false.
    boolean exportsManifestDefault =
        ruleContext.isLegalFragment(AndroidConfiguration.class)
            && ruleContext.getFragment(AndroidConfiguration.class).getExportsManifestDefault();
    return attributeValue == TriState.YES
        || (attributeValue == TriState.AUTO && exportsManifestDefault);
  }

  /** Returns the artifact for the debug key for signing the APK. */
  static ImmutableList<Artifact> getApkDebugSigningKeys(RuleContext ruleContext) {
    ImmutableList<Artifact> keys =
        ruleContext.getPrerequisiteArtifacts("debug_signing_keys").list();
    if (!keys.isEmpty()) {
      return keys;
    }
    return ImmutableList.of(ruleContext.getPrerequisiteArtifact("debug_key"));
  }

  private void compileResources(
      JavaSemantics javaSemantics,
      Artifact resourceJavaClassJar,
      Artifact resourceJavaSrcJar,
      JavaCompilationArtifacts.Builder artifactsBuilder,
      JavaTargetAttributes.Builder attributes,
      NestedSetBuilder<Artifact> filesBuilder)
      throws InterruptedException, RuleErrorException {

    packResourceSourceJar(javaSemantics, resourceJavaSrcJar);

    // Add the compiled resource jar to the classpath of the main compilation.
    attributes.addDirectJars(NestedSetBuilder.create(Order.STABLE_ORDER, resourceJavaClassJar));
    // Add the compiled resource jar to the classpath of consuming targets.
    // We don't actually use the ijar. That is almost the same as the resource class jar
    // except for <clinit>, but it takes time to build and waiting for that to build would
    // just delay building the rest of the library.
    artifactsBuilder.addCompileTimeJarAsFullJar(resourceJavaClassJar);

    // Add the compiled resource jar as a declared output of the rule.
    filesBuilder.add(resourceSourceJar);
    filesBuilder.add(resourceJavaClassJar);
  }

  private void packResourceSourceJar(JavaSemantics javaSemantics, Artifact resourcesJavaSrcJar)
      throws InterruptedException, RuleErrorException {

    resourceSourceJar =
        ruleContext.getImplicitOutputArtifact(AndroidRuleClasses.ANDROID_RESOURCES_SOURCE_JAR);

    JavaTargetAttributes.Builder javacAttributes =
        new JavaTargetAttributes.Builder(javaSemantics).addSourceJar(resourcesJavaSrcJar);
    JavaCompilationHelper javacHelper =
        new JavaCompilationHelper(ruleContext, javaSemantics, getJavacOpts(), javacAttributes);
    javacHelper.createSourceJarAction(resourceSourceJar, null);
  }

  @Nullable
  public JavaTargetAttributes init(
      JavaSemantics javaSemantics,
      AndroidSemantics androidSemantics,
      ResourceApk resourceApk,
      boolean addCoverageSupport,
      boolean collectJavaCompilationArgs,
      boolean isBinary,
      boolean shouldCompileJavaSrcs,
      NestedSet<Artifact> excludedRuntimeArtifacts,
      boolean generateExtensionRegistry)
      throws InterruptedException, RuleErrorException {

    if (shouldCompileJavaSrcs) {
      classJar =
          ruleContext.getImplicitOutputArtifact(AndroidRuleClasses.ANDROID_LIBRARY_CLASS_JAR);
    } else {
      // When java compilation happens in application_resources, grab the class jar from its
      // JavaInfo.
      classJar =
          Iterables.getOnlyElement(
                  JavaInfo.getJavaInfo(ruleContext.getPrerequisite("application_resources"))
                      .getOutputJars()
                      .getJavaOutputs())
              .getClassJar();
    }
    idlHelper = new AndroidIdlHelper(ruleContext, classJar);

    BootClassPathInfo bootClassPathInfo = androidSemantics.getBootClassPathInfo(ruleContext);

    ImmutableList.Builder<String> javacopts = ImmutableList.builder();
    javacopts.addAll(androidSemantics.getCompatibleJavacOptions(ruleContext));

    if (shouldCompileJavaSrcs) {
      resourceApk
          .asDataBindingContext()
          .supplyJavaCoptsUsing(ruleContext, isBinary, javacopts::addAll);
    }
    JavaTargetAttributes.Builder attributesBuilder =
        javaCommon
            .initCommon(idlHelper.getIdlGeneratedJavaSources(), javacopts.build())
            .setBootClassPath(bootClassPathInfo);

    if (shouldCompileJavaSrcs) {
      resourceApk
          .asDataBindingContext()
          .supplyAnnotationProcessor(
              ruleContext,
              (plugin, additionalOutputs) -> {
                attributesBuilder.addPlugin(plugin);
                attributesBuilder.addAdditionalOutputs(additionalOutputs);
              });
    }

    if (excludedRuntimeArtifacts != null) {
      attributesBuilder.addExcludedArtifacts(excludedRuntimeArtifacts);
    }

    JavaCompilationArtifacts.Builder artifactsBuilder = new JavaCompilationArtifacts.Builder();
    NestedSetBuilder<Artifact> jarsProducedForRuntime = NestedSetBuilder.<Artifact>stableOrder();
    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.<Artifact>stableOrder();

    Artifact resourceJavaSrcJar = resourceApk.getResourceJavaSrcJar();
    if (resourceJavaSrcJar != null) {
      filesBuilder.add(resourceJavaSrcJar);

      compileResources(
          javaSemantics,
          resourceApk.getResourceJavaClassJar(),
          resourceJavaSrcJar,
          artifactsBuilder,
          attributesBuilder,
          filesBuilder);

      // Combined resource constants needs to come even before our own classes that may contain
      // local resource constants.
      artifactsBuilder.addRuntimeJar(resourceApk.getResourceJavaClassJar());
      jarsProducedForRuntime.add(resourceApk.getResourceJavaClassJar());
    }

    ImmutableList<Artifact> additionalJavaInputsFromDatabinding = null;
    if (shouldCompileJavaSrcs) {
      // Databinding metadata that the databinding annotation processor reads.
      additionalJavaInputsFromDatabinding =
          resourceApk.asDataBindingContext().processDeps(ruleContext, isBinary);
    } else {
      ImmutableList.Builder<Artifact> outputs = ImmutableList.<Artifact>builder();
      DataBindingV2Provider p =
          ruleContext.getPrerequisite("application_resources", DataBindingV2Provider.PROVIDER);
      outputs.addAll(p.getSetterStores().toList());
      outputs.addAll(p.getTransitiveBRFiles().toList());
      additionalJavaInputsFromDatabinding = outputs.build();
    }

    JavaCompilationHelper helper =
        initAttributes(attributesBuilder, javaSemantics, additionalJavaInputsFromDatabinding);
    if (ruleContext.hasErrors()) {
      return null;
    }

    if (addCoverageSupport) {
      androidSemantics.addCoverageSupport(ruleContext, true, attributesBuilder);
      if (ruleContext.hasErrors()) {
        return null;
      }
    }

    JavaTargetAttributes attributes = attributesBuilder.build();
    if (shouldCompileJavaSrcs) {
      initJava(
          javaSemantics,
          helper,
          attributes,
          artifactsBuilder,
          collectJavaCompilationArgs,
          filesBuilder,
          generateExtensionRegistry);
      if (ruleContext.hasErrors()) {
        return null;
      }
      if (generatedExtensionRegistryProvider != null) {
        jarsProducedForRuntime.add(generatedExtensionRegistryProvider.getClassJar());
      }
      this.jarsProducedForRuntime = jarsProducedForRuntime.add(classJar).build();
    } else {
      // Getting java artifacts from application_resources
      JavaInfo javaInfo =
          JavaInfo.getJavaInfo(ruleContext.getPrerequisite("application_resources"));
      JavaOutput javaOutput = Iterables.getOnlyElement(javaInfo.getOutputJars().getJavaOutputs());
      outputs = helper.createOutputs(javaOutput);

      // The macro creating both the Starlark android_binary_internal and android_binary ensures
      // that they share the same attributes, which is why we can still check the android_binary
      // attributes when Javac happens in android_binary_internal.
      if (attributes.hasSources() || attributes.hasResources()) {
        // We only want to add a jar to the classpath of a dependent rule if it has content.
        artifactsBuilder.addRuntimeJar(classJar);
      }

      artifactsBuilder.addCompileTimeJarAsFullJar(classJar);
      javaSourceJarsProviderBuilder
          .addAllSourceJars(javaInfo.getSourceJars())
          .addAllTransitiveSourceJars(
              javaCommon.collectTransitiveSourceJars(javaInfo.getSourceJars()));

      if (generateExtensionRegistry) {
        generatedExtensionRegistryProvider =
            javaSemantics.createGeneratedExtensionRegistry(
                ruleContext,
                javaCommon,
                filesBuilder,
                artifactsBuilder,
                javaRuleOutputJarsProviderBuilder,
                javaSourceJarsProviderBuilder);
      }

      JavaCompilationArtifacts javaArtifacts = artifactsBuilder.build();
      javaCommon.setJavaCompilationArtifacts(javaArtifacts);
      filesToBuild = filesBuilder.build();
      NestedSetBuilder<Artifact> runtimeJars =
          NestedSetBuilder.<Artifact>naiveLinkOrder().addAll(javaInfo.getDirectRuntimeJars());
      if (generatedExtensionRegistryProvider != null) {
        jarsProducedForRuntime.add(generatedExtensionRegistryProvider.getClassJar());
        runtimeJars.add(generatedExtensionRegistryProvider.getClassJar());
      }
      transitiveNeverlinkLibraries =
          collectTransitiveNeverlinkLibraries(
              ruleContext, javaCommon.getDependencies(), runtimeJars.build());
      this.jarsProducedForRuntime = jarsProducedForRuntime.add(classJar).build();
      if (collectJavaCompilationArgs) {
        this.javaCompilationArgs = javaCommon.collectJavaCompilationArgs(asNeverLink);
      }
    }
    return attributes;
  }

  private JavaCompilationHelper initAttributes(
      JavaTargetAttributes.Builder attributes,
      JavaSemantics semantics,
      ImmutableList<Artifact> additionalArtifacts)
      throws RuleErrorException {
    JavaCompilationHelper helper =
        new JavaCompilationHelper(
            ruleContext, semantics, javaCommon.getJavacOpts(), attributes, additionalArtifacts);

    helper.addLibrariesToAttributes(javaCommon.targetsTreatedAsDeps(ClasspathType.COMPILE_ONLY));
    attributes.setTargetLabel(ruleContext.getLabel());

    ruleContext.checkSrcsSamePackage(true);
    return helper;
  }

  private void initJava(
      JavaSemantics javaSemantics,
      JavaCompilationHelper helper,
      JavaTargetAttributes attributes,
      JavaCompilationArtifacts.Builder javaArtifactsBuilder,
      boolean collectJavaCompilationArgs,
      NestedSetBuilder<Artifact> filesBuilder,
      boolean generateExtensionRegistry)
      throws InterruptedException, RuleErrorException {
    if (ruleContext.hasErrors()) {
      // Avoid leaving filesToBuild set to null, otherwise we'll get a NullPointerException masking
      // the real error.
      filesToBuild = filesBuilder.build();
      return;
    }

    Artifact jar = null;
    if (attributes.hasSources() || attributes.hasResources()) {
      // We only want to add a jar to the classpath of a dependent rule if it has content.
      javaArtifactsBuilder.addRuntimeJar(classJar);
      jar = classJar;
    }

    filesBuilder.add(classJar);

    outputs = helper.createOutputs(classJar);
    javaArtifactsBuilder.setCompileTimeDependencies(outputs.depsProto());

    srcJar = ruleContext.getImplicitOutputArtifact(AndroidRuleClasses.ANDROID_LIBRARY_SOURCE_JAR);
    javaSourceJarsProviderBuilder
        .addSourceJar(srcJar)
        .addAllTransitiveSourceJars(javaCommon.collectTransitiveSourceJars(srcJar));
    helper.createSourceJarAction(srcJar, outputs.genSource());

    helper.createCompileAction(outputs);

    if (generateExtensionRegistry) {
      generatedExtensionRegistryProvider =
          javaSemantics.createGeneratedExtensionRegistry(
              ruleContext,
              javaCommon,
              filesBuilder,
              javaArtifactsBuilder,
              javaRuleOutputJarsProviderBuilder,
              javaSourceJarsProviderBuilder);
    }

    filesToBuild = filesBuilder.build();

    if (attributes.hasSources() && jar != null) {
      iJar = helper.createCompileTimeJarAction(jar, javaArtifactsBuilder);
    }

    JavaCompilationArtifacts javaArtifacts = javaArtifactsBuilder.build();
    javaCommon.setJavaCompilationArtifacts(javaArtifacts);

    javaCommon.setClassPathFragment(
        new ClasspathConfiguredFragment(
            javaCommon.getJavaCompilationArtifacts(),
            attributes,
            asNeverLink,
            helper.getBootclasspathOrDefault().bootclasspath()));

    transitiveNeverlinkLibraries =
        collectTransitiveNeverlinkLibraries(
            ruleContext,
            javaCommon.getDependencies(),
            NestedSetBuilder.<Artifact>naiveLinkOrder()
                .addAll(javaCommon.getJavaCompilationArtifacts().getRuntimeJars())
                .build());
    if (collectJavaCompilationArgs) {
      this.javaCompilationArgs = javaCommon.collectJavaCompilationArgs(asNeverLink);
    }
  }

  public RuleConfiguredTargetBuilder addTransitiveInfoProviders(
      RuleConfiguredTargetBuilder builder,
      Artifact aar,
      ResourceApk resourceApk,
      Artifact zipAlignedApk,
      Iterable<Artifact> apksUnderTest,
      NativeLibs nativeLibs,
      boolean isNeverlink,
      boolean isLibrary)
      throws RuleErrorException {

    idlHelper.addTransitiveInfoProviders(builder, classJar, outputs.manifestProto());

    if (generatedExtensionRegistryProvider != null) {
      builder.addNativeDeclaredProvider(generatedExtensionRegistryProvider);
    }
    JavaOutput resourceJarJavaOutput = null;
    if (resourceApk.getResourceJavaClassJar() != null && resourceSourceJar != null) {
      resourceJarJavaOutput =
          JavaOutput.builder()
              .setClassJar(resourceApk.getResourceJavaClassJar())
              .addSourceJar(resourceSourceJar)
              .build();
      javaRuleOutputJarsProviderBuilder.addJavaOutput(resourceJarJavaOutput);
    }

    JavaRuleOutputJarsProvider ruleOutputJarsProvider =
        javaRuleOutputJarsProviderBuilder
            .addJavaOutput(
                JavaOutput.builder()
                    .fromJavaCompileOutputs(outputs)
                    .setCompileJar(iJar)
                    .setCompileJdeps(
                        javaCommon.getJavaCompilationArtifacts().getCompileTimeDependencyArtifact())
                    .addSourceJar(srcJar)
                    .build())
            .build();
    JavaSourceJarsProvider sourceJarsProvider = javaSourceJarsProviderBuilder.build();
    JavaCompilationArgsProvider compilationArgsProvider = javaCompilationArgs;

    JavaInfo.Builder javaInfoBuilder = JavaInfo.Builder.create();

    javaCommon.addTransitiveInfoProviders(
        builder, javaInfoBuilder, filesToBuild, classJar, ANDROID_COLLECTION_SPEC);

    javaCommon.addGenJarsProvider(
        builder, javaInfoBuilder, outputs.genClass(), outputs.genSource());

    resourceApk.asDataBindingContext().addProvider(builder, ruleContext);

    JavaInfo javaInfo =
        javaInfoBuilder
            .javaCompilationArgs(compilationArgsProvider)
            .javaRuleOutputs(ruleOutputJarsProvider)
            .javaSourceJars(sourceJarsProvider)
            .javaPluginInfo(JavaCommon.getTransitivePlugins(ruleContext))
            .setRuntimeJars(javaCommon.getJavaCompilationArtifacts().getRuntimeJars())
            .setJavaConstraints(ImmutableList.of("android"))
            .setNeverlink(isNeverlink)
            .build();

    // Do not convert the ResourceApk into builtin providers when it is created from
    // Starlark via AndroidApplicationResourceInfo, because native dependency providers are not
    // created in the Starlark pipeline.
    if (resourceApk.isFromAndroidApplicationResourceInfo()
        || (ruleContext
                .getFragment(AndroidConfiguration.class)
                .omitResourcesInfoProviderFromAndroidBinary()
            && !isLibrary)) {
      // Binary rule; allow extracting merged manifest from Starlark via
      // ctx.attr.android_binary.android.merged_manifest, but not much more.
      builder.addStarlarkTransitiveInfo(
          AndroidStarlarkApiProvider.NAME, new AndroidStarlarkApiProvider(/*resourceInfo=*/ null));
    } else {
      resourceApk.addToConfiguredTargetBuilder(
          builder, ruleContext.getLabel(), /* includeStarlarkApiProvider = */ true, isLibrary);
    }

    return builder
        .setFilesToBuild(filesToBuild)
        .addStarlarkDeclaredProvider(javaInfo)
        .addProvider(RunfilesProvider.class, RunfilesProvider.simple(getRunfiles()))
        .addNativeDeclaredProvider(
            createAndroidIdeInfoProvider(
                ruleContext,
                idlHelper,
                resourceJarJavaOutput,
                aar,
                resourceApk,
                zipAlignedApk,
                apksUnderTest,
                nativeLibs))
        .addOutputGroup(
            OutputGroupInfo.HIDDEN_TOP_LEVEL, collectHiddenTopLevelArtifacts(ruleContext))
        .addOutputGroup(
            JavaSemantics.SOURCE_JARS_OUTPUT_GROUP, sourceJarsProvider.getTransitiveSourceJars())
        .addOutputGroup(
            JavaSemantics.DIRECT_SOURCE_JARS_OUTPUT_GROUP,
            NestedSetBuilder.wrap(Order.STABLE_ORDER, sourceJarsProvider.getSourceJars()));
  }

  private Runfiles getRunfiles() {
    // TODO(bazel-team): why return any Runfiles in the neverlink case?
    if (asNeverLink) {
      return new Runfiles.Builder(
              ruleContext.getWorkspaceName(),
              ruleContext.getConfiguration().legacyExternalRunfiles())
          .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
          .build();
    }
    return JavaCommon.getRunfiles(
        ruleContext,
        javaCommon.getJavaSemantics(),
        javaCommon.getJavaCompilationArtifacts(),
        asNeverLink);
  }

  public ImmutableList<String> getJavacOpts() {
    return javaCommon.getJavacOpts();
  }

  public ImmutableList<Artifact> getRuntimeJars() {
    return javaCommon.getJavaCompilationArtifacts().getRuntimeJars();
  }

  /**
   * Returns Jars produced by this rule that may go into the runtime classpath. By contrast {@link
   * #getRuntimeJars()} returns the complete runtime classpath needed by this rule, including
   * dependencies.
   */
  public NestedSet<Artifact> getJarsProducedForRuntime() {
    return jarsProducedForRuntime;
  }

  public Artifact getClassJar() {
    return classJar;
  }

  public NestedSet<Artifact> getTransitiveNeverLinkLibraries() {
    return transitiveNeverlinkLibraries;
  }

  public boolean isNeverLink() {
    return asNeverLink;
  }

  CcInfo getCcInfo() throws RuleErrorException {
    return getCcInfo(
        javaCommon.targetsTreatedAsDeps(ClasspathType.BOTH),
        ImmutableList.of(),
        ruleContext.getLabel(),
        ruleContext.getSymbolGenerator());
  }

  static CcInfo getCcInfo(
      final Collection<? extends TransitiveInfoCollection> deps,
      final ImmutableList<String> linkOpts,
      Label label,
      SymbolGenerator<?> symbolGenerator)
      throws RuleErrorException {

    CcLinkingContext ccLinkingContext =
        CcLinkingContext.builder()
            .setOwner(label)
            .addUserLinkFlags(ImmutableList.of(LinkOptions.of(linkOpts, symbolGenerator)))
            .build();

    CcInfo linkoptsCcInfo = CcInfo.builder().setCcLinkingContext(ccLinkingContext).build();

    ImmutableList<CcInfo> ccInfos =
        Streams.concat(
                Stream.of(linkoptsCcInfo),
                JavaInfo.ccInfos(deps).stream(),
                AnalysisUtils.getProviders(deps, AndroidCcLinkParamsProvider.PROVIDER).stream()
                    .map(AndroidCcLinkParamsProvider::getLinkParams),
                AnalysisUtils.getProviders(deps, CcInfo.PROVIDER).stream())
            .collect(toImmutableList());

    return CcInfo.merge(ccInfos);
  }

  /** Returns {@link AndroidConfiguration} in given context. */
  public static AndroidConfiguration getAndroidConfig(RuleContext context) {
    return context.getConfiguration().getFragment(AndroidConfiguration.class);
  }

  private NestedSet<Artifact> collectHiddenTopLevelArtifacts(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    for (OutputGroupInfo provider :
        getTransitivePrerequisites(ruleContext, OutputGroupInfo.STARLARK_CONSTRUCTOR)) {
      builder.addTransitive(provider.getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL));
    }
    return builder.build();
  }

  /**
   * Returns a {@link JavaCommon} instance with Android data binding support.
   *
   * <p>Binaries need both compile-time and runtime support, while libraries only need compile-time
   * support.
   *
   * <p>No rule needs <i>any</i> support if data binding is disabled.
   */
  static JavaCommon createJavaCommonWithAndroidDataBinding(
      RuleContext ruleContext,
      JavaSemantics semantics,
      DataBindingContext dataBindingContext,
      boolean isLibrary,
      boolean shouldCompileJavaSrcs)
      throws RuleErrorException {

    ImmutableList<Artifact> ruleSources = ruleContext.getPrerequisiteArtifacts("srcs").list();

    /**
     * When within the context of an android_binary rule and shouldCompileJavaSrcs is False, the
     * Java compilation happens within the Starlark rule. We still list the srcs to know if the
     * class jar has content and should be included.
     */
    if (!isLibrary && !shouldCompileJavaSrcs) {
      ImmutableList<TransitiveInfoCollection> runtimeDeps =
          ImmutableList.copyOf(ruleContext.getPrerequisites("application_resources"));
      return new JavaCommon(
          ruleContext,
          semantics,
          ruleSources,
          runtimeDeps, /* compileDeps */
          runtimeDeps,
          runtimeDeps); /* bothDeps */
    }

    ImmutableList<Artifact> dataBindingSources =
        dataBindingContext.getAnnotationSourceFiles(ruleContext);
    ImmutableList<Artifact> srcs =
        ImmutableList.<Artifact>builder().addAll(ruleSources).addAll(dataBindingSources).build();

    ImmutableList<TransitiveInfoCollection> compileDeps;
    ImmutableList<TransitiveInfoCollection> runtimeDeps;
    ImmutableList<TransitiveInfoCollection> bothDeps;

    if (isLibrary) {
      compileDeps = JavaCommon.defaultDeps(ruleContext, semantics, ClasspathType.COMPILE_ONLY);
      compileDeps = AndroidIdlHelper.maybeAddSupportLibs(ruleContext, compileDeps);
      runtimeDeps = JavaCommon.defaultDeps(ruleContext, semantics, ClasspathType.RUNTIME_ONLY);
      bothDeps = JavaCommon.defaultDeps(ruleContext, semantics, ClasspathType.BOTH);
    } else {
      // Binary:
      compileDeps =
          ImmutableList.<TransitiveInfoCollection>builder()
              .addAll(ruleContext.getPrerequisites("application_resources"))
              .addAll(ruleContext.getPrerequisites("deps"))
              .build();
      runtimeDeps = compileDeps;
      bothDeps = compileDeps;
    }

    return new JavaCommon(ruleContext, semantics, srcs, compileDeps, runtimeDeps, bothDeps);
  }

  /**
   * Gets the transitive support APKs required by this rule through the {@code support_apks}
   * attribute.
   */
  static NestedSet<Artifact> getSupportApks(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> supportApks = NestedSetBuilder.stableOrder();
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("support_apks")) {
      ApkInfo apkProvider = dep.get(ApkInfo.PROVIDER);
      FileProvider fileProvider = dep.getProvider(FileProvider.class);
      // If ApkInfo is present, do not check FileProvider for .apk files. For example,
      // android_binary creates a FileProvider containing both the signed and unsigned APKs.
      if (apkProvider != null) {
        supportApks.add(apkProvider.getApk());
      } else if (fileProvider != null) {
        // The rule definition should enforce that only .apk files are allowed, however, it can't
        // hurt to double check.
        supportApks.addAll(
            FileType.filter(fileProvider.getFilesToBuild().toList(), AndroidRuleClasses.APK));
      }
    }
    return supportApks.build();
  }

  /**
   * Used for instrumentation tests. Filter out classes from the instrumentation JAR that are also
   * present in the target JAR. During an instrumentation test, ART will load jars from both APKs
   * into the same classloader. If the same class exists in both jars, there will be runtime
   * crashes.
   *
   * <p>R.class files that share the same package are also filtered out to prevent
   * surprising/incorrect references to resource IDs.
   */
  public static void createZipFilterAction(
      RuleContext ruleContext,
      Artifact in,
      Artifact filter,
      Artifact out,
      CheckHashMismatchMode checkHashMismatch,
      boolean removeAllRClasses) {
    ZipFilterBuilder builder =
        new ZipFilterBuilder(ruleContext)
            .setInputZip(in)
            .addFilterZips(ImmutableList.of(filter))
            .setOutputZip(out)
            .addFileTypeToFilter(".class")
            .setCheckHashMismatchMode(checkHashMismatch)
            // These files are generated by databinding in both the target and the instrumentation
            // app with different contents. We want to keep the one from the target app.
            .addExplicitFilter("/BR\\.class$")
            .addExplicitFilter("/databinding/[^/]+Binding\\.class$");
    if (removeAllRClasses) {
      builder.addExplicitFilter("(^|/)R\\.class").addExplicitFilter("(^|/)R\\$.*\\.class");
    }

    builder.build();
  }
}
