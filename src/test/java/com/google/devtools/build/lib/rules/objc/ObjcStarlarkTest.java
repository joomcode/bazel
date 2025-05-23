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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for Starlark interaction with the objc_* rules. */
@RunWith(JUnit4.class)
public class ObjcStarlarkTest extends ObjcRuleTestCase {
  @Before
  public void setupMyInfo() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(Label.parseCanonical("//myinfo:myinfo.bzl"), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStarlarkRuleCanDependOnNativeAppleRule() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def my_rule_impl(ctx):",
        "   dep = ctx.attr.deps[0]",
        "   library_to_link = dep[CcInfo].linking_context.linker_inputs.to_list()[0].libraries[0]",
        "   return MyInfo(",
        "      found_hdrs = dep[CcInfo].compilation_context.headers.to_list(),",
        "      found_libs = [library_to_link.static_library],",
        "    )",
        "my_rule = rule(implementation = my_rule_impl,",
        "   attrs = {",
        "   'deps': attr.label_list(allow_files = False, mandatory = False,",
        "                           providers = [[CcInfo]]),",
        "})");
    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'my_rule')",
        "my_rule(",
        "    name = 'my_target',",
        "    deps = [':lib'],",
        ")",
        "objc_library(",
        "    name = 'lib',",
        "    srcs = ['a.m'],",
        "    hdrs = ['b.h']",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);
    List<Artifact> starlarkHdrs = (List<Artifact>) myInfo.getValue("found_hdrs");
    List<Artifact> starlarkLibraries = (List<Artifact>) myInfo.getValue("found_libs");

    assertThat(ActionsTestUtil.baseArtifactNames(starlarkHdrs)).contains("b.h");
    assertThat(ActionsTestUtil.baseArtifactNames(starlarkLibraries)).contains("liblib.a");
  }

  @Test
  public void testObjcProviderLegacyName() throws Exception {
    scratch.file(
        "test/my_rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _dep_rule_impl(ctx):",
        "   objc_provider = apple_common.new_objc_provider(strict_include=depset(['foo']))",
        "   return struct(foo = objc_provider)",
        "",
        "def _root_rule_impl(ctx):",
        "   dep = ctx.attr.deps[0]",
        "   return MyInfo(",
        "      strict_include = dep.objc.strict_include,",
        "   )",
        "",
        "root_rule = rule(implementation = _root_rule_impl,",
        "   attrs = {'deps': attr.label_list(providers = [['objc']]),",
        "})",
        "dep_rule = rule(implementation = _dep_rule_impl)");
    scratch.file(
        "test/BUILD",
        "load(':my_rule.bzl', 'root_rule', 'dep_rule')",
        "root_rule(",
        "    name = 'test',",
        "    deps = [':dep'],",
        ")",
        "dep_rule(",
        "    name = 'dep',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//test:test");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);
    Depset strictIncludes = (Depset) myInfo.getValue("strict_include");

    assertThat(strictIncludes.getSet(String.class).toList()).containsExactly("foo");
  }

  @Test
  public void testStarlarkProviderRetrievalNoneIfNoProvider() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "def my_rule_impl(ctx):",
        "   dep = ctx.attr.deps[0]",
        "   objc_provider = dep[apple_common.Objc]", // this is line 3
        "   return []",
        "my_rule = rule(implementation = my_rule_impl,",
        "   attrs = {",
        "   'deps': attr.label_list(allow_files = False, mandatory = False),",
        "})");
    scratch.file("examples/apple_starlark/a.cc");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'my_rule')",
        "my_rule(",
        "    name = 'my_target',",
        "    deps = [':lib'],",
        ")",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = ['a.cc'],",
        "    hdrs = ['b.h']",
        ")");
    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//examples/apple_starlark:my_target"));
    assertThat(e)
        .hasMessageThat()
        .contains("apple_starlark/BUILD:3:8: in my_rule rule //examples/apple_starlark:my_target:");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "File \"/workspace/examples/rule/apple_rules.bzl\", line 3, column 23, in"
                + " my_rule_impl");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "<target //examples/apple_starlark:lib> (rule 'cc_library') "
                + "doesn't contain declared provider 'objc'");
  }

  @Test
  public void testStarlarkProviderCanCheckForExistenceOfObjcProvider() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def my_rule_impl(ctx):",
        "   cc_has_provider = apple_common.Objc in ctx.attr.deps[0]",
        "   objc_has_provider = apple_common.Objc in ctx.attr.deps[1]",
        "   return MyInfo(cc_has_provider=cc_has_provider, objc_has_provider=objc_has_provider)",
        "my_rule = rule(implementation = my_rule_impl,",
        "   attrs = {",
        "   'deps': attr.label_list(allow_files = False, mandatory = False),",
        "})");
    scratch.file("examples/apple_starlark/a.cc");
    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'my_rule')",
        "my_rule(",
        "    name = 'my_target',",
        "    deps = [':cc_lib', ':objc_lib'],",
        ")",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        ")",
        "cc_library(",
        "    name = 'cc_lib',",
        "    srcs = ['a.cc'],",
        ")");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);
    boolean ccResult = (boolean) myInfo.getValue("cc_has_provider");
    boolean objcResult = (boolean) myInfo.getValue("objc_has_provider");
    assertThat(ccResult).isFalse();
    assertThat(objcResult).isTrue();
  }

  @Test
  public void testStarlarkExportsObjcProviderToNativeRule() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "def my_rule_impl(ctx):",
        "    dep = ctx.attr.deps[0]",
        "    return [dep[apple_common.Objc], dep[CcInfo]]",
        "swift_library = rule(",
        "    implementation = my_rule_impl,",
        "    attrs = {",
        "        'deps': attr.label_list(",
        "            allow_files = False,",
        "            mandatory = False,",
        "            providers = [[apple_common.Objc, CcInfo]],",
        "        )",
        "    }",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "examples/apple_starlark/BUILD",
        "load('//test_starlark:apple_binary_starlark.bzl', 'apple_binary_starlark')",
        "load('//examples/rule:apple_rules.bzl', 'swift_library')",
        "package(default_visibility = ['//visibility:public'])",
        "objc_library(",
        "   name = 'lib',",
        "   srcs = ['a.m'],",
        ")",
        "swift_library(",
        "   name='my_target',",
        "   deps=[':lib'],",
        ")",
        "apple_binary_starlark(",
        "   name = 'bin',",
        "   platform_type = 'ios',",
        "   deps = [':my_target']",
        ")");

    ConfiguredTarget binaryTarget = getConfiguredTarget("//examples/apple_starlark:bin");
    AppleExecutableBinaryInfo executableProvider =
        binaryTarget.get(AppleExecutableBinaryInfo.STARLARK_CONSTRUCTOR);
    CcLinkingContext ccLinkingContext = executableProvider.getDepsCcInfo().getCcLinkingContext();

    assertThat(
            Artifact.toRootRelativePaths(
                ccLinkingContext.getStaticModeParamsForDynamicLibraryLibraries()))
        .contains("examples/apple_starlark/liblib.a");
  }

  @Test
  public void testObjcRuleCanDependOnArbitraryStarlarkRuleThatProvidesCcInfo() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "def my_rule_impl(ctx):",
        "   return [CcInfo()]",
        "my_rule = rule(implementation = my_rule_impl,",
        "   attrs = {})");

    scratch.file("examples/apple_starlark/a.m");
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "examples/apple_starlark/BUILD",
        "load('//test_starlark:apple_binary_starlark.bzl', 'apple_binary_starlark')",
        "load('//examples/rule:apple_rules.bzl', 'my_rule')",
        "package(default_visibility = ['//visibility:public'])",
        "my_rule(",
        "   name='my_target'",
        ")",
        "objc_library(",
        "   name = 'lib',",
        "   srcs = ['a.m'],",
        "   deps = [':my_target']",
        ")",
        "apple_binary_starlark(",
        "   name = 'bin',",
        "   platform_type = 'ios',",
        "   deps = [':lib']",
        ")");

    ConfiguredTarget libTarget = getConfiguredTarget("//examples/apple_starlark:lib");
    assertThat(libTarget.get(CcInfo.PROVIDER)).isNotNull();
  }

  @Test
  public void testStarlarkCanAccessAppleConfiguration() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
        "   cpu = ctx.fragments.apple.single_arch_cpu",
        "   platform = ctx.fragments.apple.single_arch_platform",
        "   xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
        "   dead_code_report = ctx.attr._dead_code_report",
        "   env = apple_common.target_apple_env(xcode_config, platform)",
        "   xcode_version = xcode_config.xcode_version()",
        "   sdk_version = xcode_config.sdk_version_for_platform(platform)",
        "   single_arch_platform = ctx.fragments.apple.single_arch_platform",
        "   single_arch_cpu = ctx.fragments.apple.single_arch_cpu",
        "   platform_type = single_arch_platform.platform_type",
        "   bitcode_mode = ctx.fragments.apple.bitcode_mode",
        "   return MyInfo(",
        "      cpu=cpu,",
        "      env=env,",
        "      xcode_version=str(xcode_version),",
        "      sdk_version=str(sdk_version),",
        "      single_arch_platform=str(single_arch_platform),",
        "      single_arch_cpu=str(single_arch_cpu),",
        "      platform_type=str(platform_type),",
        "      bitcode_mode=str(bitcode_mode),",
        "      dead_code_report=str(dead_code_report),",
        "   )",
        "swift_binary = rule(",
        "    implementation = swift_binary_impl,",
        "    fragments = ['apple'],",
        "    attrs = {",
        "        '_xcode_config': attr.label(",
        "            default = configuration_field(",
        "                fragment = 'apple', name = 'xcode_config_label')),",
        "        '_dead_code_report': attr.label(",
        "            default = configuration_field(",
        "                fragment = 'j2objc', name = 'dead_code_report')),",
        "    },",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration("--apple_platform_type=ios", "--cpu=ios_i386", "--xcode_version=7.3");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    Object iosCpu = myInfo.getValue("cpu");
    @SuppressWarnings("unchecked")
    Map<String, String> env = (Map<String, String>) myInfo.getValue("env");
    Object sdkVersion = myInfo.getValue("sdk_version");

    assertThat(iosCpu).isEqualTo("i386");
    assertThat(env).containsEntry("APPLE_SDK_PLATFORM", "iPhoneSimulator");
    assertThat(env).containsEntry("APPLE_SDK_VERSION_OVERRIDE", "8.4");
    assertThat(sdkVersion).isEqualTo("8.4");
    assertThat(myInfo.getValue("xcode_version")).isEqualTo("7.3");
    assertThat(myInfo.getValue("single_arch_platform")).isEqualTo("IOS_SIMULATOR");
    assertThat(myInfo.getValue("single_arch_cpu")).isEqualTo("i386");
    assertThat(myInfo.getValue("platform_type")).isEqualTo("ios");
    assertThat(myInfo.getValue("dead_code_report")).isEqualTo("None");
    // bitcode_mode is deprecated, but ensure it still correctly returns none.
    assertThat(myInfo.getValue("bitcode_mode")).isEqualTo("none");
  }

  @Test
  public void testDefaultJ2objcDeadCodeReport() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   dead_code_report = ctx.attr._dead_code_report",
        "   return MyInfo(",
        "      dead_code_report=str(dead_code_report),",
        "   )",
        "swift_binary = rule(",
        "    implementation = swift_binary_impl,",
        "    fragments = ['j2objc'],",
        "    attrs = {",
        "        '_dead_code_report': attr.label(",
        "            default = configuration_field(",
        "                fragment = 'j2objc', name = 'dead_code_report')),",
        "    },",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration();
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    assertThat(getMyInfoFromTarget(starlarkTarget).getValue("dead_code_report")).isEqualTo("None");
  }

  @Test
  public void testCustomJ2objcDeadCodeReport() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def dead_code_report_impl(ctx):",
        "   return MyInfo(foo='bar')",
        "def swift_binary_impl(ctx):",
        "   dead_code_report = ctx.attr._dead_code_report[MyInfo].foo",
        "   return MyInfo(",
        "      dead_code_report=dead_code_report,",
        "   )",
        "dead_code_report = rule(",
        "    implementation = dead_code_report_impl,",
        ")",
        "swift_binary = rule(",
        "    implementation = swift_binary_impl,",
        "    fragments = ['j2objc'],",
        "    attrs = {",
        "        '_dead_code_report': attr.label(",
        "            default = configuration_field(",
        "                fragment = 'j2objc', name = 'dead_code_report')),",
        "    },",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'dead_code_report', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")",
        "dead_code_report(name='dead_code_report')");

    useConfiguration("--j2objc_dead_code_report=//examples/apple_starlark:dead_code_report");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    assertThat(getMyInfoFromTarget(starlarkTarget).getValue("dead_code_report")).isEqualTo("bar");
  }

  @Test
  public void testStarlarkCanAccessJ2objcTranslationFlags() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   j2objc_flags = ctx.fragments.j2objc.translation_flags",
        "   return MyInfo(",
        "      j2objc_flags=j2objc_flags,",
        "   )",
        "swift_binary = rule(",
        "    implementation = swift_binary_impl,",
        "    fragments = ['j2objc'],",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration("--j2objc_translation_flags=-DTestJ2ObjcFlag");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    @SuppressWarnings("unchecked")
    List<String> flags = (List<String>) getMyInfoFromTarget(starlarkTarget)
        .getValue("j2objc_flags");
    assertThat(flags).contains("-DTestJ2ObjcFlag");
    assertThat(flags).doesNotContain("-unspecifiedFlag");
  }

  @Test
  public void testStarlarkCanAccessApplePlatformNames() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   platform = ctx.fragments.apple.single_arch_platform",
        "   return MyInfo(",
        "      name=platform.name_in_plist,",
        "   )",
        "test_rule = rule(",
        "implementation = _test_rule_impl,",
        "fragments = ['apple']",
        ")");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "   name='my_target',",
        ")");

    useConfiguration("--cpu=ios_i386", "--apple_platform_type=ios");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    Object name = getMyInfoFromTarget(starlarkTarget).getValue("name");
    assertThat(name).isEqualTo("iPhoneSimulator");
  }

  @Test
  public void testStarlarkCanAccessAppleToolchain() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   apple_toolchain = apple_common.apple_toolchain()",
        "   sdk_dir = apple_toolchain.sdk_dir()",
        "   platform_developer_framework_dir = \\",
        "       apple_toolchain.platform_developer_framework_dir(ctx.fragments.apple)",
        "   return MyInfo(",
        "      platform_developer_framework_dir=platform_developer_framework_dir,",
        "      sdk_dir=sdk_dir,",
        "   )",
        "swift_binary = rule(",
        "implementation = swift_binary_impl,",
        "fragments = ['apple']",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration("--apple_platform_type=ios", "--cpu=ios_i386");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    String platformDevFrameworksDir = (String) myInfo.getValue("platform_developer_framework_dir");
    String sdkDir = (String) myInfo.getValue("sdk_dir");

    assertThat(platformDevFrameworksDir)
        .isEqualTo(
            AppleToolchain.developerDir()
                + "/Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks");
    assertThat(sdkDir).isEqualTo(AppleToolchain.sdkDir());
  }

  @Test
  public void testStarlarkCanAccessSdkAndMinimumOs() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
        "   ios_sdk_version = xcode_config.sdk_version_for_platform\\",
        "(apple_common.platform.ios_device)",
        "   watchos_sdk_version = xcode_config.sdk_version_for_platform\\",
        "(apple_common.platform.watchos_device)",
        "   tvos_sdk_version = xcode_config.sdk_version_for_platform\\",
        "(apple_common.platform.tvos_device)",
        "   macos_sdk_version = xcode_config.sdk_version_for_platform\\",
        "(apple_common.platform.macos)",
        "   ios_minimum_os = xcode_config.minimum_os_for_platform_type\\",
        "(apple_common.platform_type.ios)",
        "   watchos_minimum_os = xcode_config.minimum_os_for_platform_type\\",
        "(apple_common.platform_type.watchos)",
        "   tvos_minimum_os = xcode_config.minimum_os_for_platform_type\\",
        "(apple_common.platform_type.tvos)",
        "   return MyInfo(",
        "      ios_sdk_version=str(ios_sdk_version),",
        "      watchos_sdk_version=str(watchos_sdk_version),",
        "      tvos_sdk_version=str(tvos_sdk_version),",
        "      macos_sdk_version=str(macos_sdk_version),",
        "      ios_minimum_os=str(ios_minimum_os),",
        "      watchos_minimum_os=str(watchos_minimum_os),",
        "      tvos_minimum_os=str(tvos_minimum_os)",
        "   )",
        "swift_binary = rule(",
        "    implementation = swift_binary_impl,",
        "    fragments = ['apple'],",
        "    attrs = { '_xcode_config': ",
        "        attr.label(default=configuration_field(",
        "            fragment='apple', name='xcode_config_label')) },",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration("--ios_sdk_version=1.1", "--ios_minimum_os=1.0",
        "--watchos_sdk_version=2.1", "--watchos_minimum_os=2.0",
        "--tvos_sdk_version=3.1", "--tvos_minimum_os=3.0",
        "--macos_sdk_version=4.1");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1");
    assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.0");
    assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1");
    assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.0");
    assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1");
    assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.0");
    assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1");

    useConfiguration("--ios_sdk_version=1.1",
        "--watchos_sdk_version=2.1",
        "--tvos_sdk_version=3.1",
        "--macos_sdk_version=4.1");
    starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    myInfo = getMyInfoFromTarget(starlarkTarget);

    assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1");
    assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.1");
    assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1");
    assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.1");
    assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1");
    assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.1");
    assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1");
  }

  @Test
  public void testStarlarkCanAccessObjcConfiguration() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   copts = ctx.fragments.objc.copts",
        "   compilation_mode_copts = ctx.fragments.objc.copts_for_current_compilation_mode",
        "   ios_simulator_device = ctx.fragments.objc.ios_simulator_device",
        "   ios_simulator_version = ctx.fragments.objc.ios_simulator_version",
        "   signing_certificate_name = ctx.fragments.objc.signing_certificate_name",
        "   return MyInfo(",
        "      copts=copts,",
        "      compilation_mode_copts=compilation_mode_copts,",
        "      ios_simulator_device=ios_simulator_device,",
        "      ios_simulator_version=str(ios_simulator_version),",
        "      signing_certificate_name=signing_certificate_name,",
        "   )",
        "swift_binary = rule(",
        "implementation = swift_binary_impl,",
        "fragments = ['objc']",
        ")");

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        ")");

    useConfiguration(
        "--compilation_mode=opt",
        "--objccopt=-DTestObjcCopt",
        "--ios_simulator_device='iPhone 6'",
        "--ios_simulator_version=8.4",
        "--ios_signing_cert_name='Apple Developer'");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    @SuppressWarnings("unchecked")
    List<String> copts = (List<String>) myInfo.getValue("copts");
    @SuppressWarnings("unchecked")
    List<String> compilationModeCopts = (List<String>) myInfo.getValue("compilation_mode_copts");
    Object iosSimulatorDevice = myInfo.getValue("ios_simulator_device");
    Object iosSimulatorVersion = myInfo.getValue("ios_simulator_version");
    Object signingCertificateName = myInfo.getValue("signing_certificate_name");

    assertThat(copts).contains("-DTestObjcCopt");
    assertThat(compilationModeCopts).containsExactlyElementsIn(ObjcConfiguration.OPT_COPTS);
    assertThat(iosSimulatorDevice).isEqualTo("'iPhone 6'");
    assertThat(iosSimulatorVersion).isEqualTo("8.4");
    assertThat(signingCertificateName).isEqualTo("'Apple Developer'");
  }

  @Test
  public void testSigningCertificateNameCanReturnNone() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def my_rule_impl(ctx):",
        "   signing_certificate_name = ctx.fragments.objc.signing_certificate_name",
        "   return MyInfo(",
        "      signing_certificate_name=str(signing_certificate_name),",
        "   )",
        "my_rule = rule(",
        "implementation = my_rule_impl,",
        "fragments = ['objc']",
        ")");

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'my_rule')",
        "my_rule(",
        "   name='my_target',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");

    Object signingCertificateName =
        getMyInfoFromTarget(starlarkTarget).getValue("signing_certificate_name");
    assertThat(signingCertificateName).isEqualTo("None");
  }

  @Test
  public void testUsesDebugEntitlementsIsTrueIfCompilationModeIsNotOpt() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def test_rule_impl(ctx):",
        "   uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements",
        "   return MyInfo(",
        "      uses_device_debug_entitlements=uses_device_debug_entitlements,",
        "   )",
        "test_rule = rule(",
        "implementation = test_rule_impl,",
        "fragments = ['objc']",
        ")");

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'test_rule')",
        "test_rule(",
        "   name='my_target',",
        ")");

    useConfiguration("--compilation_mode=dbg");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isTrue();
  }

  @Test
  public void testUsesDebugEntitlementsIsFalseIfFlagIsExplicitlyFalse() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def test_rule_impl(ctx):",
        "   uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements",
        "   return MyInfo(",
        "      uses_device_debug_entitlements=uses_device_debug_entitlements,",
        "   )",
        "test_rule = rule(",
        "implementation = test_rule_impl,",
        "fragments = ['objc']",
        ")");

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'test_rule')",
        "test_rule(",
        "   name='my_target',",
        ")");

    useConfiguration(
        "--compilation_mode=dbg",
        "--nodevice_debug_entitlements");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isFalse();
  }

  @Test
  public void testUsesDebugEntitlementsIsFalseIfCompilationModeIsOpt() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def test_rule_impl(ctx):",
        "   uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements",
        "   return MyInfo(",
        "      uses_device_debug_entitlements=uses_device_debug_entitlements,",
        "   )",
        "test_rule = rule(",
        "implementation = test_rule_impl,",
        "fragments = ['objc']",
        ")");

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'test_rule')",
        "test_rule(",
        "   name='my_target',",
        ")");

    useConfiguration("--compilation_mode=opt");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isFalse();
  }

  private ConfiguredTarget createObjcProviderStarlarkTarget(String... implLines) throws Exception {
    String[] impl =
        ObjectArrays.concat(
            ObjectArrays.concat("def swift_binary_impl(ctx):", implLines),
            new String[] {
              "swift_binary = rule(",
              "implementation = swift_binary_impl,",
              "attrs = {",
              "   'deps': attr.label_list(",
              "allow_files = False, mandatory = False, providers = [[apple_common.Objc]])",
              "})"
            },
            String.class);

    scratch.file("examples/rule/BUILD");
    scratch.file("examples/rule/objc_rules.bzl", impl);
    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        "   deps=[':lib'],",
        ")",
        "objc_library(",
        "   name = 'lib',",
        "   srcs = ['a.m'],",
        ")");

    return getConfiguredTarget("//examples/objc_starlark:my_target");
  }

  @Test
  public void testStarlarkCanCreateObjcProviderFromScratch() throws Exception {
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   file = ctx.actions.declare_file('foo.m')",
            "   ctx.actions.run_shell(outputs=[file], command='echo')",
            "   created_provider = apple_common.new_objc_provider(source=depset([file]))",
            "   return [created_provider]");

    ImmutableList<Artifact> sources =
        starlarkTarget.get(ObjcProvider.STARLARK_CONSTRUCTOR).get(ObjcProvider.SOURCE).toList();

    assertThat(ActionsTestUtil.baseArtifactNames(sources)).containsExactly("foo.m");
  }

  @Test
  public void testStarlarkCanCreateObjcProviderWithStrictDeps() throws Exception {
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   strict_includes = depset(['path'])",
            "   created_provider = apple_common.new_objc_provider(strict_include=strict_includes)",
            "   return [created_provider, CcInfo()]");

    ObjcProvider starlarkProvider = starlarkTarget.get(ObjcProvider.STARLARK_CONSTRUCTOR);
    assertThat(starlarkProvider.getStrictDependencyIncludes())
        .containsExactly(PathFragment.create("path"));

    scratch.file(
        "examples/objc_starlark2/BUILD",
        "objc_library(",
        "   name = 'direct_dep',",
        "   deps = ['//examples/objc_starlark:my_target']",
        ")");

    ObjcProvider starlarkProviderDirectDepender =
        getConfiguredTarget("//examples/objc_starlark2:direct_dep")
            .get(ObjcProvider.STARLARK_CONSTRUCTOR);
    assertThat(starlarkProviderDirectDepender.getStrictDependencyIncludes()).isEmpty();
  }

  @Test
  public void testStarlarkCanCreateObjcProviderFromObjcProvider() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        "def library_impl(ctx):",
        "   lib = ctx.label.name + '.a'",
        "   file = ctx.actions.declare_file(lib)",
        "   ctx.actions.run_shell(outputs=[file], command='echo')",
        "   return [apple_common.new_objc_provider(j2objc_library=depset([file]))]",
        "library = rule(implementation = library_impl)",
        "def binary_impl(ctx):",
        "   dep = ctx.attr.deps[0]",
        "   lib = ctx.label.name + '.a'",
        "   file = ctx.actions.declare_file(lib)",
        "   ctx.actions.run_shell(outputs=[file], command='echo')",
        "   created_provider = apple_common.new_objc_provider(",
        "      providers=[dep[apple_common.Objc]],",
        "      j2objc_library=depset([file]),",
        "   )",
        "   return [created_provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        "    attrs = {",
        "        'deps': attr.label_list(",
        "             allow_files = False,",
        "             mandatory = False,",
        "             providers = [[apple_common.Objc]],",
        "        )",
        "    })");

    scratch.file(
        "examples/objc_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:objc_rules.bzl', 'library', 'binary')",
        "binary(",
        "   name='bin',",
        "   deps=[':lib'],",
        ")",
        "library(",
        "   name = 'lib',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:bin");

    ImmutableList<Artifact> libraries =
        starlarkTarget
            .get(ObjcProvider.STARLARK_CONSTRUCTOR)
            .get(ObjcProvider.J2OBJC_LIBRARY)
            .toList();

    assertThat(ActionsTestUtil.baseArtifactNames(libraries)).containsExactly("lib.a", "bin.a");
  }

  @Test
  public void testStarlarkErrorOnBadObjcProviderInputKey() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(foo=depset(['bar']))",
                    "   return created_provider"));
    assertThat(e)
        .hasMessageThat()
        .contains(String.format(AppleStarlarkCommon.BAD_KEY_ERROR, "foo"));
  }

  @Test
  public void testStarlarkErrorOnNonSetObjcProviderInputValue() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(source='bar')",
                    "   return created_provider"));
    assertThat(e).hasMessageThat().contains("for source, got string, want a depset of File");
  }

  @Test
  public void testStarlarkErrorOnObjcProviderInputValueWrongSetType() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(source=depset(['bar']))",
                    "   return created_provider"));
    assertThat(e)
        .hasMessageThat()
        .contains("for 'source', got a depset of 'string', expected a depset of 'File'");
  }

  @Test
  public void testStarlarkErrorOnNonIterableObjcProviderProviderValue() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(providers='bar')",
                    "   return created_provider"));
    assertThat(e)
        .hasMessageThat()
        .contains(String.format(AppleStarlarkCommon.BAD_PROVIDERS_ITER_ERROR, "string"));
  }

  @Test
  public void testStarlarkErrorOnBadIterableObjcProviderProviderValue() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(providers=['bar'])",
                    "   return created_provider"));
    assertThat(e)
        .hasMessageThat()
        .contains(String.format(AppleStarlarkCommon.BAD_PROVIDERS_ELEM_ERROR, "string"));
  }

  @Test
  public void testEmptyObjcProviderKeysArePresent() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def swift_binary_impl(ctx):",
        "   objc_provider = ctx.attr.deps[0][apple_common.Objc]",
        "   return MyInfo(",
        "      empty_value=objc_provider.j2objc_library,",
        "   )",
        "swift_binary = rule(",
        "   implementation = swift_binary_impl,",
        "   fragments = ['apple'],",
        "   attrs = {",
        "      'deps': attr.label_list(",
        "          allow_files = False,",
        "          mandatory = False,",
        "          providers = [[apple_common.Objc]],",
        "      )",
        "   },",
        ")");

    scratch.file("examples/apple_starlark/a.m");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'swift_binary')",
        "swift_binary(",
        "   name='my_target',",
        "   deps=[':lib'],",
        ")",
        "objc_library(",
        "   name = 'lib',",
        "   srcs = ['a.m'],",
        ")");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    Depset emptyValue = (Depset) getMyInfoFromTarget(starlarkTarget).getValue("empty_value");
    assertThat(emptyValue.toList()).isEmpty();
  }

  @Test
  public void testStarlarkCanAccessAndUseApplePlatformTypes() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   apple = ctx.fragments.apple",
        "   ios_platform = apple.multi_arch_platform(apple_common.platform_type.ios)",
        "   watchos_platform = apple.multi_arch_platform(apple_common.platform_type.watchos)",
        "   tvos_platform = apple.multi_arch_platform(apple_common.platform_type.tvos)",
        "   return MyInfo(",
        "      ios_platform=str(ios_platform),",
        "      watchos_platform=str(watchos_platform),",
        "      tvos_platform=str(tvos_platform),",
        "   )",
        "test_rule = rule(implementation = _test_rule_impl,",
        "   fragments = ['apple'])");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    useConfiguration(
        "--ios_multi_cpus=arm64,armv7",
        "--watchos_cpus=armv7k",
        "--tvos_cpus=arm64");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    Object iosPlatform = myInfo.getValue("ios_platform");
    Object watchosPlatform = myInfo.getValue("watchos_platform");
    Object tvosPlatform = myInfo.getValue("tvos_platform");

    assertThat(iosPlatform).isEqualTo("IOS_DEVICE");
    assertThat(watchosPlatform).isEqualTo("WATCHOS_DEVICE");
    assertThat(tvosPlatform).isEqualTo("TVOS_DEVICE");
  }

  @Test
  public void testPlatformIsDeviceReturnsTrueForDevicePlatforms() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   apple = ctx.fragments.apple",
        "   platform = apple.multi_arch_platform(apple_common.platform_type.ios)",
        "   return MyInfo(",
        "      is_device=platform.is_device,",
        "   )",
        "test_rule = rule(implementation = _test_rule_impl,",
        "   fragments = ['apple'])");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    useConfiguration(
        "--ios_multi_cpus=arm64,armv7");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    Boolean isDevice = (Boolean) getMyInfoFromTarget(starlarkTarget).getValue("is_device");
    assertThat(isDevice).isTrue();
  }

  @Test
  public void testPlatformIsDeviceReturnsFalseForSimulatorPlatforms() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   apple = ctx.fragments.apple",
        "   platform = apple.multi_arch_platform(apple_common.platform_type.ios)",
        "   return MyInfo(",
        "      is_device=platform.is_device,",
        "   )",
        "test_rule = rule(implementation = _test_rule_impl,",
        "   fragments = ['apple'])");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    Boolean isDevice = (Boolean) getMyInfoFromTarget(starlarkTarget).getValue("is_device");
    assertThat(isDevice).isFalse();
  }

  @Test
  public void testStarlarkWithRunMemleaksEnabled() throws Exception {
    useConfiguration("--ios_memleaks");
    checkStarlarkRunMemleaksWithExpectedValue(true);
  }

  @Test
  public void testStarlarkWithRunMemleaksDisabled() throws Exception {
    checkStarlarkRunMemleaksWithExpectedValue(false);
  }

  @Test
  public void testDottedVersion() throws Exception {
    scratch.file("examples/rule/BUILD",
        "exports_files(['test_artifact'])");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   version = apple_common.dotted_version('5.4')",
        "   return MyInfo(",
        "       version=version",
        "   )",
        "test_rule = rule(implementation = _test_rule_impl)");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    DottedVersion version = (DottedVersion) getMyInfoFromTarget(starlarkTarget).getValue("version");
    assertThat(version).isEqualTo(DottedVersion.fromString("5.4"));
  }

  @Test
  public void testDottedVersion_invalid() throws Exception {
    scratch.file("examples/rule/BUILD",
        "exports_files(['test_artifact'])");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   version = apple_common.dotted_version('hello')",
        "   return MyInfo(",
        "       version=version",
        "   )",
        "test_rule = rule(implementation = _test_rule_impl)");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//examples/apple_starlark:my_target"));
    assertThat(e)
        .hasMessageThat()
        .contains("Dotted version components must all start with the form");
  }

  /**
   * This test verifies that its possible to use the Starlark constructor of ObjcProvider as a
   * provider key to obtain the provider. This test only needs to exist as long as there are two
   * methods of retrieving ObjcProvider (which is true for legacy reasons). This is the 'new' method
   * of retrieving ObjcProvider.
   */
  @Test
  public void testObjcProviderStarlarkConstructor() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "def my_rule_impl(ctx):",
        "   dep = ctx.attr.deps[0]",
        "   objc_provider = dep[apple_common.Objc]",
        "   return objc_provider",
        "my_rule = rule(implementation = my_rule_impl,",
        "   attrs = {",
        "   'deps': attr.label_list(allow_files = False, mandatory = False),",
        "})");
    scratch.file("examples/apple_starlark/a.cc");
    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'my_rule')",
        "my_rule(",
        "    name = 'my_target',",
        "    deps = [':lib'],",
        ")",
        "objc_library(",
        "    name = 'lib',",
        "    srcs = ['a.m'],",
        "    hdrs = ['a.h']",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");
    assertThat(starlarkTarget.get(ObjcProvider.STARLARK_CONSTRUCTOR)).isNotNull();
  }

  private void checkStarlarkRunMemleaksWithExpectedValue(boolean expectedValue) throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/apple_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _test_rule_impl(ctx):",
        "   return MyInfo(run_memleaks = ctx.fragments.objc.run_memleaks)",
        "test_rule = rule(implementation = _test_rule_impl,",
        "   fragments = ['objc'],",
        "   attrs = {},",
        ")");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "package(default_visibility = ['//visibility:public'])",
        "load('//examples/rule:apple_rules.bzl', 'test_rule')",
        "test_rule(",
        "    name = 'my_target',",
        ")");

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/apple_starlark:my_target");

    boolean runMemleaks = (boolean) getMyInfoFromTarget(starlarkTarget).getValue("run_memleaks");
    assertThat(runMemleaks).isEqualTo(expectedValue);
  }

  @Test
  public void testStaticFrameworkApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    scratch.file(
        "fx/defs.bzl",
        "def _custom_static_framework_import_impl(ctx):",
        "  return [apple_common.new_objc_provider(",
        "      static_framework_file=depset(ctx.files.link_inputs))]",
        "custom_static_framework_import = rule(",
        "    _custom_static_framework_import_impl,",
        "    attrs={'link_inputs': attr.label_list(allow_files=True)},",
        ")");
    scratch.file("fx/fx1.framework/fx1");
    scratch.file("fx/fx2.framework/fx2");
    scratch.file(
        "fx/BUILD",
        "load(':defs.bzl', 'custom_static_framework_import')",
        "custom_static_framework_import(",
        "    name = 'framework',",
        "    link_inputs = ['fx1.framework/fx1', 'fx2.framework/fx2'],",
        ")");

    ConfiguredTarget framework = getConfiguredTarget("//fx:framework");
    ObjcProvider objc = framework.get(ObjcProvider.STARLARK_CONSTRUCTOR);
    assertThat(Artifact.toRootRelativePaths(objc.staticFrameworkFile()))
        .containsExactly("fx/fx1.framework/fx1", "fx/fx2.framework/fx2");
    assertThat(objc.staticFrameworkNames().toList()).containsExactly("fx1", "fx2");
    assertThat(objc.staticFrameworkPaths().toList()).containsExactly("fx");
  }

  @Test
  public void testDynamicFrameworkApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    scratch.file(
        "fx/defs.bzl",
        "def _custom_dynamic_framework_import_impl(ctx):",
        "  return [apple_common.new_objc_provider(",
        "      dynamic_framework_file=depset(ctx.files.link_inputs))]",
        "custom_dynamic_framework_import = rule(",
        "    _custom_dynamic_framework_import_impl,",
        "    attrs={'link_inputs': attr.label_list(allow_files=True)},",
        ")");
    scratch.file("fx/fx1.framework/fx1");
    scratch.file("fx/fx2.framework/fx2");
    scratch.file(
        "fx/BUILD",
        "load(':defs.bzl', 'custom_dynamic_framework_import')",
        "custom_dynamic_framework_import(",
        "    name = 'framework',",
        "    link_inputs = ['fx1.framework/fx1', 'fx2.framework/fx2'],",
        ")");

    ConfiguredTarget framework = getConfiguredTarget("//fx:framework");
    ObjcProvider objc = framework.get(ObjcProvider.STARLARK_CONSTRUCTOR);
    assertThat(Artifact.toRootRelativePaths(objc.dynamicFrameworkFile()))
        .containsExactly("fx/fx1.framework/fx1", "fx/fx2.framework/fx2");
    assertThat(objc.dynamicFrameworkNames().toList()).containsExactly("fx1", "fx2");
    assertThat(objc.dynamicFrameworkPaths().toList()).containsExactly("fx");
  }

  private void checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.Key<Artifact> key)
      throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   file1 = ctx.actions.declare_file('file1')",
            "   file2 = ctx.actions.declare_file('file2')",
            "   ctx.actions.run_shell(outputs=[file1], command='echo')",
            "   ctx.actions.run_shell(outputs=[file2], command='echo')",
            "   objc = apple_common.new_objc_provider(",
            "      " + key.getStarlarkKeyName() + " = depset([file1, file2]),",
            "   )",
            "   return [objc]");

    ImmutableList<Artifact> artifacts =
        starlarkTarget.get(ObjcProvider.STARLARK_CONSTRUCTOR).get(key).toList();

    assertThat(ActionsTestUtil.baseArtifactNames(artifacts)).containsExactly("file1", "file2");
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithDynamicFrameworkFile() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.DYNAMIC_FRAMEWORK_FILE);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithForceLoadLibrary() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.FORCE_LOAD_LIBRARY);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithImportedLibrary() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.IMPORTED_LIBRARY);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithLibrary() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.LIBRARY);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithLinkInputs() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.LINK_INPUTS);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithStaticFrameworkFile() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithArtifacts(ObjcProvider.STATIC_FRAMEWORK_FILE);
  }

  private void checkLinkingApiCanCreateObjcProviderWithStrings(ObjcProvider.Key<String> key)
      throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   objc = apple_common.new_objc_provider(",
            "      " + key.getStarlarkKeyName() + " = depset(['foo1', 'foo2']),",
            "   )",
            "   return [objc]");

    ImmutableList<String> strings =
        starlarkTarget.get(ObjcProvider.STARLARK_CONSTRUCTOR).get(key).toList();

    assertThat(strings).containsExactly("foo1", "foo2");
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithLinkopt() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithStrings(ObjcProvider.LINKOPT);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithSdkDylib() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithStrings(ObjcProvider.SDK_DYLIB);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithSdkFramework() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithStrings(ObjcProvider.SDK_FRAMEWORK);
  }

  @Test
  public void checkLinkingApiCanCreateObjcProviderWithWeakSdkFramework() throws Exception {
    checkLinkingApiCanCreateObjcProviderWithStrings(ObjcProvider.WEAK_SDK_FRAMEWORK);
  }

  private void checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(
      ObjcProvider.Key<Artifact> key) throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   file1 = ctx.actions.declare_file('file1')",
                    "   file2 = ctx.actions.declare_file('file2')",
                    "   ctx.actions.run_shell(outputs=[file1], command='echo')",
                    "   ctx.actions.run_shell(outputs=[file2], command='echo')",
                    "   objc = apple_common.new_objc_provider(",
                    "      " + key.getStarlarkKeyName() + " = depset([file1, file2]),",
                    "   )",
                    "   return [objc]"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(AppleStarlarkCommon.DEPRECATED_KEY_ERROR, key.getStarlarkKeyName()));
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithDynamicFrameworkFile()
      throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(
        ObjcProvider.DYNAMIC_FRAMEWORK_FILE);
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithForceLoadLibrary() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(ObjcProvider.FORCE_LOAD_LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithImportedLibrary() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(ObjcProvider.IMPORTED_LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithLibrary() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(ObjcProvider.LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithLinkInputs() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(ObjcProvider.LINK_INPUTS);
  }

  @Test
  public void testWithoutLinkingApiCannotCreateObjcProviderWithStaticFrameworkFile()
      throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithArtifacts(ObjcProvider.STATIC_FRAMEWORK_FILE);
  }

  private void checkWithoutLinkingApiCannotCreateObjcProviderWithStrings(
      ObjcProvider.Key<String> key) throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   objc = apple_common.new_objc_provider(",
                    "      " + key.getStarlarkKeyName() + " = depset(['foo1', 'foo2']),",
                    "   )",
                    "   return [objc]"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(AppleStarlarkCommon.DEPRECATED_KEY_ERROR, key.getStarlarkKeyName()));
  }

  @Test
  public void checkWithoutLinkingApiCannotCreateObjcProviderWithLinkopt() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithStrings(ObjcProvider.LINKOPT);
  }

  @Test
  public void checkWithoutLinkingApiCannotCreateObjcProviderWithSdkDylib() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithStrings(ObjcProvider.SDK_DYLIB);
  }

  @Test
  public void checkWithoutLinkingApiCannotCreateObjcProviderWithSdkFramework() throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithStrings(ObjcProvider.SDK_FRAMEWORK);
  }

  @Test
  public void checkWithoutLinkingApiCannotCreateObjcProviderWithWeakSdkFramework()
      throws Exception {
    checkWithoutLinkingApiCannotCreateObjcProviderWithStrings(ObjcProvider.WEAK_SDK_FRAMEWORK);
  }

  @Test
  public void testExecutableBinaryProviderWithObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_executable_binary_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "      objc = apple_common.new_objc_provider(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    ConfiguredTarget target = getConfiguredTarget("//a:bin");
    AppleExecutableBinaryInfo executableBinaryProvider =
        target.get(AppleExecutableBinaryInfo.STARLARK_CONSTRUCTOR);

    assertThat(executableBinaryProvider).isNotNull();
    assertThat(executableBinaryProvider.getDepsCcInfo()).isNotNull();
    assertThat(executableBinaryProvider.getDepsObjcProvider()).isNotNull();
  }

  @Test
  public void testExecutableBinaryProviderWithoutObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_executable_binary_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    ConfiguredTarget target = getConfiguredTarget("//a:bin");
    AppleExecutableBinaryInfo executableBinaryProvider =
        target.get(AppleExecutableBinaryInfo.STARLARK_CONSTRUCTOR);

    assertThat(executableBinaryProvider).isNotNull();
    assertThat(executableBinaryProvider.getDepsCcInfo()).isNotNull();
    assertThat(executableBinaryProvider.getDepsObjcProvider()).isNotNull();
  }

  @Test
  public void testExecutableBinaryProviderDisallowsObjcProviderWithoutObjcLinkingApi()
      throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_executable_binary_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "      objc = apple_common.new_objc_provider(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:bin"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(
                AppleStarlarkCommon.DEPRECATED_OBJC_PROVIDER_ERROR, "AppleExecutableBinaryInfo"));
  }

  @Test
  public void testExecutableBinaryProviderRequiresCcInfoWithoutObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_executable_binary_provider(",
        "      binary = binary,",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:bin"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(AppleStarlarkCommon.REQUIRED_CC_INFO_ERROR, "AppleExecutableBinaryInfo"));
  }

  @Test
  public void testDynamicFrameworkProviderWithObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_dynamic_framework_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "      objc = apple_common.new_objc_provider(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    ConfiguredTarget target = getConfiguredTarget("//a:bin");
    AppleDynamicFrameworkInfo executableBinaryProvider =
        target.get(AppleDynamicFrameworkInfo.STARLARK_CONSTRUCTOR);

    assertThat(executableBinaryProvider).isNotNull();
    assertThat(executableBinaryProvider.getDepsCcInfo()).isNotNull();
    assertThat(executableBinaryProvider.getDepsObjcProvider()).isNotNull();
  }

  @Test
  public void testDynamicFrameworkProviderWithoutObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_dynamic_framework_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    ConfiguredTarget target = getConfiguredTarget("//a:bin");
    AppleDynamicFrameworkInfo executableBinaryProvider =
        target.get(AppleDynamicFrameworkInfo.STARLARK_CONSTRUCTOR);

    assertThat(executableBinaryProvider).isNotNull();
    assertThat(executableBinaryProvider.getDepsCcInfo()).isNotNull();
    assertThat(executableBinaryProvider.getDepsObjcProvider()).isNotNull();
  }

  @Test
  public void testDynamicFrameworkProviderDisallowsObjcProviderWithoutObjcLinkingApi()
      throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_dynamic_framework_provider(",
        "      binary = binary,",
        "      cc_info = CcInfo(),",
        "      objc = apple_common.new_objc_provider(),",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "    implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:bin"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(
                AppleStarlarkCommon.DEPRECATED_OBJC_PROVIDER_ERROR, "AppleDynamicFrameworkInfo"));
  }

  @Test
  public void testDynamicFrameworkProviderRequiresCcInfoWithoutObjcLinkingApi() throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "def binary_impl(ctx):",
        "   binary = ctx.actions.declare_file('bin')",
        "   ctx.actions.run_shell(outputs=[binary], command='echo')",
        "   provider = apple_common.new_dynamic_framework_provider(",
        "      binary = binary,",
        "   )",
        "   return [provider]",
        "binary = rule(",
        "   implementation = binary_impl,",
        ")");
    scratch.file("a/BUILD", "load(':objc_rules.bzl', 'binary')", "binary(", "   name='bin',", ")");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:bin"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(AppleStarlarkCommon.REQUIRED_CC_INFO_ERROR, "AppleDynamicFrameworkInfo"));
  }

  private <E> void checkLinkingApiCanReadKey(ObjcProvider.Key<E> key) throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=false");
    scratch.file(
        "a/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def impl(ctx):",
        "   objc = ctx.attr.dep[apple_common.Objc]",
        "   return MyInfo(",
        "       info = objc." + key.getStarlarkKeyName(),
        "   )",
        "library_info = rule(",
        "   implementation = impl,",
        "   attrs = {'dep': attr.label(providers = [[apple_common.Objc]])}",
        ")");
    scratch.file(
        "a/BUILD",
        "load(':objc_rules.bzl', 'library_info')",
        "objc_library(",
        "   name='lib',",
        "   srcs=['foo.m'],",
        ")",
        "library_info(",
        "   name='info',",
        "   dep=':lib'",
        ")");
    scratch.file("a/foo.m");

    ConfiguredTarget target = getConfiguredTarget("//a:info");
    StructImpl myInfo = getMyInfoFromTarget(target);

    assertThat(myInfo).isNotNull();
    assertThat(myInfo.getValue("info")).isNotNull();
  }

  @Test
  public void testLinkingApiCanReadDynamicFrameworkFile() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.DYNAMIC_FRAMEWORK_FILE);
  }

  @Test
  public void testLinkingApiCanReadForceLoadLibrary() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.FORCE_LOAD_LIBRARY);
  }

  @Test
  public void testLinkingApiCanReadImportedLibrary() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.IMPORTED_LIBRARY);
  }

  @Test
  public void testLinkingApiCanReadLibrary() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.LIBRARY);
  }

  @Test
  public void testLinkingApiCanReadLinkInputs() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.LINK_INPUTS);
  }

  @Test
  public void testLinkingApiCanReadLinkopt() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.LINKOPT);
  }

  @Test
  public void testLinkingApiCanReadSdkDylib() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.SDK_DYLIB);
  }

  @Test
  public void testLinkingApiCanReadSdkFramework() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.SDK_FRAMEWORK);
  }

  @Test
  public void testLinkingApiCanReadStaticFrameworkFile() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.STATIC_FRAMEWORK_FILE);
  }

  @Test
  public void testLinkingApiCanReadWeakSdkFramework() throws Exception {
    checkLinkingApiCanReadKey(ObjcProvider.WEAK_SDK_FRAMEWORK);
  }

  private <E> void checkWithoutLinkingApiCannotReadKey(ObjcProvider.Key<E> key) throws Exception {
    setBuildLanguageOptions("--incompatible_objc_provider_remove_linking_info=true");
    scratch.file(
        "a/objc_rules.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def impl(ctx):",
        "   objc = ctx.attr.dep[apple_common.Objc]",
        "   return MyInfo(",
        "       info = objc." + key.getStarlarkKeyName(),
        "   )",
        "library_info = rule(",
        "   implementation = impl,",
        "   attrs = {'dep': attr.label(providers = [[apple_common.Objc]])}",
        ")");
    scratch.file(
        "a/BUILD",
        "load(':objc_rules.bzl', 'library_info')",
        "objc_library(",
        "   name='lib',",
        "   srcs=['foo.m'],",
        ")",
        "library_info(",
        "   name='info',",
        "   dep=':lib'",
        ")");
    scratch.file("a/foo.m");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:info"));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(
                "ObjcProvider' value has no field or method '%s'", key.getStarlarkKeyName()));
  }

  @Test
  public void testWithoutLinkingApiCannotReadDynamicFrameworkFile() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.DYNAMIC_FRAMEWORK_FILE);
  }

  @Test
  public void testWithoutLinkingApiCannotReadForceLoadLibrary() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.FORCE_LOAD_LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotReadImportedLibrary() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.IMPORTED_LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotReadLibrary() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.LIBRARY);
  }

  @Test
  public void testWithoutLinkingApiCannotReadLinkInputs() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.LINK_INPUTS);
  }

  @Test
  public void testWithoutLinkingApiCannotReadLinkopt() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.LINKOPT);
  }

  @Test
  public void testWithoutLinkingApiCannotReadSdkDylib() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.SDK_DYLIB);
  }

  @Test
  public void testWithoutLinkingApiCannotReadSdkFramework() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.SDK_FRAMEWORK);
  }

  @Test
  public void testWithoutLinkingApiCannotReadStaticFrameworkFile() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.STATIC_FRAMEWORK_FILE);
  }

  @Test
  public void testWithoutLinkingApiCannotReadWeakSdkFramework() throws Exception {
    checkWithoutLinkingApiCannotReadKey(ObjcProvider.WEAK_SDK_FRAMEWORK);
  }

  @Test
  public void testDisallowSDKFrameworkAttribute() throws Exception {
    useConfiguration("--incompatible_disallow_sdk_frameworks_attributes");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "objc_library(",
        "    name = 'lib',",
        "    srcs = ['a.m'],",
        "    sdk_frameworks = ['Accelerate', 'GLKit'],",
        ")");
    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//examples/apple_starlark:lib"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR /workspace/examples/apple_starlark/BUILD:1:13: in sdk_frameworks attribute of"
                + " objc_library rule //examples/apple_starlark:lib: sdk_frameworks attribute is"
                + " disallowed. Use explicit dependencies instead.");
  }

  @Test
  public void testDisallowWeakSDKFrameworksAttribute() throws Exception {
    useConfiguration("--incompatible_disallow_sdk_frameworks_attributes");

    scratch.file(
        "examples/apple_starlark/BUILD",
        "objc_library(",
        "    name = 'lib',",
        "    srcs = ['a.m'],",
        "    weak_sdk_frameworks = ['XCTest'],",
        ")");
    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//examples/apple_starlark:lib"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR /workspace/examples/apple_starlark/BUILD:1:13: in weak_sdk_frameworks attribute"
                + " of objc_library rule //examples/apple_starlark:lib: weak_sdk_frameworks"
                + " attribute is disallowed.  Use explicit dependencies instead.");
  }

  @Test
  public void testGetExperimentalShorterHeaderPathForStarlarkIsPrivateApi() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "def _impl(ctx):",
        "  ctx.fragments.j2objc.experimental_shorter_header_path()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['j2objc']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("file '//foo:rule.bzl' cannot use private API");
  }

  @Test
  public void testGetExperimentalJ2ObjcHeaderMapForStarlarkIsPrivateApi() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "def _impl(ctx):",
        "  ctx.fragments.j2objc.experimental_j2objc_header_map()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['j2objc']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("file '//foo:rule.bzl' cannot use private API");
  }

  @Test
  public void testGetRemoveDeadCodeFromJ2ObjcConfigurationForStarlarkIsPrivateApi()
      throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "def _impl(ctx):",
        "  ctx.fragments.j2objc.remove_dead_code()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['j2objc']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("file '//foo:rule.bzl' cannot use private API");
  }

  @Test
  public void testJ2objcLibraryMigrationForStarlarkIsPrivateApi() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "def _impl(ctx):",
        "  ctx.fragments.j2objc.j2objc_library_migration()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['j2objc']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("file \'//foo:rule.bzl\' cannot use private API");
  }
}
