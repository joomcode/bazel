// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.testutil;

import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.CROSSTOOL_LABEL;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.RepositoryName;

/**
 * Various constants required by the tests.
 */
public class TestConstants {

  public static final String LOAD_PROTO_LIBRARY =
      "load('@rules_proto//proto:defs.bzl', 'proto_library')";
  public static final String PROTO_TOOLCHAIN =  "@rules_proto//proto:toolchain_type";
  public static final String LOAD_PROTO_TOOLCHAIN =
      "load('@rules_proto//proto:proto_toolchain.bzl', 'proto_toolchain')";
  public static final String LOAD_PROTO_LANG_TOOLCHAIN =
      "load('@rules_proto//proto:defs.bzl', 'proto_lang_toolchain')";

  private TestConstants() {
  }

  public static final String PRODUCT_NAME = "bazel";

  /**
   * A list of all embedded binaries that go into the regular Bazel binary.
   */
  public static final ImmutableList<String> EMBEDDED_TOOLS = ImmutableList.of(
      "build-runfiles",
      "linux-sandbox",
      "process-wrapper",
      "xcode-locator");

  /**
   * Location in the bazel repo where embedded binaries come from.
   */
  public static final ImmutableList<String> EMBEDDED_SCRIPTS_PATHS = ImmutableList.of(
      "_main/src/main/tools");

  /**
   * Default workspace name.
   */
  public static final String WORKSPACE_NAME = "_main";

  /**
   * Legacy default workspace name.
   */
  public static final String LEGACY_WORKSPACE_NAME = "__main__";

  /**
   * Name of a class with an INSTANCE field of type AnalysisMock to be used for analysis tests.
   */
  public static final String TEST_ANALYSIS_MOCK =
      "com.google.devtools.build.lib.analysis.mock.BazelAnalysisMock";

  /**
   * Directory where we can find bazel's Java tests, relative to a test's runfiles directory.
   */
  public static final String JAVATESTS_ROOT = "_main/src/test/java/";

  /** Location of the bazel repo relative to the workspace root */
  public static final String BAZEL_REPO_PATH = "";

  /** The file path in which to create files so that they end up under Bazel main repository. */
  public static final String BAZEL_REPO_SCRATCH = "../_main/";

  /** Relative path to the {@code process-wrapper} tool. */
  public static final String PROCESS_WRAPPER_PATH =
      "_main/src/main/tools/process-wrapper";

  /** Relative path to the {@code linux-sandbox} tool. */
  public static final String LINUX_SANDBOX_PATH =
      "_main/src/main/tools/linux-sandbox";

  /** Relative path to the {@code spend_cpu_time} testing tool. */
  public static final String CPU_TIME_SPENDER_PATH =
      "_main/src/test/shell/integration/spend_cpu_time";

  /**
   * Directory where we can find Bazel's own bootstrapping rules relative to a test's runfiles
   * directory, i.e. when //tools/build_rules:srcs is in a test's data.
   */
  public static final String BUILD_RULES_DATA_PATH = "_main/tools/build_rules/";

  public static final String TEST_RULE_CLASS_PROVIDER =
      "com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider";
  public static final String TEST_RULE_MODULE =
      "com.google.devtools.build.lib.bazel.rules.BazelRulesModule";
  public static final String TEST_STRATEGY_MODULE =
      "com.google.devtools.build.lib.bazel.rules.BazelStrategyModule";
  public static final String TEST_REAL_UNIX_FILE_SYSTEM =
      "com.google.devtools.build.lib.unix.UnixFileSystem";
  public static final String TEST_UNIX_HASH_ATTRIBUTE = "";

  public static final ImmutableList<String> IGNORED_MESSAGE_PREFIXES = ImmutableList.<String>of();

  /** The path in which the mock cc crosstool resides. */
  public static final String MOCK_CC_CROSSTOOL_PATH = "tools/cpp";

  /** The path in which the mock license rule resides. */
  public static final String MOCK_LICENSE_SCRATCH = "third_party/rules_license/";

  /** The workspace repository label under which built-in tools reside. */
  public static final RepositoryName TOOLS_REPOSITORY = RepositoryName.BAZEL_TOOLS;
  /** The file path in which to create files so that they end up under {@link #TOOLS_REPOSITORY}. */
  public static final String TOOLS_REPOSITORY_SCRATCH = "embedded_tools/";

  /** The output file path prefix for tool file dependencies. */
  public static final String TOOLS_REPOSITORY_PATH_PREFIX = "external/bazel_tools/";

  public static final String RULES_CC_REPOSITORY_SCRATCH = "rules_cc_workspace/";
  /** The directory in which rules_cc repo resides in execroot. */
  public static final String RULES_CC_REPOSITORY_EXECROOT = "external/" + RulesCcRepoName.CANONICAL_REPO_NAME + "/";

  public static final ImmutableList<String> DOCS_RULES_PATHS = ImmutableList.of(
      "src/main/java/com/google/devtools/build/lib/rules");

  // Constants used to determine how genrule pulls in the setup script.
  public static final String GENRULE_SETUP = "@bazel_tools//tools/genrule:genrule-setup.sh";
  public static final String GENRULE_SETUP_PATH = "genrule-setup.sh";

  /**
   * Flags that must be set for Bazel to work properly, if the default values are unusable for some
   * reason.
   */
  public static final ImmutableList<String> PRODUCT_SPECIFIC_FLAGS =
      ImmutableList.of(
          "--platforms=@bazel_tools//tools:host_platform",
          "--host_platform=@bazel_tools//tools:host_platform",
          // TODO(#7849): Remove after flag flip.
          "--incompatible_use_toolchain_resolution_for_java_rules");

  /** Partial query to filter out implicit dependencies of C/C++ rules. */
  public static final String CC_DEPENDENCY_CORRECTION =
      " - deps(" + TOOLS_REPOSITORY + CROSSTOOL_LABEL + ")"
      + " - deps(" + TOOLS_REPOSITORY + "//tools/cpp:current_cc_toolchain)"
      + " - deps(" + TOOLS_REPOSITORY + "//tools/cpp:grep-includes)";

  public static final String APPLE_PLATFORM_PATH = "build_bazel_apple_support/platforms";
  public static final String APPLE_PLATFORM_PACKAGE_ROOT = "@build_bazel_apple_support//platforms";
  public static final String CONSTRAINTS_PACKAGE_ROOT = "@platforms//";

  public static final String PLATFORMS_PATH = "embedded_tools/platforms";
  public static final String CONSTRAINTS_PATH = "platforms_workspace";

  public static final String PLATFORM_LABEL = "@platforms//host";
  public static final String PLATFORM_LABEL_ALIAS = "@bazel_tools//tools:host_platform";
  public static final String PIII_PLATFORM_LABEL = "@platforms//host:piii";

  /** What toolchain type do Android rules use for platform-based toolchain resolution? */
  public static final String ANDROID_TOOLCHAIN_TYPE_LABEL =
      TOOLS_REPOSITORY + "//tools/android:sdk_toolchain_type";

  /** The launcher used by Bazel. */
  public static final String LAUNCHER_PATH = "@bazel_tools//tools/launcher:launcher";

  /** The target name for ProGuard's allowlister. */
  public static final String PROGUARD_ALLOWLISTER_TARGET =
      "@bazel_tools//tools/jdk:proguard_whitelister";

  /** The java toolchain type. */
  public static final String JAVA_TOOLCHAIN_TYPE = "@@bazel_tools//tools/jdk:toolchain_type";

  /** The cpp toolchain type. */
  public static final String CPP_TOOLCHAIN_TYPE = "@@bazel_tools//tools/cpp:toolchain_type";

  /** Whether blake3 can be used through JNI */
  public static final boolean BLAKE3_AVAILABLE = true;

  /** A choice of test execution mode, only varies internally. */
  public enum InternalTestExecutionMode {
    NORMAL
  }
}
