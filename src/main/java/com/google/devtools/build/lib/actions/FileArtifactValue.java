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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashFunction;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A value that represents a file for the purposes of up-to-dateness checks of actions.
 *
 * <p>It always stands for an actual file. In particular, tree artifacts and middlemen do not have a
 * corresponding {@link FileArtifactValue}. However, the file is not necessarily present in the file
 * system; this happens when intermediate build outputs are not downloaded (and maybe when an input
 * artifact of an action is missing?)
 *
 * <p>It makes its main appearance in {@code ActionExecutionValue.artifactData}. It has two main
 * uses:
 *
 * <ul>
 *   <li>This is how dependent actions get hold of the output metadata of their generated inputs.
 *   <li>This is how {@code FileSystemValueChecker} figures out which actions need to be invalidated
 *       (just propagating the invalidation up from leaf nodes is not enough, because the output
 *       tree may have been changed while Blaze was not looking)
 * </ul>
 */
@Immutable
@ThreadSafe
public abstract class FileArtifactValue implements SkyValue, HasDigest {
  /**
   * The type of the underlying file system object. If it is a regular file, then it is guaranteed
   * to have a digest. Otherwise it does not have a digest.
   */
  public abstract FileStateType getType();

  /**
   * Returns a digest of the content of the underlying file system object; must always return a
   * non-null value for instances of type {@link FileStateType#REGULAR_FILE} that are owned by an
   * {@code ActionExecutionValue}.
   *
   * <p>All instances of this interface must either have a digest or return a last-modified time.
   * Clients should prefer using the digest for content identification (e.g., for caching), and only
   * fall back to the last-modified time if no digest is available.
   *
   * <p>The return value is owned by this object and must not be modified.
   */
  @Override
  public abstract byte[] getDigest();

  /** Returns the file's size, or 0 if the underlying file system object is not a file. */
  // TODO(ulfjack): Throw an exception if it's not a file.
  public abstract long getSize();

  /**
   * Returns the last modified time; see the documentation of {@link #getDigest} for when this can
   * and should be called.
   */
  public abstract long getModifiedTime();

  // TODO(lberki): This is only used by FileArtifactValue itself. It seems possible to remove this.
  public abstract FileContentsProxy getContentsProxy();

  @Nullable
  public byte[] getValueFingerprint() {
    // TODO(janakr): return fingerprint in other cases: symlink, directory.
    return getDigest();
  }

  /**
   * Index used to resolve remote files.
   *
   * <p>0 indicates that no such information is available which can mean that it's either a local
   * file, empty, or an omitted output.
   */
  public int getLocationIndex() {
    return 0;
  }

  /** Returns {@code true} if the file only exists remotely. */
  public boolean isRemote() {
    return false;
  }

  /**
   * Provides a best-effort determination whether the file was changed since the digest was
   * computed. This method performs file system I/O, so may be expensive. It's primarily intended to
   * avoid storing bad cache entries in an action cache. It should return true if there is a chance
   * that the file was modified since the digest was computed. Better not upload if we are not sure
   * that the cache entry is reliable.
   */
  // TODO(lberki): This is very similar to couldBeModifiedSince(). Check if we can unify these.
  public abstract boolean wasModifiedSinceDigest(Path path) throws IOException;

  /**
   * Returns whether the two {@link FileArtifactValue} instances could be considered the same for
   * purposes of action invalidation.
   */
  // TODO(lberki): This is very similar to wasModifiedSinceDigest(). Check if we can unify these.
  public boolean couldBeModifiedSince(FileArtifactValue lastKnown) {
    if (this instanceof Singleton || lastKnown instanceof Singleton) {
      return true;
    }

    if (getType() != lastKnown.getType()) {
      return true;
    }

    if (getDigest() != null && lastKnown.getDigest() != null) {
      // If we know the digests, we can tell with certainty whether the file has changed.
      return !Arrays.equals(getDigest(), lastKnown.getDigest()) || getSize() != lastKnown.getSize();
    } else {
      // If not, we assume by default that the file has changed, but individual implementations
      // might know better. For example, regular local files can be compared by ctime or mtime.
      return couldBeModifiedByMetadata(lastKnown);
    }
  }

  /** Adds this file metadata to the given {@link Fingerprint}. */
  public final void addTo(Fingerprint fp) {
    byte[] digest = getDigest();
    if (digest != null) {
      fp.addBytes(digest);
    } else {
      // Use the timestamp if the digest is not present, but not both. Modifying a timestamp while
      // keeping the contents of a file the same should not cause rebuilds.
      fp.addLong(getModifiedTime());
    }
  }

  protected boolean couldBeModifiedByMetadata(FileArtifactValue lastKnown) {
    return true;
  }

  /**
   * Optional materialization path.
   *
   * <p>If present, this artifact is a copy of another artifact. It is still tracked as a
   * non-symlink by Bazel, but materialized in the local filesystem as a symlink to the original
   * artifact, whose contents live at this location. This is used by {@link
   * com.google.devtools.build.lib.remote.AbstractActionInputPrefetcher} to implement zero-cost
   * copies of remotely stored artifacts.
   */
  public Optional<PathFragment> getMaterializationExecPath() {
    return Optional.empty();
  }

  /**
   * Marker interface for singleton implementations of this class.
   *
   * <p>Needed for a correct implementation of {@code equals}.
   */
  interface Singleton {}

  @SerializationConstant
  public static final FileArtifactValue DEFAULT_MIDDLEMAN = new SingletonMarkerValue();
  /** Data that marks that a file is not present on the filesystem. */
  @SerializationConstant
  public static final FileArtifactValue MISSING_FILE_MARKER = new SingletonMarkerValue();
  /**
   * Represents an omitted file -- we are aware of it but it doesn't exist. All access methods are
   * unsupported.
   */
  @SerializationConstant
  public static final FileArtifactValue OMITTED_FILE_MARKER = new OmittedFileValue();

  public static FileArtifactValue createForSourceArtifact(
      Artifact artifact, FileValue fileValue, XattrProvider xattrProvider) throws IOException {
    // Artifacts with known generating actions should obtain the derived artifact's SkyValue
    // from the generating action, instead.
    Preconditions.checkState(!artifact.hasKnownGeneratingAction());
    Preconditions.checkState(!artifact.isConstantMetadata());
    boolean isFile = fileValue.isFile();
    return create(
        artifact.getPath(),
        isFile,
        isFile ? fileValue.getSize() : 0,
        isFile ? fileValue.realFileStateValue().getContentsProxy() : null,
        isFile ? fileValue.getDigest() : null,
        xattrProvider);
  }

  public static FileArtifactValue createFromInjectedDigest(
      FileArtifactValue metadata, @Nullable byte[] digest) {
    return createForNormalFile(digest, metadata.getContentsProxy(), metadata.getSize());
  }

  @VisibleForTesting
  public static FileArtifactValue createForTesting(Artifact artifact) throws IOException {
    return createForTesting(artifact.getPath());
  }

  @VisibleForTesting
  public static FileArtifactValue createForTesting(Path path) throws IOException {
    // Caution: there's a race condition between stating the file and computing the digest. We need
    // to stat first, since we're using the stat to detect changes. We follow symlinks here to be
    // consistent with getDigest.
    return createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE);
  }

  public static FileArtifactValue createFromStat(
      Path path, FileStatus stat, XattrProvider xattrProvider) throws IOException {
    return create(
        path,
        stat.isFile(),
        stat.getSize(),
        FileContentsProxy.create(stat),
        /* digest= */ null,
        xattrProvider);
  }

  private static FileArtifactValue create(
      Path path,
      boolean isFile,
      long size,
      FileContentsProxy proxy,
      @Nullable byte[] digest,
      XattrProvider xattrProvider)
      throws IOException {
    if (!isFile) {
      // In this case, we need to store the mtime because the action cache uses mtime for
      // directories to determine if this artifact has changed. We want this code path to go away
      // somehow.
      return new DirectoryArtifactValue(path.getLastModifiedTime());
    }
    if (digest == null) {
      digest = DigestUtils.getDigestWithManualFallback(path, xattrProvider);
    }
    Preconditions.checkState(digest != null, path);
    return createForNormalFile(digest, proxy, size);
  }

  public static FileArtifactValue createForVirtualActionInput(byte[] digest, long size) {
    return new RegularFileArtifactValue(digest, /* proxy= */ null, size);
  }

  public static FileArtifactValue createForUnresolvedSymlink(Artifact artifact) throws IOException {
    checkArgument(artifact.isSymlink());
    return createForUnresolvedSymlink(artifact.getPath());
  }

  public static FileArtifactValue createForUnresolvedSymlink(Path symlink) throws IOException {
    return new UnresolvedSymlinkArtifactValue(symlink);
  }

  @VisibleForTesting
  public static FileArtifactValue createForNormalFile(
      byte[] digest, @Nullable FileContentsProxy proxy, long size) {
    return new RegularFileArtifactValue(digest, proxy, size);
  }

  /**
   * Create a FileArtifactValue using the {@link Path} and size. FileArtifactValue#create will
   * handle getting the digest using the Path and size values.
   */
  public static FileArtifactValue createForNormalFileUsingPath(
      Path path, long size, XattrProvider xattrProvider) throws IOException {
    return create(
        path, /* isFile= */ true, size, /* proxy= */ null, /* digest= */ null, xattrProvider);
  }

  public static FileArtifactValue createForDirectoryWithHash(byte[] digest) {
    return new HashedDirectoryArtifactValue(digest);
  }

  public static FileArtifactValue createForDirectoryWithMtime(long mtime) {
    return new DirectoryArtifactValue(mtime);
  }

  /**
   * Creates a FileArtifactValue used as a 'proxy' input for other ArtifactValues. These are used in
   * {@link ActionCacheChecker}.
   */
  public static FileArtifactValue createProxy(byte[] digest) {
    Preconditions.checkNotNull(digest);
    return createForNormalFile(digest, /* proxy= */ null, /* size= */ 0);
  }

  private static String bytesToString(byte[] bytes) {
    return "0x" + BaseEncoding.base16().omitPadding().encode(bytes);
  }

  private static final class DirectoryArtifactValue extends FileArtifactValue {
    private final long mtime;

    private DirectoryArtifactValue(long mtime) {
      this.mtime = mtime;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DirectoryArtifactValue)) {
        return false;
      }

      DirectoryArtifactValue that = (DirectoryArtifactValue) o;
      return mtime == that.mtime;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(mtime);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getValueFingerprint() {
      return new Fingerprint()
          .addString(getClass().getCanonicalName())
          .addLong(mtime)
          .digestAndReset();
    }

    @Override
    public long getModifiedTime() {
      return mtime;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("mtime", mtime).toString();
    }
  }

  private static final class HashedDirectoryArtifactValue extends FileArtifactValue {

    private final byte[] digest;

    private HashedDirectoryArtifactValue(byte[] digest) {
      this.digest = digest;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof HashedDirectoryArtifactValue)) {
        return false;
      }

      HashedDirectoryArtifactValue that = (HashedDirectoryArtifactValue) o;
      return Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      // TODO(ulfjack): Ideally, we'd attempt to detect intra-build modifications here. I'm
      // consciously deferring work here as this code will most likely change again, and we're
      // already doing better than before by detecting inter-build modifications.
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("digest", digest).toString();
    }
  }

  private static final class RegularFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    @Nullable private final FileContentsProxy proxy;
    private final long size;

    private RegularFileArtifactValue(
        @Nullable byte[] digest, @Nullable FileContentsProxy proxy, long size) {
      this.digest = digest;
      this.proxy = proxy;
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RegularFileArtifactValue)) {
        return false;
      }
      RegularFileArtifactValue that = (RegularFileArtifactValue) o;
      return Arrays.equals(digest, that.digest)
          && Objects.equals(proxy, that.proxy)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), proxy, size);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      return proxy;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      if (proxy == null) {
        return false;
      }
      FileStatus stat = path.statIfFound(Symlinks.FOLLOW);
      return stat == null || !stat.isFile() || !proxy.equals(FileContentsProxy.create(stat));
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException(
          "regular file's mtime should never be called. (" + this + ")");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", BaseEncoding.base16().lowerCase().encode(digest))
          .add("size", size)
          .add("proxy", proxy)
          .toString();
    }

    @Override
    protected boolean couldBeModifiedByMetadata(FileArtifactValue o) {
      switch (o) {
        case RegularFileArtifactValue lastKnown -> {
          return size != lastKnown.size || !Objects.equals(proxy, lastKnown.proxy);
        }
        case RemoteFileArtifactValueWithMaterializationData lastKnown -> {
          return size != lastKnown.getSize() || !Objects.equals(proxy, lastKnown.proxy);
        }
        default -> {
          return true;
        }
      }
    }
  }

  /** Metadata for remotely stored files. */
  public static class RemoteFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final long size;
    private final int locationIndex;

    private RemoteFileArtifactValue(byte[] digest, long size, int locationIndex) {
      this.digest = checkNotNull(digest);
      this.size = size;
      this.locationIndex = locationIndex;
    }

    public static RemoteFileArtifactValue create(byte[] digest, long size, int locationIndex) {
      return new RemoteFileArtifactValue(digest, size, locationIndex);
    }

    public static RemoteFileArtifactValueWithMaterializationData createWithMaterializationData(
        byte[] digest,
        long size,
        int locationIndex,
        long expireAtEpochMilli,
        @Nullable PathFragment materializationExecPath) {
      return new RemoteFileArtifactValueWithMaterializationData(
          digest, size, locationIndex, materializationExecPath, expireAtEpochMilli);
    }

    /**
     * Returns a {@link RemoteFileArtifactValue} identical to the given one, except that its
     * materialization path is set to the given value unless already present.
     */
    public static RemoteFileArtifactValue createFromExistingWithMaterializationPath(
        RemoteFileArtifactValue metadata, PathFragment materializationExecPath) {
      checkNotNull(materializationExecPath);
      if (metadata.getMaterializationExecPath().isPresent()) {
        return metadata;
      }
      return createWithMaterializationData(
          metadata.getDigest(),
          metadata.getSize(),
          metadata.getLocationIndex(),
          metadata.getExpireAtEpochMilli(),
          metadata.getMaterializationExecPath().orElse(materializationExecPath));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RemoteFileArtifactValue)) {
        return false;
      }

      RemoteFileArtifactValue that = (RemoteFileArtifactValue) o;
      return Arrays.equals(digest, that.digest)
          && size == that.size
          && locationIndex == that.locationIndex;
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), size, locationIndex);
    }

    @Override
    public final FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public final byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public final long getSize() {
      return size;
    }

    @Override
    public final long getModifiedTime() {
      throw new UnsupportedOperationException(
          "RemoteFileArtifactValue doesn't support getModifiedTime");
    }

    @Override
    public final int getLocationIndex() {
      return locationIndex;
    }

    /**
     * Returns the time when the remote file expires in milliseconds since epoch. A negative value
     * means the remote is not known to expire.
     *
     * <p>Expiration time does not contribute to equality of remote files.
     */
    public long getExpireAtEpochMilli() {
      return -1;
    }

    /**
     * Extends the expiration time for this metadata. If it was constructed without known expiration
     * time (i.e. expireAtEpochMilli < 0), this extension does nothing.
     */
    public void extendExpireAtEpochMilli(long expireAtEpochMilli) {}

    public boolean isAlive(Instant now) {
      return true;
    }

    @Override
    public final boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public final boolean isRemote() {
      return true;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", size)
          .add("locationIndex", locationIndex)
          .toString();
    }
  }

  /**
   * A remote artifact that contains additional data for materialization. This is used when the
   * output mode allows Bazel to materialize remote output to local filesystem.
   */
  public static final class RemoteFileArtifactValueWithMaterializationData
      extends RemoteFileArtifactValue {
    @Nullable private final PathFragment materializationExecPath;
    private long expireAtEpochMilli;
    @Nullable private FileContentsProxy proxy;

    private RemoteFileArtifactValueWithMaterializationData(
        byte[] digest,
        long size,
        int locationIndex,
        PathFragment materializationExecPath,
        long expireAtEpochMilli) {
      super(digest, size, locationIndex);
      this.materializationExecPath = materializationExecPath;
      this.expireAtEpochMilli = expireAtEpochMilli;
    }

    @Override
    public long getExpireAtEpochMilli() {
      return expireAtEpochMilli;
    }

    @Override
    public void extendExpireAtEpochMilli(long expireAtEpochMilli) {
      if (expireAtEpochMilli < 0) {
        return;
      }
      Preconditions.checkState(expireAtEpochMilli > this.expireAtEpochMilli);
      this.expireAtEpochMilli = expireAtEpochMilli;
    }

    /**
     * Returns a non-null {@link FileContentsProxy} if this remote metadata is backed by a local
     * file, e.g. the file is materialized after action execution.
     */
    @Override
    public FileContentsProxy getContentsProxy() {
      return proxy;
    }

    /**
     * Sets the {@link FileContentsProxy} if the output backed by this remote metadata is
     * materialized later.
     */
    public void setContentsProxy(FileContentsProxy proxy) {
      this.proxy = proxy;
    }

    @Override
    public boolean isAlive(Instant now) {
      if (expireAtEpochMilli < 0) {
        return true;
      }
      return now.toEpochMilli() < expireAtEpochMilli;
    }

    @Override
    public Optional<PathFragment> getMaterializationExecPath() {
      return Optional.ofNullable(materializationExecPath);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RemoteFileArtifactValueWithMaterializationData that)) {
        return false;
      }

      return Arrays.equals(getDigest(), that.getDigest())
          && getSize() == that.getSize()
          && getLocationIndex() == that.getLocationIndex()
          && Objects.equals(materializationExecPath, that.materializationExecPath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          Arrays.hashCode(getDigest()), getSize(), getLocationIndex(), materializationExecPath);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(getDigest()))
          .add("size", getSize())
          .add("locationIndex", getLocationIndex())
          .add("materializationExecPath", materializationExecPath)
          .add("expireAtEpochMilli", expireAtEpochMilli)
          .add("proxy", proxy)
          .toString();
    }
  }

  /** A {@link FileArtifactValue} representing a symlink that is not to be resolved. */
  public static final class UnresolvedSymlinkArtifactValue extends FileArtifactValue {
    private final String symlinkTarget;
    private final byte[] digest;

    private UnresolvedSymlinkArtifactValue(Path symlink) throws IOException {
      String symlinkTarget = symlink.readSymbolicLink().getPathString();

      byte[] digest =
          symlink
              .getFileSystem()
              .getDigestFunction()
              .getHashFunction()
              .hashString(symlinkTarget, ISO_8859_1)
              .asBytes();

      // We need to be able to tell the difference between a symlink and a file containing the same
      // text. So we transform the digest a bit. This works because if one wants to craft a file
      // with the same digest as a symlink, one would need to mount a preimage attack on the digest
      // function (this would be different if we tweaked the data before applying the hash function)
      digest[0] = (byte) (digest[0] ^ 0xff);

      this.symlinkTarget = symlinkTarget;
      this.digest = digest;
    }

    public String getSymlinkTarget() {
      return symlinkTarget;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.SYMLINK;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      throw new IllegalStateException();
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new IllegalStateException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      try {
        var newMetadata = FileArtifactValue.createForUnresolvedSymlink(path);
        return !Arrays.equals(digest, newMetadata.getDigest());
      } catch (IOException e) {
        return true;
      }
    }
  }

  /** File stored inline in metadata. */
  public static final class InlineFileArtifactValue extends FileArtifactValue {

    public static InlineFileArtifactValue create(byte[] bytes, HashFunction hashFunction) {
      return new InlineFileArtifactValue(bytes, hashFunction.hashBytes(bytes).asBytes());
    }

    private final byte[] data;
    private final byte[] digest;

    private InlineFileArtifactValue(byte[] data, byte[] digest) {
      this.data = Preconditions.checkNotNull(data);
      this.digest = Preconditions.checkNotNull(digest);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InlineFileArtifactValue)) {
        return false;
      }
      InlineFileArtifactValue that = (InlineFileArtifactValue) o;
      return Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }

    public ByteArrayInputStream getInputStream() {
      return new ByteArrayInputStream(data);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      return data.length;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Used to resolve source symlinks when diskless.
   *
   * <p>When the optional per-action file system creates symlinks, it relies on metadata ({@link
   * FileArtifactValue}) to resolve the actual underlying data. In the case of remote or inline
   * files, this information is self-contained. However, in the case of source files, the path is
   * required to resolve the content.
   */
  public static final class SourceFileArtifactValue extends FileArtifactValue {
    private final PathFragment path;
    private final PathFragment execPath;
    private final byte[] digest;
    private final long size;

    public SourceFileArtifactValue(
        PathFragment path, PathFragment execPath, byte[] digest, long size) {
      Preconditions.checkArgument(path.isAbsolute(), "path %s isn't absolute", path);
      Preconditions.checkArgument(
          path.endsWith(execPath), "path %s doesn't end with execPath %s", path, execPath);
      this.path = path;
      this.execPath = Preconditions.checkNotNull(execPath);
      this.digest = Preconditions.checkNotNull(digest);
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SourceFileArtifactValue)) {
        return false;
      }

      SourceFileArtifactValue that = (SourceFileArtifactValue) o;
      return Objects.equals(path, that.path)
          && Objects.equals(execPath, that.execPath)
          && Arrays.equals(digest, that.digest)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, execPath, Arrays.hashCode(digest), size);
    }

    public PathFragment getPath() {
      return path;
    }

    public PathFragment getExecPath() {
      return execPath;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class SingletonMarkerValue extends FileArtifactValue implements Singleton {
    private static final byte[] FINGERPRINT = new byte[] {0x10};

    @Override
    public FileStateType getType() {
      return FileStateType.NONEXISTENT;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public byte[] getValueFingerprint() {
      return FINGERPRINT;
    }

    @Override
    public String toString() {
      return "singleton marker artifact value (" + hashCode() + ")";
    }
  }

  private static final class OmittedFileValue extends FileArtifactValue implements Singleton {
    @Override
    public FileStateType getType() {
      return FileStateType.NONEXISTENT;
    }

    @Override
    public byte[] getDigest() {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public String toString() {
      return "OMITTED_FILE_MARKER";
    }
  }
}
