// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.packages.util.ResourceLoader;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.Pair;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@code CcToolchainProvider}
 */
@RunWith(JUnit4.class)
public class CcToolchainProviderTest extends BuildViewTestCase {
  @Test
  public void testStarlarkCallables() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--cpu=k8", "--force_pic");
    scratch.file(
        "test/rule.bzl",
        "MyInfo = provider()",
        "def _impl(ctx):",
        "  provider = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = provider,",
        "  )",
        "  return MyInfo(",
        "    dirs = provider.built_in_include_directories,",
        "    sysroot = provider.sysroot,",
        "    cpu = provider.cpu,",
        "    ar_executable = provider.ar_executable,",
        "    use_pic_for_dynamic_libraries = provider.needs_pic_for_dynamic_libraries(",
        "      feature_configuration = feature_configuration,",
        "    ),",
        "  )",
        "",
        "my_rule = rule(",
        "  _impl,",
        "  attrs = {'_cc_toolchain': attr.label(default=Label('//test:toolchain'))},",
        "  fragments = [ 'cpp' ],",
        ")");

    scratch.file("test/BUILD",
        "load(':rule.bzl', 'my_rule')",
        "cc_toolchain_alias(name = 'toolchain')",
        "my_rule(name = 'target')");

    ConfiguredTarget ct = getConfiguredTarget("//test:target");
    Provider.Key key = new StarlarkProvider.Key(Label.parseCanonical("//test:rule.bzl"), "MyInfo");
    StructImpl info = (StructImpl) ct.get(key);

    assertThat((String) info.getValue("ar_executable")).endsWith("/usr/bin/mock-ar");

    assertThat(info.getValue("cpu")).isEqualTo("k8");

    assertThat(info.getValue("sysroot")).isEqualTo("/usr/grte/v1");

    boolean usePicForDynamicLibraries = (boolean) info.getValue("use_pic_for_dynamic_libraries");
    assertThat(usePicForDynamicLibraries).isTrue();
  }

  @Test
  public void testToolchainAndSuiteDifferentPackages() throws Exception {
    scratch.file(
        "suite/BUILD",
        "filegroup(name = 'empty')",
        "cc_toolchain_suite(",
        "    name = 'suite',",
        "    toolchains = { 'banana': '//toolchain' },",
        ")");
    scratch.file(
        "toolchain/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "cc_toolchain(",
        "    name = 'toolchain',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':banana_config',",
        ")",
        "cc_toolchain_config(name = 'banana_config')");

    scratch.appendFile("tools/cpp/BUILD", "");
    scratch.overwriteFile(
        "tools/cpp/cc_toolchain_config_lib.bzl",
        ResourceLoader.readFromResources(
            TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"));
    scratch.file(
        "toolchain/cc_toolchain_config.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'tool_path')",
        "def _impl(ctx):",
        "    return cc_common.create_cc_toolchain_config_info(",
        "        ctx = ctx,",
        "        features = [],",
        "        action_configs = [],",
        "        artifact_name_patterns = [],",
        "        cxx_builtin_include_directories = [],",
        "        toolchain_identifier = 'toolchain',",
        "        host_system_name = 'host',",
        "        target_system_name = 'target',",
        "        target_cpu = 'cpu',",
        "        target_libc = 'libc',",
        "        compiler = 'compiler',",
        "        abi_libc_version = 'abi_libc',",
        "        abi_version = 'banana',",
        "        tool_paths = [",
        "             tool_path(name = 'ar', path = 'some/ar'),",
        "             tool_path(name = 'cpp', path = 'some/cpp'),",
        "             tool_path(name = 'gcc', path = 'some/gcc'),",
        "             tool_path(name = 'gcov', path = 'some/gcov'),",
        "             tool_path(name = 'gcovtool', path = 'some/gcovtool'),",
        "             tool_path(name = 'ld', path = 'some/ld'),",
        "             tool_path(name = 'nm', path = 'some/nm'),",
        "             tool_path(name = 'objcopy', path = 'some/objcopy'),",
        "             tool_path(name = 'objdump', path = 'some/objdump'),",
        "             tool_path(name = 'strip', path = 'some/strip'),",
        "             tool_path(name = 'dwp', path = 'some/dwp'),",
        "        ],",
        "        cc_target_os = 'os',",
        "        builtin_sysroot = 'sysroot'",
        "    )",
        "cc_toolchain_config = rule(",
        "    implementation = _impl,",
        "    attrs = {},",
        "    provides = [CcToolchainConfigInfo],",
        "    fragments = ['cpp']",
        ")");

    useConfiguration("--cpu=banana");
    ConfiguredTarget target = getConfiguredTarget("//suite");
    RuleContext ruleContext = getRuleContext(target);
    CcToolchainProvider toolchainProvider = target.get(CcToolchainProvider.PROVIDER);

    assertThat(
            toolchainProvider
                .getToolPathFragment(CppConfiguration.Tool.CPP, ruleContext)
                .toString())
        .isEqualTo("toolchain/some/cpp");
  }

  /*
   * Crosstools should load fine with or without 'gcov-tool'. Those that define 'gcov-tool'
   * should also add a make variable.
   */
  @Test
  public void testGcovToolNotDefined() throws Exception {
    // Crosstool with gcov-tool
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                Pair.of("gcov", "gcov"),
                Pair.of("ld", "ld"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump"),
                Pair.of("strip", "strip"))
            .build()
            .getCcToolchainConfigRule());
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    useConfiguration("--cpu=k8", "--host_cpu=k8");
    CcToolchainProvider ccToolchainProvider =
        getConfiguredTarget("//a:a").get(CcToolchainProvider.PROVIDER);
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    ccToolchainProvider.addGlobalMakeVariables(builder);
    assertThat(builder.build().get("GCOVTOOL")).isNull();
  }

  @Test
  public void testGcovToolDefined() throws Exception {
    // Crosstool with gcov-tool
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("gcov-tool", "path-to-gcov-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                Pair.of("gcov", "gcov"),
                Pair.of("ld", "ld"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump"),
                Pair.of("strip", "strip"))
            .build()
            .getCcToolchainConfigRule());
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    useConfiguration("--cpu=k8", "--host_cpu=k8");
    CcToolchainProvider ccToolchainProvider =
        getConfiguredTarget("//a:a").get(CcToolchainProvider.PROVIDER);
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    ccToolchainProvider.addGlobalMakeVariables(builder);
    assertThat(builder.build().get("GCOVTOOL")).isNotNull();
  }

  @Test
  public void testGcovNotDefined() throws Exception {
    CcToolchainConfig.Builder ccToolchainConfigBuilder =
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("gcov-tool", "path-to-gcov-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                // No path for gcov
                Pair.of("ld", "ld"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump"),
                Pair.of("strip", "strip"));
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        ccToolchainConfigBuilder.build().getCcToolchainConfigRule(),
        "cc_library(",
        "    name = 'lib',",
        "    toolchains = [':a'],",
        ")");
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder);
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    useConfiguration(
        "--cpu=k8", "--host_cpu=k8", "--collect_code_coverage", "--instrumentation_filter=//a[:/]");
    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    String gcovPath = null;
    for (Pair<String, String> pair : instrumentedFilesInfo.getCoverageEnvironment().toList()) {
      if (pair.getFirst().equals("COVERAGE_GCOV_PATH")) {
        gcovPath = pair.getSecond();
        break;
      }
    }
    assertThat(gcovPath).isNull();
  }

  @Test
  public void testLlvmCoverageToolsDefined() throws Exception {
    CcToolchainConfig.Builder ccToolchainConfigBuilder =
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("gcov-tool", "path-to-gcov-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                Pair.of("ld", "ld"),
                Pair.of("llvm-cov", "path-to-llvm-cov"),
                Pair.of("llvm-profdata", "path-to-llvm-profdata"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump"),
                Pair.of("strip", "strip"));
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        ccToolchainConfigBuilder.build().getCcToolchainConfigRule(),
        "cc_library(",
        "    name = 'lib',",
        "    toolchains = [':a'],",
        ")");
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder);
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    useConfiguration(
        "--cpu=k8", "--host_cpu=k8", "--collect_code_coverage", "--instrumentation_filter=//a[:/]");

    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    String llvmCov = null;
    String llvmProfdata = null;
    for (Pair<String, String> pair : instrumentedFilesInfo.getCoverageEnvironment().toList()) {
      if (pair.getFirst().equals("LLVM_COV")) {
        llvmCov = pair.getSecond();
      }
      if (pair.getFirst().equals("LLVM_PROFDATA")) {
        llvmProfdata = pair.getSecond();
      }
    }
    assertThat(llvmCov).isNotNull();
    assertThat(llvmCov).isNotEmpty();
    assertThat(llvmProfdata).isNotNull();
    assertThat(llvmProfdata).isNotEmpty();
  }

  @Test
  public void testLlvmCoverageToolsNotDefined() throws Exception {
    CcToolchainConfig.Builder ccToolchainConfigBuilder =
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("gcov-tool", "path-to-gcov-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                // No paths for llvm-cov, llvm-profdata
                Pair.of("ld", "ld"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump"),
                Pair.of("strip", "strip"));
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        ccToolchainConfigBuilder.build().getCcToolchainConfigRule(),
        "cc_library(",
        "    name = 'lib',",
        "    toolchains = [':a'],",
        ")");
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder);
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    useConfiguration(
        "--cpu=k8", "--host_cpu=k8", "--collect_code_coverage", "--instrumentation_filter=//a[:/]");

    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    String llvmCov = null;
    String llvmProfdata = null;
    for (Pair<String, String> pair : instrumentedFilesInfo.getCoverageEnvironment().toList()) {
      if (pair.getFirst().equals("LLVM_COV")) {
        llvmCov = pair.getSecond();
      }
      if (pair.getFirst().equals("LLVM_PROFDATA")) {
        llvmProfdata = pair.getSecond();
      }
    }

    assertThat(llvmCov).isNull();
    assertThat(llvmProfdata).isNull();
  }

  @Test
  public void testEnableCoveragePropagatesSupportFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        "cc_toolchain_alias(name = 'toolchain')",
        "cc_library(",
        "    name = 'lib',",
        ")");
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]");

    CcToolchainProvider ccToolchainProvider =
        getConfiguredTarget("//a:toolchain").get(CcToolchainProvider.PROVIDER);
    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);

    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isNotEmpty();
    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList())
        .containsExactlyElementsIn(ccToolchainProvider.getCoverageFiles().toList());
  }

  @Test
  public void testDisableCoverageDoesNotPropagateSupportFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        "cc_toolchain_alias(name = 'toolchain')",
        "cc_library(",
        "    name = 'lib',",
        ")");

    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);

    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isEmpty();
  }

  @Test
  public void testUnsupportedSysrootErrorMessage() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(name='empty')",
        "filegroup(name='everything')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        // Not specifying `builtin_sysroot` means the toolchain doesn't support --grte_top.
        CcToolchainConfig.builder().withSysroot("").build().getCcToolchainConfigRule());
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    reporter.removeHandler(failFastHandler);
    useConfiguration("--grte_top=//a", "--cpu=k8", "--host_cpu=k8");
    getConfiguredTarget("//a:a");
    assertContainsEvent("does not support setting --grte_top");
  }

  @Test
  public void testConfigWithMissingToolDefs() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(",
        "   name='empty')",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        CcToolchainConfig.builder()
            .withToolPaths(
                Pair.of("gcc", "path-to-gcc-tool"),
                Pair.of("ar", "ar"),
                Pair.of("cpp", "cpp"),
                Pair.of("gcov", "gcov"),
                Pair.of("ld", "ld"),
                Pair.of("nm", "nm"),
                Pair.of("objdump", "objdump")
                // Pair.of("strip", "strip")
                )
            .build()
            .getCcToolchainConfigRule());
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    reporter.removeHandler(failFastHandler);
    useConfiguration("--cpu=k8", "--host_cpu=k8");
    getConfiguredTarget("//a:a");
    assertContainsEvent("Tool path for 'strip' is missing");
  }

  @Test
  public void testRuntimeLibsAttributesAreNotObligatory() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(name='empty') ",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b' },",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':banana_config',",
        ")",
        "cc_toolchain_config(name = 'banana_config')");
    scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN);
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    reporter.removeHandler(failFastHandler);
    useConfiguration("--cpu=k8", "--host_cpu=k8");
    getConfiguredTarget("//a:a");
    assertNoEvents();
  }

  @Test
  public void testWhenStaticRuntimeLibAttributeMandatoryWhenSupportsEmbeddedRuntimes()
      throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(name = 'empty')",
        "cc_binary(name = 'main', srcs = [ 'main.cc' ],)",
        "cc_binary(name = 'test', linkstatic = 0, srcs = [ 'test.cc' ],)",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b'},",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        CcToolchainConfig.builder()
            .withFeatures(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)
            .build()
            .getCcToolchainConfigRule(),
        "toolchain(",
        "  name = 'cc-toolchain-b',",
        "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
        "  toolchain = ':b',",
        "  target_compatible_with = [],",
        "  exec_compatible_with = [],",
        ")");
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    reporter.removeHandler(failFastHandler);
    useConfiguration(
        "--extra_toolchains=//a:cc-toolchain-b",
        "--crosstool_top=//a:a",
        "--cpu=k8",
        "--host_cpu=k8");
    assertThat(getConfiguredTarget("//a:main")).isNull();
    assertContainsEvent(
        "Toolchain supports embedded runtimes, but didn't provide static_runtime_lib attribute.");
  }

  @Test
  public void testWhenDynamicRuntimeLibAttributeMandatoryWhenSupportsEmbeddedRuntimes()
      throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
        "filegroup(name = 'empty')",
        "cc_binary(name = 'main', srcs = [ 'main.cc' ],)",
        "cc_binary(name = 'test', linkstatic = 0, srcs = [ 'test.cc' ],)",
        "cc_toolchain_suite(",
        "    name = 'a',",
        "    toolchains = { 'k8': ':b'},",
        ")",
        "cc_toolchain(",
        "    name = 'b',",
        "    all_files = ':empty',",
        "    ar_files = ':empty',",
        "    as_files = ':empty',",
        "    compiler_files = ':empty',",
        "    dwp_files = ':empty',",
        "    linker_files = ':empty',",
        "    strip_files = ':empty',",
        "    objcopy_files = ':empty',",
        "    static_runtime_lib = ':empty',",
        "    toolchain_identifier = 'banana',",
        "    toolchain_config = ':k8-compiler_config',",
        ")",
        CcToolchainConfig.builder()
            .withFeatures(
                CppRuleClasses.STATIC_LINK_CPP_RUNTIMES, CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            .build()
            .getCcToolchainConfigRule(),
        "toolchain(",
        "  name = 'cc-toolchain-b',",
        "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
        "  toolchain = ':b',",
        "  target_compatible_with = [],",
        "  exec_compatible_with = [],",
        ")");
    analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder());
    mockToolsConfig.create(
        "a/cc_toolchain_config.bzl",
        ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"));
    reporter.removeHandler(failFastHandler);
    useConfiguration(
        "--extra_toolchains=//a:cc-toolchain-b",
        "--crosstool_top=//a:a",
        "--cpu=k8",
        "--host_cpu=k8",
        "--dynamic_mode=fully");
    assertThat(getConfiguredTarget("//a:test")).isNull();
    assertContainsEvent(
        "Toolchain supports embedded runtimes, but didn't provide dynamic_runtime_lib attribute.");
  }

  @Test
  public void testDwpFilesIsBlocked() throws Exception {
    scratch.file(
        "foobar/dwp_files_rule.bzl",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "  cc_toolchain.dwp_files()",
        "  return []",
        "dwp_files_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {'_cc_toolchain':" + " attr.label(default=Label('//foobar:alias'))},",
        ")");
    scratch.file(
        "foobar/BUILD",
        "load(':dwp_files_rule.bzl', 'dwp_files_rule')",
        "cc_toolchain_alias(name='alias')",
        "dwp_files_rule(name = 'target')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foobar:target");

    assertContainsEvent("cannot use private API");
  }
}
