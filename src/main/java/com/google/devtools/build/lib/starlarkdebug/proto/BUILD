load("@rules_java//java:defs.bzl", "java_proto_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")
load("@rules_proto//proto:defs.bzl", "proto_library")

package(default_visibility = ["//src:__subpackages__"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/com/google/devtools/build/lib:__pkg__"],
)

java_library_srcs(
    name = "dist_jars",
    deps = [":starlark_debugging_java_proto"],
)

java_proto_library(
    name = "starlark_debugging_java_proto",
    # The Bazel IntelliJ plugin uses this.
    # Other clients must not assume protocol stability.
    visibility = ["//visibility:public"],
    deps = ["starlark_debugging_proto"],
)

proto_library(
    name = "starlark_debugging_proto",
    srcs = ["starlark_debugging.proto"],
    visibility = ["//visibility:public"],
)
