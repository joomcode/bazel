// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Utility methods that are the canonical way for actions to support path mapping (see {@link
 * PathMapper}).
 */
public final class PathMappers {
  private static final PathFragment BAZEL_OUT = PathFragment.create("bazel-out");
  private static final PathFragment BLAZE_OUT = PathFragment.create("blaze-out");

  /**
   * A special instance for use in {@link AbstractAction#computeKey} when path mapping is generally
   * enabled for an action.
   *
   * <p>When computing an action key, the following approaches to taking path mapping into account
   * do <b>not</b> work:
   *
   * <ul>
   *   <li>Using the actual path mapper is prohibitive since constructing it requires checking for
   *       collisions among the action input's paths when computing the action key, which flattens
   *       the input depsets of all actions that opt into path mapping and also increases CPU usage.
   *   <li>Unconditionally using {@link StrippingPathMapper} can result in stale action keys when an
   *       action is opted out of path mapping at execution time due to input path collisions after
   *       stripping. See path_mapping_test for an example.
   *   <li>Using {@link PathMapper#NOOP} does not distinguish between map_each results built from
   *       strings and those built from {@link
   *       com.google.devtools.build.lib.starlarkbuildapi.FileApi#getExecPathStringForStarlark}.
   *       While the latter will be mapped at execution time, the former won't, resulting in the
   *       same digest for actions that behave differently at execution time. This is covered by
   *       tests in StarlarkRuleImplementationFunctionsTest.
   * </ul>
   *
   * <p>Instead, we use a special path mapping instance that preserves the equality relations
   * between the original config segments, but prepends a fixed string to distinguish hard-coded
   * path strings from mapped paths. This relies on actions using path mapping to be "root
   * agnostic": they must not contain logic that depends on any particular (output) root path.
   */
  private static final PathMapper FOR_FINGERPRINTING =
      execPath -> {
        if (!execPath.startsWith(BAZEL_OUT) && !execPath.startsWith(BLAZE_OUT)) {
          // This is not an output path.
          return execPath;
        }
        String execPathString = execPath.getPathString();
        int startOfConfigSegment = execPathString.indexOf('/') + 1;
        return PathFragment.createAlreadyNormalized(
            execPathString.substring(0, startOfConfigSegment)
                + "pm-"
                + execPathString.substring(startOfConfigSegment));
      };

  // TODO: Remove actions from this list by adding ExecutionRequirements.SUPPORTS_PATH_MAPPING to
  //  their execution info instead.
  private static final ImmutableSet<String> SUPPORTED_MNEMONICS =
      ImmutableSet.of(
          "AndroidLint",
          "CompileAndroidResources",
          "DeJetify",
          "DejetifySrcs",
          "Desugar",
          "DexBuilder",
          "Jetify",
          "JetifySrcs",
          "LinkAndroidResources",
          "MergeAndroidAssets",
          "MergeManifests",
          "ParseAndroidResources",
          "StarlarkAARGenerator",
          "StarlarkMergeCompiledAndroidResources",
          "StarlarkRClassGenerator",
          "Mock action");

  /**
   * Actions that support path mapping should call this method from {@link
   * Action#getKey(ActionKeyContext, ArtifactExpander)}.
   *
   * <p>Compared to {@link #create}, this method does not flatten nested sets and thus can't result
   * in memory regressions.
   *
   * @param outputPathsMode the value of {@link CoreOptions#outputPathsMode}
   * @param fingerprint the fingerprint to add to
   */
  public static void addToFingerprint(
      String mnemonic,
      Map<String, String> executionInfo,
      NestedSet<Artifact> additionalArtifactsForPathMapping,
      ActionKeyContext actionKeyContext,
      OutputPathsMode outputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException {
    // Creating a new PathMapper instance can be expensive, but isn't needed here: Whether and
    // how path mapping applies to the action only depends on the output paths mode and the action
    // inputs, which are already part of the action key.
    OutputPathsMode effectiveOutputPathsMode =
        getEffectiveOutputPathsMode(outputPathsMode, mnemonic, executionInfo);
    if (effectiveOutputPathsMode == OutputPathsMode.STRIP) {
      fingerprint.addString(StrippingPathMapper.GUID);
      // These artifacts are not part of the actual command line or inputs, but influence the
      // behavior of path mapping.
      actionKeyContext.addNestedSetToFingerprint(fingerprint, additionalArtifactsForPathMapping);
    }
  }

  /** Returns the instance to use during action key computation. */
  public static PathMapper forActionKey(OutputPathsMode outputPathsMode) {
    return outputPathsMode == OutputPathsMode.OFF
        ? PathMapper.NOOP
        : PathMappers.FOR_FINGERPRINTING;
  }

  /**
   * Actions that support path mapping should call this method when creating their {@link Spawn}.
   *
   * <p>The returned {@link PathMapper} has to be passed to {@link
   * com.google.devtools.build.lib.actions.CommandLine#arguments(ArtifactExpander, PathMapper)},
   * {@link com.google.devtools.build.lib.actions.CommandLines#expand(ArtifactExpander,
   * PathFragment, PathMapper, CommandLineLimits)} )} or any other variants of these functions. The
   * same instance should also be passed to the {@link Spawn} constructor so that the executor can
   * obtain it via {@link Spawn#getPathMapper()}.
   *
   * <p>Note: This method flattens nested sets and should thus not be called from methods that are
   * executed in the analysis phase.
   *
   * <p>Actions calling this method should also call {@link #addToFingerprint} from {@link
   * Action#getKey(ActionKeyContext, ArtifactExpander)} to ensure correct incremental builds.
   *
   * @param action the {@link AbstractAction} for which a {@link Spawn} is to be created
   * @param outputPathsMode the value of {@link CoreOptions#outputPathsMode}
   * @param isStarlarkAction whether the action is a Starlark action
   * @return a {@link PathMapper} that maps paths of the action's inputs and outputs. May be {@link
   *     PathMapper#NOOP} if path mapping is not applicable to the action.
   */
  public static PathMapper create(
      AbstractAction action, OutputPathsMode outputPathsMode, boolean isStarlarkAction) {
    if (getEffectiveOutputPathsMode(
            outputPathsMode, action.getMnemonic(), action.getExecutionInfo())
        != OutputPathsMode.STRIP) {
      return PathMapper.NOOP;
    }
    return StrippingPathMapper.tryCreate(action, isStarlarkAction).orElse(PathMapper.NOOP);
  }

  /**
   * Helper method to simplify calling {@link #create} for actions that store the configuration
   * directly.
   *
   * @param configuration the configuration
   * @return the value of
   */
  public static OutputPathsMode getOutputPathsMode(
      @Nullable BuildConfigurationValue configuration) {
    if (configuration == null) {
      return OutputPathsMode.OFF;
    }
    return configuration.getOptions().get(CoreOptions.class).outputPathsMode;
  }

  private static OutputPathsMode getEffectiveOutputPathsMode(
      OutputPathsMode outputPathsMode, String mnemonic, Map<String, String> executionInfo) {
    if (executionInfo.containsKey(ExecutionRequirements.LOCAL)
        || (executionInfo.containsKey(ExecutionRequirements.NO_SANDBOX)
            && executionInfo.containsKey(ExecutionRequirements.NO_REMOTE))) {
      // Path mapping requires sandboxed or remote execution.
      return OutputPathsMode.OFF;
    }
    if (outputPathsMode == OutputPathsMode.STRIP
        && (SUPPORTED_MNEMONICS.contains(mnemonic)
            || executionInfo.containsKey(ExecutionRequirements.SUPPORTS_PATH_MAPPING))) {
      return OutputPathsMode.STRIP;
    }
    return OutputPathsMode.OFF;
  }

  private PathMappers() {}
}
