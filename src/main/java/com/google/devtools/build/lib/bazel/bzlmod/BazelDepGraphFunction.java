// Copyright 2022 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableBiMap.toImmutableBiMap;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * This function runs Bazel module resolution, extracts the dependency graph from it and creates a
 * value containing all Bazel modules, along with a few lookup maps that help with further usage. By
 * this stage, module extensions are not evaluated yet.
 */
public class BazelDepGraphFunction implements SkyFunction {

  public BazelDepGraphFunction() {}

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BazelDepGraphFunctionException, InterruptedException {
    BazelModuleResolutionValue selectionResult =
        (BazelModuleResolutionValue) env.getValue(BazelModuleResolutionValue.KEY);
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }
    var depGraph = selectionResult.getResolvedDepGraph();

    ImmutableBiMap<RepositoryName, ModuleKey> canonicalRepoNameLookup =
        computeCanonicalRepoNameLookup(depGraph, starlarkSemantics);
    ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesById;
    try {
      extensionUsagesById = getExtensionUsagesById(depGraph, canonicalRepoNameLookup.inverse());
    } catch (ExternalDepsException e) {
      throw new BazelDepGraphFunctionException(e, Transience.PERSISTENT);
    }

    ImmutableBiMap<String, ModuleExtensionId> extensionUniqueNames =
        calculateUniqueNameForUsedExtensionId(extensionUsagesById, starlarkSemantics);

    char repoNameSeparator =
        starlarkSemantics.getBool(BuildLanguageOptions.INCOMPATIBLE_USE_PLUS_IN_REPO_NAMES)
            ? '+'
            : '~';

    return BazelDepGraphValue.create(
        depGraph,
        canonicalRepoNameLookup,
        depGraph.values().stream().map(AbridgedModule::from).collect(toImmutableList()),
        extensionUsagesById,
        extensionUniqueNames.inverse(),
        resolveRepoOverrides(
            depGraph,
            extensionUsagesById,
            extensionUniqueNames.inverse(),
            canonicalRepoNameLookup,
            repoNameSeparator),
        repoNameSeparator);
  }

  private static ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage>
      getExtensionUsagesById(
          ImmutableMap<ModuleKey, Module> depGraph,
          ImmutableMap<ModuleKey, RepositoryName> moduleKeyToRepositoryNames)
          throws ExternalDepsException {
    ImmutableTable.Builder<ModuleExtensionId, ModuleKey, ModuleExtensionUsage>
        extensionUsagesTableBuilder = ImmutableTable.builder();
    for (Module module : depGraph.values()) {
      RepositoryMapping repoMapping =
          module.getRepoMappingWithBazelDepsOnly(moduleKeyToRepositoryNames);
      LabelConverter labelConverter =
          new LabelConverter(
              PackageIdentifier.create(repoMapping.ownerRepo(), PathFragment.EMPTY_FRAGMENT),
              module.getRepoMappingWithBazelDepsOnly(moduleKeyToRepositoryNames));
      for (ModuleExtensionUsage usage : module.getExtensionUsages()) {
        ModuleExtensionId moduleExtensionId;
        try {
          moduleExtensionId =
              ModuleExtensionId.create(
                  labelConverter.convert(usage.getExtensionBzlFile()),
                  usage.getExtensionName(),
                  usage.getIsolationKey());
        } catch (LabelSyntaxException e) {
          throw ExternalDepsException.withCauseAndMessage(
              Code.BAD_MODULE,
              e,
              "invalid label for module extension found at %s",
              usage.getProxies().getFirst().getLocation());
        }
        if (!moduleExtensionId.getBzlFileLabel().getRepository().isVisible()) {
          throw ExternalDepsException.withMessage(
              Code.BAD_MODULE,
              "invalid label for module extension found at %s: no repo visible as '@%s' here",
              usage.getProxies().getFirst().getLocation(),
              moduleExtensionId.getBzlFileLabel().getRepository().getName());
        }
        extensionUsagesTableBuilder.put(moduleExtensionId, module.getKey(), usage);
      }
    }
    return extensionUsagesTableBuilder.buildOrThrow();
  }

  private static ImmutableBiMap<RepositoryName, ModuleKey> computeCanonicalRepoNameLookup(
      ImmutableMap<ModuleKey, Module> depGraph, StarlarkSemantics semantics) {
    // Find modules with multiple versions in the dep graph. Currently, the only source of such
    // modules is multiple_version_override.
    ImmutableSet<String> multipleVersionsModules =
        depGraph.keySet().stream()
            .collect(groupingBy(ModuleKey::getName, counting()))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Entry::getKey)
            .collect(toImmutableSet());

    // If there is a unique version of this module in the entire dep graph, we elide the version
    // from the canonical repository name. This has a number of benefits:
    // * It prevents the output base from being polluted with repository directories corresponding
    //   to outdated versions of modules, which can be large and would otherwise only be cleaned
    //   up by the discouraged bazel clean --expunge.
    // * It improves cache hit rates by ensuring that a module update doesn't e.g. cause the paths
    //   of all toolchains provided by its extensions to change, which would result in widespread
    //   cache misses on every update.
    return depGraph.keySet().stream()
        .collect(
            toImmutableBiMap(
                key ->
                    multipleVersionsModules.contains(key.getName())
                        ? key.getCanonicalRepoNameWithVersion(semantics)
                        : key.getCanonicalRepoNameWithoutVersion(semantics),
                key -> key));
  }

  private ImmutableBiMap<String, ModuleExtensionId> calculateUniqueNameForUsedExtensionId(
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesById,
      StarlarkSemantics starlarkSemantics) {
    // Calculate a unique name for each used extension id with the following property that is
    // required for BzlmodRepoRuleFunction to unambiguously identify the extension that generates a
    // given repo:
    // After appending a single `~` to each such name, none of the resulting strings is a prefix of
    // any other such string.
    BiMap<String, ModuleExtensionId> extensionUniqueNames = HashBiMap.create();
    for (ModuleExtensionId id : extensionUsagesById.rowKeySet()) {
      int attempt = 1;
      while (extensionUniqueNames.putIfAbsent(
              makeUniqueNameCandidate(id, attempt, starlarkSemantics), id)
          != null) {
        attempt++;
      }
    }
    return ImmutableBiMap.copyOf(extensionUniqueNames);
  }

  private static String makeUniqueNameCandidate(
      ModuleExtensionId id, int attempt, StarlarkSemantics starlarkSemantics) {
    boolean usePlus =
        starlarkSemantics.getBool(BuildLanguageOptions.INCOMPATIBLE_USE_PLUS_IN_REPO_NAMES);
    // Ensure that the resulting extension name (and thus the repository names derived from it) do
    // not start with a tilde.
    RepositoryName repository = id.getBzlFileLabel().getRepository();
    String nonEmptyRepoPart = repository.isMain() && !usePlus ? "_main" : repository.getName();
    // When using a namespace, prefix the extension name with "_" to distinguish the prefix from
    // those generated by non-namespaced extension usages. Extension names are identified by their
    // Starlark identifier, which in the case of an exported symbol cannot start with "_".
    Preconditions.checkArgument(attempt >= 1);
    String extensionNameDisambiguator = attempt == 1 ? "" : String.valueOf(attempt);
    // Avoid emitting unique names that resemble Windows short paths as those can cause additional
    // file IO during analysis (see WindowsShortPath). In both cases, the final tilde is followed
    // by a Starlark identifier (either the exported name of the usage or the extension name),
    // neither of which can start with a digit.
    return id.getIsolationKey()
        .map(
            namespace ->
                String.format(
                    usePlus ? "%s+_%s%s+%s+%s+%s" : "%s~_%s%s~%s~%s~%s",
                    nonEmptyRepoPart,
                    id.getExtensionName(),
                    extensionNameDisambiguator,
                    namespace.getModule().getName(),
                    namespace.getModule().getVersion(),
                    namespace.getUsageExportedName()))
        .orElse(
            nonEmptyRepoPart
                + (usePlus ? "+" : "~")
                + id.getExtensionName()
                + extensionNameDisambiguator);
  }

  private static ImmutableTable<ModuleExtensionId, String, RepositoryName> resolveRepoOverrides(
      ImmutableMap<ModuleKey, Module> depGraph,
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesTable,
      ImmutableMap<ModuleExtensionId, String> extensionUniqueNames,
      ImmutableBiMap<RepositoryName, ModuleKey> canonicalRepoNameLookup,
      char repoNameSeparator) {
    RepositoryMapping rootModuleMappingWithoutOverrides =
        BazelDepGraphValue.getRepositoryMapping(
            ModuleKey.ROOT,
            depGraph,
            extensionUsagesTable,
            extensionUniqueNames,
            canonicalRepoNameLookup,
            // ModuleFileFunction ensures that repos that override other repos are not themselves
            // overridden, so we can safely pass an empty table here instead of resolving chains
            // of overrides.
            ImmutableTable.of(),
            repoNameSeparator);
    ImmutableTable.Builder<ModuleExtensionId, String, RepositoryName> repoOverridesBuilder =
        ImmutableTable.builder();
    for (var extensionId : extensionUsagesTable.rowKeySet()) {
      var rootUsage = extensionUsagesTable.row(extensionId).get(ModuleKey.ROOT);
      if (rootUsage != null) {
        for (var override : rootUsage.getRepoOverrides().entrySet()) {
          repoOverridesBuilder.put(
              extensionId,
              override.getKey(),
              rootModuleMappingWithoutOverrides.get(override.getValue().overridingRepoName()));
        }
      }
    }
    return repoOverridesBuilder.buildOrThrow();
  }

  static class BazelDepGraphFunctionException extends SkyFunctionException {
    BazelDepGraphFunctionException(ExternalDepsException e, Transience transience) {
      super(e, transience);
    }
  }
}
