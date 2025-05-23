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

"""Auxiliary rule to create the deploy archives for java_binary

This needs to be a separate rule because we need to add the runfiles manifest as an input to
the generating actions, so that the runfiles symlink tree is staged for the deploy jars.
"""

load(":common/cc/semantics.bzl", cc_semantics = "semantics")
load(":common/java/java_binary.bzl", "InternalDeployJarInfo")
load(":common/java/java_binary_deploy_jar.bzl", "create_deploy_archives", "make_deploy_jars_rule")
load(":common/java/java_common_internal_for_builtins.bzl", "get_build_info")

def _stamping_enabled(ctx, stamp):
    if ctx.configuration.is_tool_configuration():
        stamp = 0
    return (stamp == 1) or (stamp == -1 and ctx.configuration.stamp_binaries())

def _get_build_info(ctx, stamp):
    return get_build_info(ctx, _stamping_enabled(ctx, stamp))

def _bazel_deploy_jars_impl(ctx):
    info = ctx.attr.binary[InternalDeployJarInfo]

    build_info_files = _get_build_info(ctx, info.stamp)

    create_deploy_archives(
        ctx,
        info.java_attrs,
        info.launcher_info,
        info.main_class,
        info.coverage_main_class,
        info.strip_as_default,
        build_info_files,
        str(ctx.attr.binary.label),
        add_exports = info.add_exports,
        add_opens = info.add_opens,
        manifest_lines = info.manifest_lines,
    )

    return []

_ATTRS = {
    "_cc_toolchain": attr.label(
        default = "@" + cc_semantics.get_repo() + "//tools/cpp:optional_current_cc_toolchain",
    ),
}

deploy_jars = make_deploy_jars_rule(implementation = _bazel_deploy_jars_impl, extra_attrs = _ATTRS)

deploy_jars_nonexec = make_deploy_jars_rule(implementation = _bazel_deploy_jars_impl, extra_attrs = _ATTRS, create_executable = False)
