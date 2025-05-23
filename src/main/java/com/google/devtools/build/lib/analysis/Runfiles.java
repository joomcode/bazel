// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.actions.ActionKeyContext.describeNestedSetFingerprint;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.RunfilesApi;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * An object that encapsulates runfiles. Conceptually, the runfiles are a map of paths to files,
 * forming a symlink tree.
 *
 * <p>In order to reduce memory consumption, this map is not explicitly stored here, but instead as
 * a combination of three parts: artifacts placed at their output-dir-relative paths, source tree
 * symlinks and root symlinks (outside of the source tree).
 */
@Immutable
public final class Runfiles implements RunfilesApi {

  private static class DummyEmptyFilesSupplier implements EmptyFilesSupplier {
    private static final UUID GUID = UUID.fromString("36437db7-820b-4386-85b4-f7205a2018ae");

    private DummyEmptyFilesSupplier() {}

    @Override
    public Iterable<PathFragment> getExtraPaths(Set<PathFragment> manifestPaths) {
      return ImmutableList.of();
    }

    @Override
    public void fingerprint(Fingerprint fp) {
      fp.addUUID(GUID);
    }
  }

  @SerializationConstant @VisibleForSerialization
  static final EmptyFilesSupplier DUMMY_EMPTY_FILES_SUPPLIER = new DummyEmptyFilesSupplier();

  // It is important to declare this *after* the DUMMY_SYMLINK_EXPANDER to avoid NPEs
  public static final Runfiles EMPTY = new Builder().build();

  private static final CommandLineItem.ExceptionlessMapFn<SymlinkEntry> SYMLINK_ENTRY_MAP_FN =
      (symlink, args) -> {
        args.accept(symlink.getPathString());
        args.accept(symlink.getArtifact().getExecPathString());
      };

  private static final CommandLineItem.ExceptionlessMapFn<Artifact>
      RUNFILES_AND_ABSOLUTE_PATH_MAP_FN =
          (artifact, args) -> {
            args.accept(artifact.getRunfilesPathString());
            args.accept(artifact.getPath().getPathString());
          };

  private static final CommandLineItem.ExceptionlessMapFn<Artifact> RUNFILES_AND_EXEC_PATH_MAP_FN =
      (artifact, args) -> {
        args.accept(artifact.getRunfilesPathString());
        args.accept(artifact.getExecPathString());
      };

  /**
   * The directory to put all runfiles under.
   *
   * <p>Using "foo" will put runfiles under &lt;target&gt;.runfiles/foo.
   *
   * <p>This is either set to the workspace name, or is empty.
   */
  private final PathFragment suffix;

  /**
   * The artifacts that should be present in the runfiles directory.
   *
   * <p>This collection may not include any middlemen. These artifacts will be placed at a location
   * that corresponds to the output-dir-relative path of each artifact. It's possible for several
   * artifacts to have the same output-dir-relative path, in which case the last one will win.
   */
  private final NestedSet<Artifact> artifacts;

  /**
   * A map of symlinks that should be present in the runfiles directory. In general, the symlink can
   * be determined from the artifact by using the output-dir-relative path, so this should only be
   * used for cases where that isn't possible.
   *
   * <p>This may include runfiles symlinks from the root of the runfiles tree.
   */
  private final NestedSet<SymlinkEntry> symlinks;

  /**
   * A map of symlinks that should be present above the runfiles directory. These are useful for
   * certain rule types like AppEngine apps which have root level config files outside of the
   * regular source tree.
   */
  private final NestedSet<SymlinkEntry> rootSymlinks;

  /**
   * A set of middlemen artifacts. {@link RuleConfiguredTargetBuilder} adds these to the {@link
   * FilesToRunProvider} of binaries that include this runfiles tree in their runfiles.
   */
  private final NestedSet<Artifact> extraMiddlemen;

  /**
   * Interface used for adding empty files to the runfiles at the last minute. Mainly to support
   * python-related rules adding __init__.py files.
   */
  public interface EmptyFilesSupplier {
    /** Calculate additional empty files to add based on the existing manifest paths. */
    Iterable<PathFragment> getExtraPaths(Set<PathFragment> manifestPaths);

    void fingerprint(Fingerprint fingerprint);
  }

  /** Generates extra (empty file) inputs. */
  private final EmptyFilesSupplier emptyFilesSupplier;

  /**
   * Behavior upon finding a conflict between two runfile entries. A conflict means that two
   * different artifacts have the same runfiles path specified.  For example, adding artifact
   * "a.foo" at path "bar" when there is already an artifact "b.foo" at path "bar".  The policies
   * are ordered from least strict to most strict.
   *
   * <p>Note that conflicts are found relatively late, when the manifest file is created, not when
   * the symlinks are added to runfiles.
   *
   * <p>If no EventHandler is available, all values are treated as IGNORE.
   */
  public enum ConflictPolicy {
    IGNORE,
    WARN,
    ERROR,
  }

  /** Policy for this Runfiles tree */
  private ConflictPolicy conflictPolicy;

  /**
   * If external runfiles should be created under .runfiles/wsname/external/repo as well as
   * .runfiles/repo.
   */
  private final boolean legacyExternalRunfiles;

  private Runfiles(
      PathFragment suffix,
      NestedSet<Artifact> artifacts,
      NestedSet<SymlinkEntry> symlinks,
      NestedSet<SymlinkEntry> rootSymlinks,
      NestedSet<Artifact> extraMiddlemen,
      EmptyFilesSupplier emptyFilesSupplier,
      ConflictPolicy conflictPolicy,
      boolean legacyExternalRunfiles) {
    this.suffix = suffix;
    this.artifacts = Preconditions.checkNotNull(artifacts);
    this.symlinks = Preconditions.checkNotNull(symlinks);
    this.rootSymlinks = Preconditions.checkNotNull(rootSymlinks);
    this.extraMiddlemen = Preconditions.checkNotNull(extraMiddlemen);
    this.emptyFilesSupplier = Preconditions.checkNotNull(emptyFilesSupplier);
    this.conflictPolicy = conflictPolicy;
    this.legacyExternalRunfiles = legacyExternalRunfiles;
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  /**
   * Returns the runfiles' suffix.
   */
  public PathFragment getSuffix() {
    return suffix;
  }

  public NestedSet<Artifact> getExtraMiddlemen() {
    return extraMiddlemen;
  }

  /** Returns the collection of runfiles as artifacts. */
  @Override
  public Depset /*<Artifact>*/ getArtifactsForStarlark() {
    return Depset.of(Artifact.class, artifacts);
  }

  public NestedSet<Artifact> getArtifacts() {
    return artifacts;
  }

  /** Returns the symlinks. */
  @Override
  public Depset /*<SymlinkEntry>*/ getSymlinksForStarlark() {
    return Depset.of(SymlinkEntry.class, symlinks);
  }

  public NestedSet<SymlinkEntry> getSymlinks() {
    return symlinks;
  }

  @Override
  public Depset /*<String>*/ getEmptyFilenamesForStarlark() {
    return Depset.of(
        String.class,
        NestedSetBuilder.wrap(
            Order.STABLE_ORDER,
            Iterables.transform(getEmptyFilenames(), PathFragment::getPathString)));
  }

  public Iterable<PathFragment> getEmptyFilenames() {
    if (emptyFilesSupplier == DUMMY_EMPTY_FILES_SUPPLIER) {
      return ImmutableList.of();
    }
    Set<PathFragment> manifestKeys =
        Streams.concat(
                symlinks.toList().stream().map(SymlinkEntry::getPath),
                artifacts.toList().stream()
                    .map(
                        artifact ->
                            legacyExternalRunfiles
                                ? artifact.getOutputDirRelativePath(false)
                                : artifact.getRunfilesPath()))
            .collect(ImmutableSet.toImmutableSet());
    return emptyFilesSupplier.getExtraPaths(manifestKeys);
  }

  /**
   * Returns the symlinks as a map from path fragment to artifact.
   *
   * @param checker If not null, check for conflicts using this checker.
   */
  Map<PathFragment, Artifact> getSymlinksAsMap(@Nullable ConflictChecker checker) {
    return entriesToMap(symlinks, checker);
  }

  /**
   * @param eventHandler Used for throwing an error if we have an obscuring runlink.
   *                 May be null, in which case obscuring symlinks are silently discarded.
   * @param location Location for reporter. Ignored if reporter is null.
   * @param workingManifest Manifest to be checked for obscuring symlinks.
   * @return map of source file names mapped to their location on disk.
   */
  @VisibleForTesting
  static Map<PathFragment, Artifact> filterListForObscuringSymlinks(
      EventHandler eventHandler, Location location, Map<PathFragment, Artifact> workingManifest) {
    Map<PathFragment, Artifact> newManifest =
        Maps.newHashMapWithExpectedSize(workingManifest.size());
    Set<PathFragment> noFurtherObstructions = new HashSet<>();

    outer:
    for (Map.Entry<PathFragment, Artifact> entry : workingManifest.entrySet()) {
      PathFragment source = entry.getKey();
      Artifact symlink = entry.getValue();
      // drop nested entries; warn if this changes anything
      int n = source.segmentCount();
      ArrayList<PathFragment> parents = new ArrayList<>(n);
      for (int j = 1; j < n; ++j) {
        PathFragment prefix = source.subFragment(0, n - j);
        if (noFurtherObstructions.contains(prefix)) {
          break;
        }
        parents.add(prefix);
        Artifact ancestor = workingManifest.get(prefix);
        if (ancestor != null) {
          // This is an obscuring symlink, so just drop it and move on if there's no reporter.
          if (eventHandler == null) {
            continue outer;
          }
          PathFragment suffix = source.subFragment(n - j, n);
          PathFragment viaAncestor = ancestor.getExecPath().getRelative(suffix);
          PathFragment expected = symlink.getExecPath();
          if (!viaAncestor.equals(expected)) {
            eventHandler.handle(
                Event.warn(
                    location,
                    "runfiles symlink "
                        + source
                        + " -> "
                        + expected
                        + " obscured by "
                        + prefix
                        + " -> "
                        + ancestor.getExecPath()));
          }
          continue outer;
        }
      }
      noFurtherObstructions.addAll(parents);
      newManifest.put(entry.getKey(), entry.getValue());
    }
    return newManifest;
  }

  /**
   * Returns the symlinks as a map from PathFragment to Artifact.
   *
   * @param eventHandler Used for throwing an error if we have an obscuring runlink within the
   *     normal source tree entries, or runfile conflicts. May be null, in which case obscuring
   *     symlinks are silently discarded, and conflicts are overwritten.
   * @param location Location for eventHandler warnings. Ignored if eventHandler is null.
   * @param repoMappingManifest repository mapping manifest to add as a root symlink. This manifest
   *     has to be added automatically for every executable and is thus not part of the Runfiles
   *     advertised by a configured target.
   * @return Map<PathFragment, Artifact> path fragment to artifact, of normal source tree entries
   *     and elements that live outside the source tree. Null values represent empty input files.
   */
  public Map<PathFragment, Artifact> getRunfilesInputs(
      EventHandler eventHandler, Location location, @Nullable Artifact repoMappingManifest) {
    ConflictChecker checker = new ConflictChecker(conflictPolicy, eventHandler, location);
    Map<PathFragment, Artifact> manifest = getSymlinksAsMap(checker);
    // Add artifacts (committed to inclusion on construction of runfiles).
    for (Artifact artifact : artifacts.toList()) {
      checker.put(manifest, artifact.getRunfilesPath(), artifact);
    }

    manifest = filterListForObscuringSymlinks(eventHandler, location, manifest);

    // TODO(bazel-team): Create /dev/null-like Artifact to avoid nulls?
    for (PathFragment extraPath : emptyFilesSupplier.getExtraPaths(manifest.keySet())) {
      checker.put(manifest, extraPath, null);
    }

    // Copy manifest map to another manifest map, prepending the workspace name to every path.
    // E.g. for workspace "myworkspace", the runfile entry "mylib.so"->"/path/to/mylib.so" becomes
    // "myworkspace/mylib.so"->"/path/to/mylib.so".
    ManifestBuilder builder = new ManifestBuilder(suffix, legacyExternalRunfiles);
    builder.addUnderWorkspace(manifest, checker);

    // Finally add symlinks relative to the root of the runfiles tree, on top of everything else.
    // This operation is always checked for conflicts, to match historical behavior.
    if (conflictPolicy == ConflictPolicy.IGNORE) {
      checker = new ConflictChecker(ConflictPolicy.WARN, eventHandler, location);
    }
    builder.add(getRootSymlinksAsMap(checker), checker);
    if (repoMappingManifest != null) {
      checker.put(builder.manifest, PathFragment.create("_repo_mapping"), repoMappingManifest);
    }
    return builder.build();
  }

  public boolean isLegacyExternalRunfiles() {
    return legacyExternalRunfiles;
  }

  /**
   * Helper class to handle munging the paths of external artifacts.
   */
  @VisibleForTesting
  static final class ManifestBuilder {
    // Manifest of paths to artifacts. Path fragments are relative to the .runfiles directory.
    private final Map<PathFragment, Artifact> manifest;
    private final PathFragment workspaceName;
    private final boolean legacyExternalRunfiles;
    // Whether we saw the local workspace name in the runfiles. If legacyExternalRunfiles is true,
    // then this is true, as anything under external/ will also have a runfile under the local
    // workspace.
    private boolean sawWorkspaceName;

    ManifestBuilder(PathFragment workspaceName, boolean legacyExternalRunfiles) {
      this.manifest = new HashMap<>();
      this.workspaceName = workspaceName;
      this.legacyExternalRunfiles = legacyExternalRunfiles;
      this.sawWorkspaceName = legacyExternalRunfiles;
    }

    /** Adds a map under the workspaceName. */
    void addUnderWorkspace(Map<PathFragment, Artifact> inputManifest, ConflictChecker checker) {
      for (Map.Entry<PathFragment, Artifact> entry : inputManifest.entrySet()) {
        PathFragment path = entry.getKey();
        if (isUnderWorkspace(path)) {
          sawWorkspaceName = true;
          checker.put(manifest, workspaceName.getRelative(path), entry.getValue());
        } else {
          if (legacyExternalRunfiles) {
            checker.put(manifest, getLegacyExternalPath(path), entry.getValue());
          }
          // Always add the non-legacy .runfiles/repo/whatever path.
          checker.put(manifest, getExternalPath(path), entry.getValue());
        }
      }
    }

    /**
     * Adds a map to the root directory.
     */
    public void add(Map<PathFragment, Artifact> inputManifest, ConflictChecker checker) {
      for (Map.Entry<PathFragment, Artifact> entry : inputManifest.entrySet()) {
        checker.put(manifest, checkForWorkspace(entry.getKey()), entry.getValue());
      }
    }

    /**
     * Returns the manifest, adding the workspaceName directory if it is not already present.
     */
    public Map<PathFragment, Artifact> build() {
      if (!sawWorkspaceName) {
        // If we haven't seen it and we have seen other files, add the workspace name directory.
        // It might not be there if all of the runfiles are from other repos (and then running from
        // x.runfiles/ws will fail, because ws won't exist). We can't tell Runfiles to create a
        // directory, so instead this creates a hidden file inside the desired directory.
        manifest.put(workspaceName.getRelative(".runfile"), null);
      }
      return manifest;
    }

    private PathFragment getExternalPath(PathFragment path) {
      return checkForWorkspace(path.subFragment(1));
    }

    private PathFragment getLegacyExternalPath(PathFragment path) {
      return workspaceName
          .getRelative(LabelConstants.EXTERNAL_PATH_PREFIX)
          .getRelative(checkForWorkspace(path.subFragment(1)));
    }

    private PathFragment checkForWorkspace(PathFragment path) {
      sawWorkspaceName = sawWorkspaceName
          || path.getSegment(0).equals(workspaceName.getPathString());
      return path;
    }

    private static boolean isUnderWorkspace(PathFragment path) {
      return !path.startsWith(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX);
    }
  }

  /** Returns the root symlinks. */
  @Override
  public Depset /*<SymlinkEntry>*/ getRootSymlinksForStarlark() {
    return Depset.of(SymlinkEntry.class, rootSymlinks);
  }

  public NestedSet<SymlinkEntry> getRootSymlinks() {
    return rootSymlinks;
  }

  /**
   * Returns the root symlinks as a map from path fragment to artifact.
   *
   * @param checker If not null, check for conflicts using this checker.
   */
  public Map<PathFragment, Artifact> getRootSymlinksAsMap(@Nullable ConflictChecker checker) {
    return entriesToMap(rootSymlinks, checker);
  }

  /**
   * Returns the unified map of path fragments to artifacts, taking both artifacts and symlinks into
   * account.
   */
  public Map<PathFragment, Artifact> asMapWithoutRootSymlinks() {
    Map<PathFragment, Artifact> result = entriesToMap(symlinks, null);
    // If multiple artifacts have the same output-dir-relative path, the last one in the list will
    // win. That is because the runfiles tree cannot contain the same artifact for different
    // configurations, because it only uses output-dir-relative paths.
    for (Artifact artifact : artifacts.toList()) {
      result.put(artifact.getOutputDirRelativePath(true), artifact);
    }
    return result;
  }

  /**
   * Returns the manifest expander specified for this runfiles tree.
   */
  private EmptyFilesSupplier getEmptyFilesProvider() {
    return emptyFilesSupplier;
  }

  /**
   * Returns the unified map of path fragments to artifacts, taking into account artifacts and
   * symlinks. The returned set is guaranteed to be a (not necessarily strict) superset of the
   * actual runfiles tree created at execution time.
   */
  public NestedSet<Artifact> getAllArtifacts() {
    if (isEmpty()) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    NestedSetBuilder<Artifact> allArtifacts = NestedSetBuilder.stableOrder();
    allArtifacts
        .addTransitive(artifacts)
        .addAll(Iterables.transform(symlinks.toList(), SymlinkEntry::getArtifact))
        .addAll(Iterables.transform(rootSymlinks.toList(), SymlinkEntry::getArtifact));
    return allArtifacts.build();
  }

  /**
   * Returns if there are no runfiles.
   */
  public boolean isEmpty() {
    return artifacts.isEmpty()
        && symlinks.isEmpty()
        && rootSymlinks.isEmpty()
        && extraMiddlemen.isEmpty();
  }

  /**
   * Flatten a sequence of entries into a single map.
   *
   * @param entrySet Sequence of entries to add.
   * @param checker If not null, check for conflicts with this checker, otherwise silently allow
   *     entries to overwrite previous entries.
   * @return Map<PathFragment, Artifact> Map of runfile entries.
   */
  private static Map<PathFragment, Artifact> entriesToMap(
      NestedSet<SymlinkEntry> entrySet, @Nullable ConflictChecker checker) {
    checker = (checker != null) ? checker : ConflictChecker.IGNORE_CHECKER;
    Map<PathFragment, Artifact> map = new LinkedHashMap<>();
    for (SymlinkEntry entry : entrySet.toList()) {
      checker.put(map, entry.getPath(), entry.getArtifact());
    }
    return map;
  }

  /** Returns currently policy for conflicting symlink entries. */
  ConflictPolicy getConflictPolicy() {
    return this.conflictPolicy;
  }

  /** Set whether we should warn about conflicting symlink entries. */
  @CanIgnoreReturnValue
  public Runfiles setConflictPolicy(ConflictPolicy conflictPolicy) {
    this.conflictPolicy = conflictPolicy;
    return this;
  }

  /**
   * Checks for conflicts between entries in a runfiles tree while putting them in a map.
   */
  public static final class ConflictChecker {
    /** Prebuilt ConflictChecker with policy set to IGNORE */
    static final ConflictChecker IGNORE_CHECKER =
        new ConflictChecker(ConflictPolicy.IGNORE, null, null);

    /** Behavior when a conflict is found. */
    private final ConflictPolicy policy;

    /** Used for warning on conflicts. May be null, in which case conflicts are ignored. */
    private final EventHandler eventHandler;

    /** Location for eventHandler warnings. Ignored if eventHandler is null. */
    private final Location location;

    /** Type of event to emit */
    private final EventKind eventKind;

    /** Construct a ConflictChecker for the given reporter with the given behavior */
    public ConflictChecker(ConflictPolicy policy, EventHandler eventHandler, Location location) {
      if (eventHandler == null) {
        this.policy = ConflictPolicy.IGNORE; // Can't warn even if we wanted to
      } else {
        this.policy = policy;
      }
      this.eventHandler = eventHandler;
      this.location = location;
      this.eventKind = (policy == ConflictPolicy.ERROR) ? EventKind.ERROR : EventKind.WARNING;
    }

    /**
     * Add an entry to a Map of symlinks, optionally reporting conflicts.
     *
     * @param map Manifest of runfile entries.
     * @param path Path fragment to use as key in map.
     * @param artifact Artifact to store in map. This may be null to indicate an empty file.
     */
    public void put(Map<PathFragment, Artifact> map, PathFragment path, Artifact artifact) {
      if (artifact != null && artifact.isMiddlemanArtifact() && eventHandler != null) {
        eventHandler.handle(
            Event.of(
                EventKind.ERROR,
                location,
                "Runfiles must not contain middleman artifacts: " + artifact));
        return;
      }
      Preconditions.checkArgument(
          artifact == null || !artifact.isMiddlemanArtifact(), "%s", artifact);
      if (policy != ConflictPolicy.IGNORE && map.containsKey(path)) {
        // Previous and new entry might have value of null
        Artifact previous = map.get(path);
        if (!Objects.equals(previous, artifact)) {
          String previousStr =
              (previous == null) ? "empty file" : previous.getExecPath().toString();
          String artifactStr =
              (artifact == null) ? "empty file" : artifact.getExecPath().toString();
          if (!previousStr.equals(artifactStr)) {
            String message =
                String.format(
                    "overwrote runfile %s, was symlink to %s, now symlink to %s",
                    path.getSafePathString(), previousStr, artifactStr);
            eventHandler.handle(Event.of(eventKind, location, message));
          }
        }
      }
      map.put(path, artifact);
    }
  }

  /**
   * Builder for Runfiles objects.
   */
  public static final class Builder {

    /** This is set to the workspace name */
    private final PathFragment suffix;

    /**
     * This must be COMPILE_ORDER because {@link #asMapWithoutRootSymlinks} overwrites earlier
     * entries with later ones, so we want a post-order iteration.
     */
    private final NestedSetBuilder<Artifact> artifactsBuilder = NestedSetBuilder.compileOrder();

    private final NestedSetBuilder<SymlinkEntry> symlinksBuilder = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<SymlinkEntry> rootSymlinksBuilder =
        NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact> extraMiddlemenBuilder = NestedSetBuilder.stableOrder();
    private EmptyFilesSupplier emptyFilesSupplier = DUMMY_EMPTY_FILES_SUPPLIER;

    /** Build the Runfiles object with this policy */
    private ConflictPolicy conflictPolicy = ConflictPolicy.IGNORE;

    private final boolean legacyExternalRunfiles;

    /**
     * Only used for Runfiles.EMPTY.
     */
    private Builder() {
      this.suffix = PathFragment.EMPTY_FRAGMENT;
      this.legacyExternalRunfiles = false;
    }

    /**
     * Creates a builder with the given suffix. Transitional constructor so that new rules don't
     * accidentally depend on the legacy repository structure, until that option is removed.
     *
     * @param workspace is the string specified in workspace() in the WORKSPACE file.
     */
    public Builder(String workspace) {
      this(workspace, false);
    }

    /**
     * Creates a builder with the given suffix.
     * @param workspace is the string specified in workspace() in the WORKSPACE file.
     * @param legacyExternalRunfiles if the wsname/external/repo symlinks should also be
     *     created.
     */
    public Builder(String workspace, boolean legacyExternalRunfiles) {
      this(PathFragment.create(workspace), legacyExternalRunfiles);
    }

    /**
     * Creates a builder with the given suffix.
     * @param suffix is the PathFragment wrapping the string specified in workspace() in the
     *     WORKSPACE file.
     * @param legacyExternalRunfiles if the wsname/external/repo symlinks should also be
     *     created.
     */
    private Builder(PathFragment suffix, boolean legacyExternalRunfiles) {
      this.suffix = suffix;
      this.legacyExternalRunfiles = legacyExternalRunfiles;
    }

    /**
     * Builds a new Runfiles object.
     */
    public Runfiles build() {
      return new Runfiles(
          suffix,
          artifactsBuilder.build(),
          symlinksBuilder.build(),
          rootSymlinksBuilder.build(),
          extraMiddlemenBuilder.build(),
          emptyFilesSupplier,
          conflictPolicy,
          legacyExternalRunfiles);
    }

    /** Adds an artifact to the internal collection of artifacts. */
    @CanIgnoreReturnValue
    public Builder addArtifact(Artifact artifact) {
      Preconditions.checkNotNull(artifact);
      Preconditions.checkArgument(
          !artifact.isMiddlemanArtifact(), "unexpected middleman artifact: %s", artifact);
      artifactsBuilder.add(artifact);
      return this;
    }

    /** Adds several artifacts to the internal collection. */
    @CanIgnoreReturnValue
    public Builder addArtifacts(Iterable<Artifact> artifacts) {
      for (Artifact artifact : artifacts) {
        addArtifact(artifact);
      }
      return this;
    }

    /** Adds a nested set to the internal collection. */
    @CanIgnoreReturnValue
    public Builder addTransitiveArtifacts(NestedSet<Artifact> artifacts) {
      artifactsBuilder.addTransitive(artifacts);
      return this;
    }

    /**
     * Adds a nested set to the internal collection.
     *
     * <p>The nested set will become wrapped in stable order. Only use this when the set of
     * artifacts will not have conflicting root relative paths, or the wrong artifact will end up in
     * the runfiles tree.
     */
    @CanIgnoreReturnValue
    public Builder addTransitiveArtifactsWrappedInStableOrder(NestedSet<Artifact> artifacts) {
      NestedSet<Artifact> wrappedArtifacts =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(artifacts).build();
      artifactsBuilder.addTransitive(wrappedArtifacts);
      return this;
    }

    /** Adds a symlink. */
    @CanIgnoreReturnValue
    public Builder addSymlink(PathFragment link, Artifact target) {
      symlinksBuilder.add(new SymlinkEntry(link, target));
      return this;
    }

    /** Adds several symlinks. Neither keys nor values may be null. */
    @CanIgnoreReturnValue
    Builder addSymlinks(Map<PathFragment, Artifact> symlinks) {
      for (Map.Entry<PathFragment, Artifact> symlink : symlinks.entrySet()) {
        symlinksBuilder.add(new SymlinkEntry(symlink.getKey(), symlink.getValue()));
      }
      return this;
    }

    /** Adds several symlinks as a NestedSet. */
    @CanIgnoreReturnValue
    public Builder addSymlinks(NestedSet<SymlinkEntry> symlinks) {
      symlinksBuilder.addTransitive(symlinks);
      return this;
    }

    /** Adds a root symlink. */
    @CanIgnoreReturnValue
    public Builder addRootSymlink(PathFragment link, Artifact target) {
      rootSymlinksBuilder.add(new SymlinkEntry(link, target));
      return this;
    }

    /** Adds several root symlinks. Neither keys nor values may be null. */
    @CanIgnoreReturnValue
    public Builder addRootSymlinks(Map<PathFragment, Artifact> symlinks) {
      for (Map.Entry<PathFragment, Artifact> symlink : symlinks.entrySet()) {
        rootSymlinksBuilder.add(new SymlinkEntry(symlink.getKey(), symlink.getValue()));
      }
      return this;
    }

    /** Adds several root symlinks as a NestedSet. */
    @CanIgnoreReturnValue
    public Builder addRootSymlinks(NestedSet<SymlinkEntry> symlinks) {
      rootSymlinksBuilder.addTransitive(symlinks);
      return this;
    }
    /**
     * Specify a function that can create additional manifest entries based on the input entries,
     * see {@link EmptyFilesSupplier} for more details.
     */
    @CanIgnoreReturnValue
    public Builder setEmptyFilesSupplier(EmptyFilesSupplier supplier) {
      emptyFilesSupplier = Preconditions.checkNotNull(supplier);
      return this;
    }

    /**
     * Merges runfiles from a given runfiles support.
     *
     * @param runfilesSupport the runfiles support to be merged in
     */
    @CanIgnoreReturnValue
    public Builder merge(@Nullable RunfilesSupport runfilesSupport) {
      if (runfilesSupport == null) {
        return this;
      }
      merge(runfilesSupport.getRunfiles());
      return this;
    }

    /** Adds the other {@link Runfiles} object transitively. */
    @CanIgnoreReturnValue
    public Builder merge(Runfiles runfiles) {
      // Propagate the most strict conflict checking from merged-in runfiles
      if (runfiles.conflictPolicy.compareTo(conflictPolicy) > 0) {
        conflictPolicy = runfiles.conflictPolicy;
      }
      if (runfiles.isEmpty()) {
        return this;
      }
      // The suffix should be the same within any blaze build, except for the EMPTY runfiles, which
      // may have an empty suffix, but that is covered above.
      Preconditions.checkArgument(
          suffix.equals(runfiles.suffix), "%s != %s", suffix, runfiles.suffix);
      artifactsBuilder.addTransitive(runfiles.getArtifacts());
      symlinksBuilder.addTransitive(runfiles.getSymlinks());
      rootSymlinksBuilder.addTransitive(runfiles.getRootSymlinks());
      extraMiddlemenBuilder.addTransitive(runfiles.getExtraMiddlemen());
      if (emptyFilesSupplier == DUMMY_EMPTY_FILES_SUPPLIER) {
        emptyFilesSupplier = runfiles.getEmptyFilesProvider();
      } else {
        EmptyFilesSupplier otherSupplier = runfiles.getEmptyFilesProvider();
        Preconditions.checkState(
            (otherSupplier == DUMMY_EMPTY_FILES_SUPPLIER)
                || emptyFilesSupplier.equals(otherSupplier));
      }
      return this;
    }

    /**
     * Adds the runfiles for a particular target and visits the transitive closure of "srcs", "deps"
     * and "data", collecting all of their respective runfiles.
     */
    @CanIgnoreReturnValue
    public Builder addRunfiles(
        RuleContext ruleContext, Function<TransitiveInfoCollection, Runfiles> mapping) {
      Preconditions.checkNotNull(mapping);
      Preconditions.checkNotNull(ruleContext);
      addDataDeps(ruleContext);
      addNonDataDeps(ruleContext, mapping);
      return this;
    }

    /**
     * Adds the files specified by a mapping from the transitive info collection to the runfiles.
     *
     * <p>Dependencies in {@code srcs} and {@code deps} are considered.
     */
    @CanIgnoreReturnValue
    public Builder add(
        RuleContext ruleContext, Function<TransitiveInfoCollection, Runfiles> mapping) {
      Preconditions.checkNotNull(ruleContext);
      Preconditions.checkNotNull(mapping);
      for (TransitiveInfoCollection dep : getNonDataDeps(ruleContext)) {
        Runfiles runfiles = mapping.apply(dep);
        if (runfiles != null) {
          merge(runfiles);
        }
      }

      return this;
    }

    /** Collects runfiles from data dependencies of a target. */
    @CanIgnoreReturnValue
    public Builder addDataDeps(RuleContext ruleContext) {
      addTargets(
          getPrerequisites(ruleContext, "data"),
          RunfilesProvider.DATA_RUNFILES,
          ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData());
      return this;
    }

    /** Collects runfiles from "srcs" and "deps" of a target. */
    @CanIgnoreReturnValue
    Builder addNonDataDeps(
        RuleContext ruleContext, Function<TransitiveInfoCollection, Runfiles> mapping) {
      for (TransitiveInfoCollection target : getNonDataDeps(ruleContext)) {
        addTargetExceptFileTargets(target, mapping);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addTargets(
        Iterable<? extends TransitiveInfoCollection> targets,
        Function<TransitiveInfoCollection, Runfiles> mapping,
        boolean alwaysIncludeFilesToBuildInData) {
      for (TransitiveInfoCollection target : targets) {
        addTarget(target, mapping, alwaysIncludeFilesToBuildInData);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addTarget(
        TransitiveInfoCollection target,
        Function<TransitiveInfoCollection, Runfiles> mapping,
        boolean alwaysIncludeFilesToBuildInData) {
      return addTargetIncludingFileTargets(target, mapping, alwaysIncludeFilesToBuildInData);
    }

    @CanIgnoreReturnValue
    private Builder addTargetExceptFileTargets(
        TransitiveInfoCollection target, Function<TransitiveInfoCollection, Runfiles> mapping) {
      Runfiles runfiles = mapping.apply(target);
      if (runfiles != null) {
        merge(runfiles);
      }

      return this;
    }

    private Builder addTargetIncludingFileTargets(
        TransitiveInfoCollection target,
        Function<TransitiveInfoCollection, Runfiles> mapping,
        boolean alwaysIncludeFilesToBuildInData) {
      if (target.getProvider(RunfilesProvider.class) == null
          && mapping == RunfilesProvider.DATA_RUNFILES) {
        // RuleConfiguredTarget implements RunfilesProvider, so this will only be called on
        // FileConfiguredTarget instances.
        // TODO(bazel-team): This is a terrible hack. We should be able to make this go away
        // by implementing RunfilesProvider on FileConfiguredTarget. We'd need to be mindful
        // of the memory use, though, since we have a whole lot of FileConfiguredTarget instances.
        addTransitiveArtifacts(target.getProvider(FileProvider.class).getFilesToBuild());
        return this;
      }

      if (alwaysIncludeFilesToBuildInData && mapping == RunfilesProvider.DATA_RUNFILES) {
        // Ensure that `DefaultInfo.files` of Starlark rules is merged in so that native rules
        // interoperate well with idiomatic Starlark rules..
        // https://bazel.build/extending/rules#runfiles_features_to_avoid
        // Internal tests fail if the order of filesToBuild is preserved.
        addTransitiveArtifacts(
            NestedSetBuilder.<Artifact>stableOrder()
                .addTransitive(target.getProvider(FileProvider.class).getFilesToBuild())
                .build());
      }

      return addTargetExceptFileTargets(target, mapping);
    }

    /**
     * Add extra middlemen artifacts that should be built by reverse dependency binaries. This
     * method exists solely to support the unfortunate legacy behavior of some rules; new uses
     * should not be added.
     */
    @CanIgnoreReturnValue
    public Builder addLegacyExtraMiddleman(Artifact middleman) {
      Preconditions.checkArgument(middleman.isMiddlemanArtifact(), middleman);
      extraMiddlemenBuilder.add(middleman);
      return this;
    }

    private static Iterable<TransitiveInfoCollection> getNonDataDeps(RuleContext ruleContext) {
      return Iterables.concat(
          // TODO(bazel-team): This line shouldn't be here. Removing it requires that no rules have
          // dependent rules in srcs (except for filegroups and such), but always in deps.
          // TODO(bazel-team): DONT_CHECK is not optimal here. Rules that use split configs need to
          // be changed not to call into here.
          getPrerequisites(ruleContext, "srcs"), getPrerequisites(ruleContext, "deps"));
    }

    /**
     * For the specified attribute "attributeName" (which must be of type list(label)), resolves all
     * the labels into ConfiguredTargets (for the same configuration as this one) and returns them
     * as a list.
     *
     * <p>If the rule does not have the specified attribute, returns the empty list.
     */
    private static Iterable<? extends TransitiveInfoCollection> getPrerequisites(
        RuleContext ruleContext, String attributeName) {
      if (ruleContext.getRule().isAttrDefined(attributeName, BuildType.LABEL_LIST)) {
        return ruleContext.getPrerequisites(attributeName);
      } else {
        return Collections.emptyList();
      }
    }
  }

  private static void verifyNestedSetDepthLimitHelper(
      NestedSet<?> nestedSet, String name, int limit) throws EvalException {
    if (nestedSet.getApproxDepth() > limit) {
      throw Starlark.errorf(
          "%s depset depth %d exceeds limit (%d)", name, nestedSet.getApproxDepth(), limit);
    }
  }

  /**
   * Checks that the depth of a Runfiles object's nested sets (artifacts, symlinks, root symlinks,
   * extra middlemen) does not exceed Starlark's depset depth limit, as specified by {@code
   * --nested_set_depth_limit}.
   *
   * @param semantics Starlark semantics providing {@code --nested_set_depth_limit}
   * @return this object, in the fluent style
   * @throws EvalException if a nested set in the Runfiles object exceeds the depth limit
   */
  @CanIgnoreReturnValue
  private Runfiles verifyNestedSetDepthLimit(StarlarkSemantics semantics) throws EvalException {
    int limit = semantics.get(BuildLanguageOptions.NESTED_SET_DEPTH_LIMIT);
    verifyNestedSetDepthLimitHelper(artifacts, "artifacts", limit);
    verifyNestedSetDepthLimitHelper(symlinks, "symlinks", limit);
    verifyNestedSetDepthLimitHelper(rootSymlinks, "root symlinks", limit);
    verifyNestedSetDepthLimitHelper(extraMiddlemen, "extra middlemen", limit);
    return this;
  }

  @Override
  public Runfiles merge(RunfilesApi other, StarlarkThread thread) throws EvalException {
    Runfiles o = (Runfiles) other;
    if (isEmpty()) {
      // This is not just a memory / performance optimization. The Builder requires a valid suffix,
      // but the {@code Runfiles.EMPTY} singleton has an invalid one, which must not be used to
      // construct a Runfiles.Builder.
      return o;
    } else if (o.isEmpty()) {
      return this;
    }
    return new Runfiles.Builder(suffix, false)
        .merge(this)
        .merge(o)
        .build()
        .verifyNestedSetDepthLimit(thread.getSemantics());
  }

  @Override
  public Runfiles mergeAll(Sequence<?> sequence, StarlarkThread thread) throws EvalException {
    // The delayed initialization of the Builder is not just a memory / performance optimization.
    // The Builder requires a valid suffix, but the {@code Runfiles.EMPTY} singleton has an invalid
    // one, which must not be used to construct a Runfiles.Builder.
    Builder builder = null;
    // When merging exactly one non-empty Runfiles object, we want to return that object and avoid a
    // Builder. This is a memory optimization and provides identical behavior for `x.merge_all([y])`
    // and `x.merge(y)` in Starlark.
    Runfiles uniqueNonEmptyMergee = null;
    if (!this.isEmpty()) {
      builder = new Builder(suffix, false).merge(this);
      uniqueNonEmptyMergee = this;
    }

    Sequence<Runfiles> runfilesSequence = Sequence.cast(sequence, Runfiles.class, "param");
    for (Runfiles runfiles : runfilesSequence) {
      if (!runfiles.isEmpty()) {
        if (builder == null) {
          builder = new Builder(runfiles.suffix, /* legacyExternalRunfiles = */ false);
          uniqueNonEmptyMergee = runfiles;
        } else {
          uniqueNonEmptyMergee = null;
        }
        builder.merge(runfiles);
      }
    }

    if (uniqueNonEmptyMergee != null) {
      return uniqueNonEmptyMergee;
    } else if (builder != null) {
      return builder.build().verifyNestedSetDepthLimit(thread.getSemantics());
    } else {
      return EMPTY;
    }
  }

  /** Fingerprint this {@link Runfiles} tree, including the absolute paths of artifacts. */
  public void fingerprint(
      ActionKeyContext actionKeyContext, Fingerprint fp, boolean digestAbsolutePaths) {
    fp.addInt(conflictPolicy.ordinal());
    fp.addBoolean(legacyExternalRunfiles);
    fp.addPath(suffix);

    actionKeyContext.addNestedSetToFingerprint(SYMLINK_ENTRY_MAP_FN, fp, symlinks);
    actionKeyContext.addNestedSetToFingerprint(SYMLINK_ENTRY_MAP_FN, fp, rootSymlinks);
    actionKeyContext.addNestedSetToFingerprint(
        digestAbsolutePaths ? RUNFILES_AND_ABSOLUTE_PATH_MAP_FN : RUNFILES_AND_EXEC_PATH_MAP_FN,
        fp,
        artifacts);

    emptyFilesSupplier.fingerprint(fp);

    // extraMiddlemen does not affect the shape of the runfiles tree described by this instance and
    // thus does not need to be fingerprinted.
  }

  /** Describes the inputs {@link #fingerprint} uses to aid describeKey() descriptions. */
  String describeFingerprint(boolean digestAbsolutePaths) {
    return String.format("conflictPolicy: %s\n", conflictPolicy)
        + String.format("legacyExternalRunfiles: %s\n", legacyExternalRunfiles)
        + String.format("suffix: %s\n", suffix)
        + String.format(
            "symlinks: %s\n", describeNestedSetFingerprint(SYMLINK_ENTRY_MAP_FN, symlinks))
        + String.format(
            "rootSymlinks: %s\n", describeNestedSetFingerprint(SYMLINK_ENTRY_MAP_FN, rootSymlinks))
        + String.format(
            "artifacts: %s\n",
            describeNestedSetFingerprint(
                digestAbsolutePaths
                    ? RUNFILES_AND_ABSOLUTE_PATH_MAP_FN
                    : RUNFILES_AND_EXEC_PATH_MAP_FN,
                artifacts))
        + String.format("emptyFilesSupplier: %s\n", emptyFilesSupplier.getClass().getName());
  }
}
