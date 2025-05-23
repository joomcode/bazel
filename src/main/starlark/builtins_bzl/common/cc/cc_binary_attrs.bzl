# Copyright 2022 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Attributes for cc_binary.
"""

load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_shared_library.bzl", "dynamic_deps_attrs")
load(":common/cc/semantics.bzl", "semantics")

cc_internal = _builtins.internal.cc_internal

cc_binary_attrs = {
    "srcs": attr.label_list(
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
        allow_files = True,
    ),
    "win_def_file": attr.label(
        allow_single_file = [".def"],
    ),
    "reexport_deps": attr.label_list(
        allow_files = True,
        allow_rules = semantics.ALLOWED_RULES_IN_DEPS,
    ),
    "linkopts": attr.string_list(),
    "copts": attr.string_list(),
    "conlyopts": attr.string_list(),
    "cxxopts": attr.string_list(),
    "defines": attr.string_list(),
    "local_defines": attr.string_list(),
    "includes": attr.string_list(),
    "nocopts": attr.string(),
    # TODO(b/198254254): Only once inside Google? in progress
    # TODO(b/198254254): Add default = cc_internal.default_hdrs_check_computed_default().
    "hdrs_check": attr.string(),
    "linkstatic": attr.bool(
        default = True,
    ),
    "additional_linker_inputs": attr.label_list(
        allow_files = True,
        flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
    ),
    "deps": attr.label_list(
        allow_files = semantics.ALLOWED_FILES_IN_DEPS,
        allow_rules = semantics.ALLOWED_RULES_IN_DEPS + semantics.ALLOWED_RULES_WITH_WARNINGS_IN_DEPS,
        flags = ["SKIP_ANALYSIS_TIME_FILETYPE_CHECK"],
        providers = [CcInfo],
    ),
    "malloc": attr.label(
        default = Label("@" + semantics.get_repo() + "//tools/cpp:malloc"),
        allow_files = False,
        providers = [CcInfo],
        allow_rules = ["cc_library"],
    ),
    "_default_malloc": attr.label(
        default = configuration_field(fragment = "cpp", name = "custom_malloc"),
    ),
    "link_extra_lib": attr.label(
        default = Label("@" + semantics.get_repo() + "//tools/cpp:link_extra_lib"),
        providers = [CcInfo],
    ),
    "stamp": attr.int(
        values = [-1, 0, 1],
        default = -1,
    ),
    "linkshared": attr.bool(
        default = False,
    ),
    "data": attr.label_list(
        allow_files = True,
        flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
    ),
    "env": attr.string_dict(),
    "distribs": attr.string_list(),
    "licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
    "_cc_binary": attr.bool(),
    "_is_test": attr.bool(default = False),
    "_stl": semantics.get_stl(),
    "_cc_toolchain": attr.label(default = "@" + semantics.get_repo() + "//tools/cpp:current_cc_toolchain"),
    "_cc_toolchain_type": attr.label(default = "@" + semantics.get_repo() + "//tools/cpp:toolchain_type"),
    "_def_parser": semantics.get_def_parser(),
    "_use_auto_exec_groups": attr.bool(default = True),
}

cc_binary_attrs.update(dynamic_deps_attrs)
cc_binary_attrs.update(semantics.get_distribs_attr())
