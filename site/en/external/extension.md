Project: /_project.yaml
Book: /_book.yaml

# Module extensions

{% include "_buttons.html" %}

Module extensions allow users to extend the module system by reading input data
from modules across the dependency graph, performing necessary logic to resolve
dependencies, and finally creating repos by calling repo rules. These extensions
have capabilities similar to repo rules, which enables them to perform file I/O,
send network requests, and so on. Among other things, they allow Bazel to
interact with other package management systems while also respecting the
dependency graph built out of Bazel modules.

You can define module extensions in `.bzl` files, just like repo rules. They're
not invoked directly; rather, each module specifies pieces of data called *tags*
for extensions to read. Bazel runs module resolution before evaluating any
extensions. The extension reads all the tags belonging to it across the entire
dependency graph.

## Extension usage

Extensions are hosted in Bazel modules themselves. To use an extension in a
module, first add a `bazel_dep` on the module hosting the extension, and then
call the [`use_extension`](/rules/lib/globals/module#use_extension) built-in function
to bring it into scope. Consider the following example — a snippet from a
`MODULE.bazel` file to use the "maven" extension defined in the
[`rules_jvm_external`](https://github.com/bazelbuild/rules_jvm_external){:.external}
module:

```python
bazel_dep(name = "rules_jvm_external", version = "4.5")
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
```

This binds the return value of `use_extension` to a variable, which allows the
user to use dot-syntax to specify tags for the extension. The tags must follow
the schema defined by the corresponding *tag classes* specified in the
[extension definition](#extension_definition). For an example specifying some
`maven.install` and `maven.artifact` tags:

```python
maven.install(artifacts = ["org.junit:junit:4.13.2"])
maven.artifact(group = "com.google.guava",
               artifact = "guava",
               version = "27.0-jre",
               exclusions = ["com.google.j2objc:j2objc-annotations"])
```

Use the [`use_repo`](/rules/lib/globals/module#use_repo) directive to bring repos
generated by the extension into the scope of the current module.

```python
use_repo(maven, "maven")
```

Repos generated by an extension are part of its API. In this example, the
"maven" module extension promises to generate a repo called `maven`. With the
declaration above, the extension properly resolves labels such as
`@maven//:org_junit_junit` to point to the repo generated by the "maven"
extension.

Note: Module extensions are evaluated lazily. This means that an extension will
typically not be evaluated unless some module brings one of its repositories
into scope using `use_repo` and that repository is referenced in a build. While
testing a module extension, `bazel mod deps` can be useful as it
unconditionally evaluates all module extensions.

## Extension definition

You can define module extensions similarly to repo rules, using the
[`module_extension`](/rules/lib/globals/bzl#module_extension) function. However,
while repo rules have a number of attributes, module extensions have
[`tag_class`es](/rules/lib/globals/bzl#tag_class), each of which has a number of
attributes. The tag classes define schemas for tags used by this extension. For
example, the "maven" extension above might be defined like this:

```python
# @rules_jvm_external//:extensions.bzl

_install = tag_class(attrs = {"artifacts": attr.string_list(), ...})
_artifact = tag_class(attrs = {"group": attr.string(), "artifact": attr.string(), ...})
maven = module_extension(
  implementation = _maven_impl,
  tag_classes = {"install": _install, "artifact": _artifact},
)
```

These declarations show that `maven.install` and `maven.artifact` tags can be
specified using the specified attribute schema.

The implementation function of module extensions are similar to those of repo
rules, except that they get a [`module_ctx`](/rules/lib/builtins/module_ctx) object,
which grants access to all modules using the extension and all pertinent tags.
The implementation function then calls repo rules to generate repos.

```python
# @rules_jvm_external//:extensions.bzl

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")  # a repo rule
def _maven_impl(ctx):
  # This is a fake implementation for demonstration purposes only

  # collect artifacts from across the dependency graph
  artifacts = []
  for mod in ctx.modules:
    for install in mod.tags.install:
      artifacts += install.artifacts
    artifacts += [_to_artifact(artifact) for artifact in mod.tags.artifact]

  # call out to the coursier CLI tool to resolve dependencies
  output = ctx.execute(["coursier", "resolve", artifacts])
  repo_attrs = _process_coursier_output(output)

  # call repo rules to generate repos
  for attrs in repo_attrs:
    http_file(**attrs)
  _generate_hub_repo(name = "maven", repo_attrs)
```

### Extension identity

Module extensions are identified by the name and the `.bzl` file that appears
in the call to `use_extension`. In the following example, the extension `maven`
is identified by the `.bzl` file `@rules_jvm_external//:extension.bzl` and the
name `maven`:

```python
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
```

Re-exporting an extension from a different `.bzl` file gives it a new identity
and if both versions of the extension are used in the transitive module graph,
then they will be evaluated separately and will only see the tags associated
with that particular identity.

As an extension author you should make sure that users will only use your
module extension from one single `.bzl` file.

## Repository names and visibility

Repos generated by extensions have canonical names in the form of `{{ "<var>"
}}module_repo_canonical_name{{ "</var>" }}~{{ "<var>" }}extension_name{{
"</var>" }}~{{ "<var>" }}repo_name{{ "</var>" }}`. For extensions hosted in the
root module, the `{{ "<var>" }}module_repo_canonical_name{{ "</var>" }}` part is
replaced with the string `_main`. Note that the canonical name format is not an
API you should depend on — it's subject to change at any time.

This naming policy means that each extension has its own "repo namespace"; two
distinct extensions can each define a repo with the same name without risking
any clashes. It also means that `repository_ctx.name` reports the canonical name
of the repo, which is *not* the same as the name specified in the repo rule
call.

Taking repos generated by module extensions into consideration, there are
several repo visibility rules:

*   A Bazel module repo can see all repos introduced in its `MODULE.bazel` file
    via [`bazel_dep`](/rules/lib/globals/module#bazel_dep) and
    [`use_repo`](/rules/lib/globals/module#use_repo).
*   A repo generated by a module extension can see all repos visible to the
    module that hosts the extension, *plus* all other repos generated by the
    same module extension (using the names specified in the repo rule calls as
    their apparent names).
    *   This might result in a conflict. If the module repo can see a repo with
        the apparent name `foo`, and the extension generates a repo with the
        specified name `foo`, then for all repos generated by that extension
        `foo` refers to the former.
*   Similarly, in a module extension's implementation function, repos created
    by the extension can refer to each other by their apparent names in
    attributes, regardless of the order in which they are created.
    *   In case of a conflict with a repository visible to the module, labels
        passed to repository rule attributes can be wrapped in a call to
        [`Label`](/rules/lib/toplevel/attr#label) to ensure that they refer to
        the repo visible to the module instead of the extension-generated repo
        of the same name.

### Overriding and injecting module extension repos

The root module can use
[`override_repo`](/rules/lib/globals/module#override_repo) and
[`inject_repo`](/rules/lib/globals/module#inject_repo) to override or inject
module extension repos.

#### Example: Replacing `rules_java`'s `java_tools` with a vendored copy

```python
# MODULE.bazel
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "my_java_tools",
  path = "vendor/java_tools",
)

bazel_dep(name = "rules_java", version = "7.11.1")
java_toolchains = use_extension("@rules_java//java:extension.bzl", "toolchains")

override_repo(java_toolchains, remote_java_tools = "my_java_tools")
```

#### Example: Patch a Go dependency to depend on `@zlib` instead of the system zlib

```python
# MODULE.bazel
bazel_dep(name = "gazelle", version = "0.38.0")
bazel_dep(name = "zlib", version = "1.3.1.bcr.3")

go_deps = use_extension("@gazelle//:extensions.bzl", "go_deps")
go_deps.from_file(go_mod = "//:go.mod")
go_deps.module_override(
  patches = [
    "//patches:my_module_zlib.patch",
  ],
  path = "example.com/my_module",
)
use_repo(go_deps, ...)

inject_repo(go_deps, "zlib")
```

```diff
# patches/my_module_zlib.patch
--- a/BUILD.bazel
+++ b/BUILD.bazel
@@ -1,6 +1,6 @@
 go_binary(
     name = "my_module",
     importpath = "example.com/my_module",
     srcs = ["my_module.go"],
-    copts = ["-lz"],
+    cdeps = ["@zlib"],
 )
```

## Best practices

This section describes best practices when writing extensions so they are
straightforward to use, maintainable, and adapt well to changes over time.

### Put each extension in a separate file

When extensions are in a different files, it allows one extension to load
repositories generated by another extension. Even if you don't use this
functionality, it's best to put them in separate files in case you need it
later. This is because the extension's identify is based on its file, so moving
the extension into another file later changes your public API and is a backwards
incompatible change for your users.

### Specify reproducibility

If your extension always defines the same repositories given the same inputs
(extension tags, files it reads, etc.) and in particular doesn't rely on
any [downloads](/rules/lib/builtins/module_ctx#download) that aren't guarded by
a checksum, consider returning
[`extension_metadata`](/rules/lib/builtins/module_ctx#extension_metadata) with
`reproducible = True`. This allows Bazel to skip this extension when writing to
the lockfile.

### Specify the operating system and architecture

If your extension relies on the operating system or its architecture type,
ensure to indicate this in the extension definition using the `os_dependent`
and `arch_dependent` boolean attributes. This ensures that Bazel recognizes the
need for re-evaluation if there are changes to either of them.

Since this kind of dependence on the host makes it more difficult to maintain
the lockfile entry for this extension, consider
[marking the extension reproducible](#specify_reproducibility) if possible.

### Only the root module should directly affect repository names

Remember that when an extension creates repositories, they are created within
the namespace of the extension. This means collisions can occur if different
modules use the same extension and end up creating a repository with the same
name. This often manifests as a module extension's `tag_class` having a `name`
argument that is passed as a repository rule's `name` value.

For example, say the root module, `A`, depends on module `B`. Both modules
depend on module `mylang`. If both `A` and `B` call
`mylang.toolchain(name="foo")`, they will both try to create a repository named
`foo` within the `mylang` module and an error will occur.

To avoid this, either remove the ability to set the repository name directly,
or only allow the root module to do so. It's OK to allow the root module this
ability because nothing will depend on it, so it doesn't have to worry about
another module creating a conflicting name.
