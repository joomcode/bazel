# Copyright 2023 The Bazel Authors. All rights reserved.
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

""" Utilities for Java compilation support in Starlark. """

load(":common/java/boot_class_path_info.bzl", "BootClassPathInfo")
load(":common/java/java_common_internal_for_builtins.bzl", "compile", "run_ijar")
load(":common/java/java_helper.bzl", "helper")
load(
    ":common/java/java_info.bzl",
    "JavaCompilationInfo",
    "JavaInfo",
    "JavaPluginDataInfo",
    "JavaPluginInfo",
    "to_java_binary_info",
    _java_info_add_constraints = "add_constraints",
    _java_info_make_non_strict = "make_non_strict",
    _java_info_merge = "merge",
    _java_info_set_annotation_processing = "set_annotation_processing",
)
load(":common/java/java_runtime.bzl", "JavaRuntimeInfo")
load(":common/java/java_semantics.bzl", "semantics")
load(":common/java/java_toolchain.bzl", "JavaToolchainInfo")
load(":common/java/message_bundle_info.bzl", "MessageBundleInfo")
load(":common/paths.bzl", "paths")

_java_common_internal = _builtins.internal.java_common_internal_do_not_use
JavaRuntimeClasspathInfo = provider(
    "Provider for the runtime classpath contributions of a Java binary.",
    fields = ["runtime_classpath"],
)

def _compile(
        ctx,
        output,
        java_toolchain,
        source_jars = [],
        source_files = [],
        output_source_jar = None,
        javac_opts = [],
        deps = [],
        runtime_deps = [],
        exports = [],
        plugins = [],
        exported_plugins = [],
        native_libraries = [],
        annotation_processor_additional_inputs = [],
        annotation_processor_additional_outputs = [],
        strict_deps = "ERROR",
        bootclasspath = None,
        sourcepath = [],
        resources = [],
        neverlink = False,
        enable_annotation_processing = True,
        add_exports = [],
        add_opens = []):
    return compile(
        ctx,
        output,
        java_toolchain,
        source_jars = source_jars,
        source_files = source_files,
        output_source_jar = output_source_jar,
        javac_opts = javac_opts,
        deps = deps,
        runtime_deps = runtime_deps,
        exports = exports,
        plugins = plugins,
        exported_plugins = exported_plugins,
        native_libraries = native_libraries,
        annotation_processor_additional_inputs = annotation_processor_additional_inputs,
        annotation_processor_additional_outputs = annotation_processor_additional_outputs,
        strict_deps = strict_deps,
        bootclasspath = bootclasspath,
        sourcepath = sourcepath,
        resources = resources,
        neverlink = neverlink,
        enable_annotation_processing = enable_annotation_processing,
        add_exports = add_exports,
        add_opens = add_opens,
    )

def _run_ijar(actions, jar, java_toolchain, target_label = None):
    _java_common_internal._check_java_toolchain_is_declared_on_rule(actions)
    return run_ijar(
        actions = actions,
        jar = jar,
        java_toolchain = java_toolchain,
        target_label = target_label,
    )

def _stamp_jar(actions, jar, java_toolchain, target_label):
    """Stamps a jar with a target label for <code>add_dep</code> support.

    The return value is typically passed to `JavaInfo.compile_jar`. Prefer to use `run_ijar` when
    possible.

    Args:
        actions: (actions) ctx.actions
        jar: (File) The jar to run stamp_jar on.
        java_toolchain: (JavaToolchainInfo) The toolchain to used to find the stamp_jar tool.
        target_label: (Label) A target label to stamp the jar with. Used for `add_dep` support.
            Typically, you would pass `ctx.label` to stamp the jar with the current rule's label.

    Returns:
        (File) The output artifact

    """
    _java_common_internal._check_java_toolchain_is_declared_on_rule(actions)
    output = actions.declare_file(paths.replace_extension(jar.basename, "-stamped.jar"), sibling = jar)
    args = actions.args()
    args.add(jar)
    args.add(output)
    args.add("--nostrip_jar")
    args.add("--target_label", target_label)
    actions.run(
        mnemonic = "JavaIjar",
        inputs = [jar],
        outputs = [output],
        executable = java_toolchain.ijar,  # ijar doubles as a stamping tool
        arguments = [args],
        progress_message = "Stamping target label into jar %{input}",
        toolchain = semantics.JAVA_TOOLCHAIN_TYPE,
        use_default_shell_env = True,
    )
    return output

def _pack_sources(
        actions,
        java_toolchain,
        output_source_jar,
        sources = [],
        source_jars = []):
    """Packs sources and source jars into a single source jar file.

    The return value is typically passed to `JavaInfo.source_jar`.

    Args:
        actions: (actions) ctx.actions
        java_toolchain: (JavaToolchainInfo) The toolchain used to find the ijar tool.
        output_source_jar: (File) The output source jar.
        sources: ([File]) A list of Java source files to be packed into the source jar.
        source_jars: ([File]) A list of source jars to be packed into the source jar.

    Returns:
        (File) The output artifact
    """
    _java_common_internal._check_java_toolchain_is_declared_on_rule(actions)
    return helper.create_single_jar(
        actions,
        toolchain = java_toolchain,
        output = output_source_jar,
        sources = depset(source_jars),
        resources = depset(sources),
        progress_message = "Building source jar %{output}",
        mnemonic = "JavaSourceJar",
    )

def _default_javac_opts(java_toolchain):
    """Experimental! Get default javacopts from a java toolchain

    Args:
        java_toolchain: (JavaToolchainInfo) the toolchain from which to get the javac options.

    Returns:
        ([str]) A list of javac options
    """
    return _java_common_internal.default_javac_opts(java_toolchain = java_toolchain)

# temporary for migration
def _default_javac_opts_depset(java_toolchain):
    """Experimental! Get default javacopts from a java toolchain

    Args:
        java_toolchain: (JavaToolchainInfo) the toolchain from which to get the javac options.

    Returns:
        (depset[str]) A depset of javac options that should be tokenized before passing to javac
    """
    return _java_common_internal.default_javac_opts(
        java_toolchain = java_toolchain,
        as_depset = True,
    )

def _merge(providers):
    """Merges the given providers into a single JavaInfo.

    Args:
        providers: ([JavaInfo]) The list of providers to merge.

    Returns:
        (JavaInfo) The merged JavaInfo
    """
    return _java_info_merge(providers)

def _make_non_strict(java_info):
    """Returns a new JavaInfo instance whose direct-jars part is the union of both the direct and indirect jars of the given Java provider.

    Args:
        java_info: (JavaInfo) The java info to make non-strict.

    Returns:
        (JavaInfo)
    """
    return _java_info_make_non_strict(java_info)

def _get_message_bundle_info():
    return None if semantics.IS_BAZEL else MessageBundleInfo

def _add_constraints(java_info, constraints = []):
    """Returns a copy of the given JavaInfo with the given constraints added.

    Args:
        java_info: (JavaInfo) The JavaInfo to enhance
        constraints: ([str]) Constraints to add

    Returns:
        (JavaInfo)
    """
    if semantics.IS_BAZEL:
        return java_info

    return _java_info_add_constraints(java_info, constraints = constraints)

def _get_constraints(java_info):
    """Returns a set of constraints added.

    Args:
        java_info: (JavaInfo) The JavaInfo to get constraints from.

    Returns:
        ([str]) The constraints set on the supplied JavaInfo
    """
    return [] if semantics.IS_BAZEL else java_info._constraints

def _set_annotation_processing(
        java_info,
        enabled = False,
        processor_classnames = [],
        processor_classpath = None,
        class_jar = None,
        source_jar = None):
    """Returns a copy of the given JavaInfo with the given annotation_processing info.

    Args:
        java_info: (JavaInfo) The JavaInfo to enhance.
        enabled: (bool) Whether the rule uses annotation processing.
        processor_classnames: ([str]) Class names of annotation processors applied.
        processor_classpath: (depset[File]) Class names of annotation processors applied.
        class_jar: (File) Optional. Jar that is the result of annotation processing.
        source_jar: (File) Optional. Source archive resulting from annotation processing.

    Returns:
        (JavaInfo)
    """
    if semantics.IS_BAZEL:
        return None

    return _java_info_set_annotation_processing(
        java_info,
        enabled = enabled,
        processor_classnames = processor_classnames,
        processor_classpath = processor_classpath,
        class_jar = class_jar,
        source_jar = source_jar,
    )

def _java_toolchain_label(java_toolchain):
    """Returns the toolchain's label.

    Args:
        java_toolchain: (JavaToolchainInfo) The toolchain.
    Returns:
        (Label)
    """
    if semantics.IS_BAZEL:
        # No implementation in Bazel. This method is not callable in Starlark except through
        # (discouraged) use of --experimental_google_legacy_api.
        return None

    _java_common_internal.check_provider_instances([java_toolchain], "java_toolchain", JavaToolchainInfo)
    return java_toolchain.label

def _internal_exports():
    _builtins.internal.cc_common.check_private_api(allowlist = [
        ("", "third_party/bazel_rules/rules_java"),
        ("rules_java", ""),
    ])
    return struct(
        incompatible_disable_non_executable_java_binary = _java_common_internal.incompatible_disable_non_executable_java_binary,
        target_kind = _java_common_internal.target_kind,
        compile = compile,
        JavaCompilationInfo = JavaCompilationInfo,
        collect_native_deps_dirs = _java_common_internal.collect_native_deps_dirs,
        get_runtime_classpath_for_archive = _java_common_internal.get_runtime_classpath_for_archive,
        to_java_binary_info = to_java_binary_info,
        run_ijar_private_for_builtins = run_ijar,
        expand_java_opts = _java_common_internal.expand_java_opts,
        JavaPluginDataInfo = JavaPluginDataInfo,
        google_legacy_api_enabled = _java_common_internal._google_legacy_api_enabled,
        wrap_java_info = _java_common_internal.wrap_java_info,
        check_provider_instances = _java_common_internal.check_provider_instances,
        incompatible_java_info_merge_runtime_module_flags = _java_common_internal._incompatible_java_info_merge_runtime_module_flags,
        check_java_toolchain_is_declared_on_rule = _java_common_internal._check_java_toolchain_is_declared_on_rule,
        create_header_compilation_action = _java_common_internal.create_header_compilation_action,
        create_compilation_action = _java_common_internal.create_compilation_action,
        tokenize_javacopts = _java_common_internal.tokenize_javacopts,
    )

def _make_java_common():
    methods = {
        "provider": JavaInfo,
        "compile": _compile,
        "run_ijar": _run_ijar,
        "stamp_jar": _stamp_jar,
        "pack_sources": _pack_sources,
        "default_javac_opts": _default_javac_opts,
        "default_javac_opts_depset": _default_javac_opts_depset,
        "merge": _merge,
        "make_non_strict": _make_non_strict,
        "JavaPluginInfo": JavaPluginInfo,
        "JavaToolchainInfo": JavaToolchainInfo,
        "JavaRuntimeInfo": JavaRuntimeInfo,
        "BootClassPathInfo": BootClassPathInfo,
        "JavaRuntimeClasspathInfo": JavaRuntimeClasspathInfo,
        "internal_DO_NOT_USE": _internal_exports,
    }
    if _java_common_internal._google_legacy_api_enabled():
        methods.update(
            MessageBundleInfo = _get_message_bundle_info(),  # struct field that is None in bazel
            add_constraints = _add_constraints,
            get_constraints = _get_constraints,
            set_annotation_processing = _set_annotation_processing,
            java_toolchain_label = _java_toolchain_label,
        )
    return struct(**methods)

java_common = _make_java_common()
