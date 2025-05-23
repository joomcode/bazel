// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.testing.ToolchainContextSubject.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.toolchains.ConstraintValueLookupUtil.InvalidConstraintValueException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainTypeLookupUtil.InvalidToolchainTypeException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link UnloadedToolchainContext} and {@link ToolchainResolutionFunction}. */
@RunWith(JUnit4.class)
public class ToolchainResolutionFunctionTest extends ToolchainTestCase {

  private EvaluationResult<UnloadedToolchainContext> invokeToolchainResolution(SkyKey key)
      throws InterruptedException {
    try {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    } finally {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false);
    }
  }

  @Test
  public void resolve() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, because platform
    // mac is listed first.
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_hostPlatform() throws Exception {
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')");

    useConfiguration("--platforms=//platforms:linux", "--host_platform=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_hostPlatform_alias() throws Exception {
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    // Set up aliases for the platforms.
    scratch.file(
        "alias/BUILD",
        """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        """);
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')");

    useConfiguration("--platforms=//platforms:linux", "--host_platform=//alias:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  // TODO(katre): Add further tests for optional/mandatory/mixed toolchains.

  @Test
  public void resolve_optional() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, because platform
    // mac is listed first.
    addOptionalToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addOptionalToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(optionalToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_optional_on_first_platform() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
    addOptionalToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(optionalToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_optional_on_second_platform() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
    addOptionalToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:linux', '//platforms:mac')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(optionalToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_max_optional_on_second_platform() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
    // and independent of non-existence of the second optional toolchain
    addOptionalToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    scratch.appendFile("toolchain/BUILD", "toolchain_type(name = 'extra_optional_toolchain')");
    Label extraOptionalToolchainTypeLabel =
        Label.parseCanonicalUnchecked("//toolchain:extra_optional_toolchain");
    ToolchainTypeRequirement extraOptionalToolchainType =
        ToolchainTypeRequirement.builder(extraOptionalToolchainTypeLabel).mandatory(false).build();
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:linux', '//platforms:mac')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(optionalToolchainType, extraOptionalToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_multiple() throws Exception {
    Label secondToolchainTypeLabel = Label.parseCanonicalUnchecked("//second:toolchain_type");
    ToolchainTypeRequirement secondToolchainTypeRequirement =
        ToolchainTypeRequirement.create(secondToolchainTypeLabel);
    scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')");

    addToolchain(
        "main",
        "main_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "main",
        "second_toolchain_linux",
        secondToolchainTypeLabel,
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//main:all',)", "register_execution_platforms('//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:second_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_mandatory_missing() throws Exception {
    // There is no toolchain for the requested type.
    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("No matching toolchains found for types //toolchain:test_toolchain");
  }

  @Test
  public void resolve_multiple_optional() throws Exception {
    Label secondToolchainTypeLabel = Label.parseCanonicalUnchecked("//second:toolchain_type");
    ToolchainTypeRequirement secondToolchainTypeRequirement =
        ToolchainTypeRequirement.builder(secondToolchainTypeLabel).mandatory(false).build();
    scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')");

    addToolchain(
        "main",
        "main_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "main",
        "second_toolchain_linux",
        secondToolchainTypeLabel,
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//main:all',)", "register_execution_platforms('//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:second_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_multiple_optional_missing() throws Exception {
    Label secondToolchainTypeLabel = Label.parseCanonicalUnchecked("//second:toolchain_type");
    ToolchainTypeRequirement secondToolchainTypeRequirement =
        ToolchainTypeRequirement.builder(secondToolchainTypeLabel).mandatory(false).build();
    scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')");

    addToolchain(
        "main",
        "main_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//main:all',)", "register_execution_platforms('//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel);
    assertThat(unloadedToolchainContext)
        .resolvedToolchainLabels()
        .doesNotContain(Label.parseCanonicalUnchecked("//main:second_toolchain_linux_impl"));
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_toolchainTypeAlias() throws Exception {
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux')",
        "register_execution_platforms('//platforms:linux')");

    // Set up an alias for the toolchain type.
    Label aliasedToolchainTypeLabel = Label.parseCanonicalUnchecked("//alias:toolchain_type");
    scratch.file(
        "alias/BUILD", "alias(name = 'toolchain_type', actual = '//toolchain:test_toolchain')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(ToolchainTypeRequirement.create(aliasedToolchainTypeLabel))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_noToolchainType() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    rewriteWorkspace("register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    ToolchainContextKey key = ToolchainContextKey.key().configurationKey(targetConfigKey).build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext.toolchainTypes()).isEmpty();
    // Even with no toolchains requested, should still select the first execution platform.
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_noToolchainType_hostNotAvailable() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    scratch.file(
        "sample/BUILD",
        "constraint_setting(name='demo')",
        "constraint_value(name = 'demo_a', constraint_setting=':demo')",
        "constraint_value(name = 'demo_b', constraint_setting=':demo')",
        "platform(name = 'sample_a',",
        "  constraint_values = [':demo_a'],",
        ")",
        "platform(name = 'sample_b',",
        "  constraint_values = [':demo_b'],",
        ")");
    rewriteWorkspace(
        "register_execution_platforms('//platforms:mac', '//platforms:linux',",
        "    '//sample:sample_a', '//sample:sample_b')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .execConstraintLabels(Label.parseCanonicalUnchecked("//sample:demo_b"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext.toolchainTypes()).isEmpty();
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//sample:sample_b");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_unavailableToolchainType_single() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("fake/toolchain/BUILD", "");
    useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(
                testToolchainType,
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//fake/toolchain:type_1")))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidToolchainTypeException.class);
    assertContainsEvent("no such target '//fake/toolchain:type_1'");
  }

  @Test
  public void resolve_optional_unavailableToolchainType_single() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("fake/toolchain/BUILD", "");
    useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(optionalToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel);
    assertThat(unloadedToolchainContext).resolvedToolchainLabels().isEmpty();
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_unavailableToolchainType_multiple() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("fake/toolchain/BUILD", "");
    useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(
                testToolchainType,
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//fake/toolchain:type_1")),
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//fake/toolchain:type_2")))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidToolchainTypeException.class);
    // Only one of the missing types will be reported, so do not check the specific error message.
  }

  @Test
  public void resolve_invalidTargetPlatform_badTarget() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--platforms=//invalid:not_a_platform");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "//invalid:not_a_platform was referenced as a platform, "
                + "but does not provide PlatformInfo");
  }

  @Test
  public void resolve_invalidTargetPlatform_badPackage() throws Exception {
    scratch.resolve("invalid").delete();
    useConfiguration("--platforms=//invalid:not_a_platform");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("BUILD file not found");
  }

  @Test
  public void resolve_executionPlatform_alias() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, because platform
    // mac is listed first.
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    // Set up aliases for the platforms.
    scratch.file(
        "alias/BUILD",
        """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        """);
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//alias:mac', '//alias:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_invalidHostPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--host_platform=//invalid:not_a_platform");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void resolve_invalidExecutionPlatform() throws Exception {
    // Have to use a rule that doesn't require a target platform, or else there will be a cycle.
    scratch.file("invalid/BUILD", "toolchain_type(name = 'not_a_platform')");
    useConfiguration("--extra_execution_platforms=//invalid:not_a_platform");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void resolve_execConstraints() throws Exception {
    // This should select platform linux, toolchain extra_toolchain_linux, due to extra constraints,
    // even though platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .execConstraintLabels(Label.parseCanonicalUnchecked("//constraints:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_execConstraints_invalid() throws Exception {
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .execConstraintLabels(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidConstraintValueException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//platforms:linux");
  }

  @Test
  public void resolve_noMatchingPlatform() throws Exception {
    // Write toolchain A, and a toolchain implementing it.
    scratch.appendFile(
        "a/BUILD",
        "toolchain_type(name = 'toolchain_type_A')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_A',",
        "    exec_compatible_with = ['//constraints:mac'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");
    // Write toolchain B, and a toolchain implementing it.
    scratch.appendFile(
        "b/BUILD",
        "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
        "toolchain_type(name = 'toolchain_type_B')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_B',",
        "    exec_compatible_with = ['//constraints:linux'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");

    rewriteWorkspace(
        "register_toolchains('//a:toolchain', '//b:toolchain')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//a:toolchain_type_A")),
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//b:toolchain_type_B")))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);
    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext.errorData()).isNotNull();
  }

  @Test
  public void resolve_forceExecutionPlatform() throws Exception {
    // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
    // forced execution platform, even though execution platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_forceExecutionPlatform_alias() throws Exception {
    // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
    // forced execution platform, even though execution platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    // Set up aliases for the platforms.
    scratch.file(
        "alias/BUILD",
        """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        """);
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//alias:mac', '//alias:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_forceExecutionPlatform_host() throws Exception {
    // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
    // forced execution platform, even though execution platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')");

    useConfiguration("--platforms=//platforms:linux", "--host_platform=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  // Regression test for https://github.com/bazelbuild/bazel/issues/22607, where the aliased host
  // platform didn't match with the dereferenced forced execution platform and so no toolchain
  // was selected.
  @Test
  public void resolve_forceExecutionPlatform_host_alias() throws Exception {
    // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
    // forced execution platform, even though execution platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    // Set up aliases for the platforms.
    scratch.file(
        "alias/BUILD",
        """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        """);
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        // This test requires an execution platform that isn't the forced platform in order to
        // trigger.
        "register_execution_platforms('//alias:mac')");

    useConfiguration("--platforms=//platforms:linux", "--host_platform=//alias:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .toolchainTypes(testToolchainType)
            // Use the actual label for the forced exec platform, since this was redeferenced
            // earlier in analysis.
            .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel);
    assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl");
    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void resolve_forceExecutionPlatform_noRequiredToolchains() throws Exception {
    // This should select execution platform linux, due to the forced execution platform, even
    // though execution platform mac is registered first.
    rewriteWorkspace("register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    ToolchainContextKey key =
        ToolchainContextKey.key()
            .configurationKey(targetConfigKey)
            .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
            .build();

    EvaluationResult<UnloadedToolchainContext> result = invokeToolchainResolution(key);

    assertThatEvaluationResult(result).hasNoError();
    UnloadedToolchainContext unloadedToolchainContext = result.get(key);
    assertThat(unloadedToolchainContext).isNotNull();

    assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux");
    assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux");
  }

  @Test
  public void errorProperlyReportedWhenInvalidConfigurationConfiguration() throws Exception {
    // It would be absolutely insane for a user to have a toolchain w/ a config_setting that reads a
    // config_feature_flag; however, should still test the InvalidConfigurationException codepath.
    rewriteWorkspace(
        "register_toolchains('//strange:strange_toolchain')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");
    scratch.file(
        "strange/BUILD",
        "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
        "config_setting(",
        "    name = 'flagged',",
        "    flag_values = {':flag': 'default'},",
        "    transitive_configs = [':flag'],",
        ")",
        "config_feature_flag(",
        "    name = 'flag',",
        "    allowed_values = ['default', 'left', 'right'],",
        "    default_value = 'default',",
        ")",
        "toolchain(",
        "    name = 'strange_toolchain',",
        "    toolchain_type = '//toolchain:test_toolchain',",
        "    target_settings = [':flagged'],",
        "    toolchain = ':strange_test_toolchain')",
        "test_toolchain(",
        "    name = 'strange_test_toolchain',",
        "    data = 'foo')");
    scratch.file(
        "rule/rule_def.bzl",
        "def _impl(ctx):",
        "    pass",
        "my_rule = rule(",
        "    implementation = _impl,",
        "    toolchains = ['//toolchain:test_toolchain'])");
    scratch.file(
        "rule/BUILD",
        "load('//rule:rule_def.bzl', 'my_rule')",
        "my_rule(",
        "    name = 'me',",
        "    transitive_configs = [':flag'],",
        ")");
    // Need this so the feature flag actually gone from the configuration.
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    reporter.removeHandler(failFastHandler); // expect errors
    assertThat(getConfiguredTarget("//rule:me")).isNull();
    assertContainsEvent(
        "Unrecoverable errors resolving config_setting associated with"
            + " //strange:strange_test_toolchain: For config_setting flagged, Feature flag"
            + " //strange:flag was accessed in a configuration it is not present in.");
  }
}
