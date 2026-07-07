// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.Futures.immediateCancelledFuture;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;
import static com.google.devtools.build.lib.util.StringUtilities.bytesCountToDisplayString;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Striped;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSymlinkLoopException;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunctionException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * A file system that overlays the native file system with a {@link RemoteExternalFileSystem} for
 * the "external" directory, which contains the contents of external repositories.
 *
 * <p>Each external repository can either be materialized to the native file system or kept in
 * memory in the {@link RemoteExternalFileSystem}.
 */
public final class RemoteExternalOverlayFileSystem extends FileSystem
    implements AbstractActionInputPrefetcher.ExternalRepositoryOverlay {
  /** Describes how {@link #routeAcrossFileSystems} handles symbolic links. */
  private enum FollowMode {
    /** Canonicalize the entire path. This is equivalent to {@link Symlinks#FOLLOW}. */
    FOLLOW_ALL,
    /** Canonicalize only the parent. This is equivalent to {@link Symlinks#NOFOLLOW}. */
    FOLLOW_PARENT,
    /** Do not canonicalize. The path's parent must already be canonical. */
    FOLLOW_NONE,
  }

  /** The backing file system and canonical path selected by cross-file-system routing. */
  private static final class RoutedPath {
    private final FileSystem owner;
    private final PathFragment canonicalPath;
    private boolean statusKnown;
    @Nullable private FileStatus status;

    /** Creates a routed path whose final status will be loaded on demand. */
    RoutedPath(FileSystem owner, PathFragment canonicalPath) {
      this.owner = owner;
      this.canonicalPath = canonicalPath;
    }

    /** Creates a routed path with an already known, possibly missing, final status. */
    RoutedPath(FileSystem owner, PathFragment canonicalPath, @Nullable FileStatus status) {
      this(owner, canonicalPath);
      this.status = status;
      this.statusKnown = true;
    }

    FileSystem owner() {
      return owner;
    }

    PathFragment canonicalPath() {
      return canonicalPath;
    }

    /** Returns the final status without following symlinks, loading it at most once. */
    @Nullable
    FileStatus status() throws IOException {
      if (!statusKnown) {
        status = owner.statIfFound(canonicalPath, /* followSymlinks= */ false);
        statusKnown = true;
      }
      return status;
    }
  }

  @FunctionalInterface
  private interface FileSystemOperation<T> {
    T run(FileSystem fileSystem, PathFragment path) throws IOException;
  }

  @FunctionalInterface
  private interface RoutedPathOperation<T> {
    T run(RoutedPath routedPath) throws IOException;
  }

  @FunctionalInterface
  private interface FileSystemQuery<T> {
    T run(FileSystem fileSystem, PathFragment path);
  }

  @FunctionalInterface
  private interface RoutedQuery<T> {
    T run();
  }

  /** Signals a missing path component without conflating it with other I/O errors. */
  private static final class MissingPathException extends IOException {
    MissingPathException(PathFragment path) {
      super(path.getPathString() + ERR_NO_SUCH_FILE_OR_DIR);
    }
  }

  /** A link encountered while resolving a path for host materialization. */
  private record HostSymlink(PathFragment linkPath, PathFragment targetPath) {}

  /** The result of resolving every component of a logical path through the overlay. */
  private record HostTrace(PathFragment resolvedPath, ImmutableList<HostSymlink> symlinks) {}

  private final PathFragment externalDirectory;
  private final int externalDirectorySegmentCount;
  private final FileSystem nativeFs;
  private final RemoteExternalFileSystem externalFs;
  private PathCanonicalizer pathCanonicalizer;
  // Host repair can touch both the source and target repositories of one cross-repository link.
  // Plans acquire all affected repository locks in a common stripe order during short synchronous
  // sections; downloads themselves run without holding a lock.
  private final Striped<Lock> hostMaterializationLocks = Striped.lazyWeakLock(Integer.MAX_VALUE);
  private final ConcurrentHashMap<String, Future<Void>> materializations =
      new ConcurrentHashMap<>();
  // As long as a repo name appears as a key in this map, the repo contents are available in
  // externalFs.
  private final ConcurrentHashMap<String, String> markerFileContents = new ConcurrentHashMap<>();
  private final Set<String> reposWithLostFiles = ConcurrentHashMap.newKeySet();
  // Eager prefetching during injection writes native shadows before markerFileContents publishes
  // the repo as externally owned. Those prefetches must keep using the ordinary host path.
  private final Set<String> reposBeingInjected = ConcurrentHashMap.newKeySet();

  // Per-build information that is set in beforeCommand and cleared in afterCommand.
  @Nullable private CombinedCache cache;
  @Nullable private AbstractActionInputPrefetcher inputPrefetcher;
  @Nullable private Reporter reporter;
  @Nullable private String buildRequestId;
  @Nullable private String commandId;
  @Nullable private MemoizingEvaluator evaluator;
  @Nullable private Duration remoteCacheTtl;
  @Nullable private ExecutorService materializationExecutor;

  public RemoteExternalOverlayFileSystem(PathFragment externalDirectory, FileSystem nativeFs) {
    super(nativeFs.getDigestFunction());
    this.externalDirectory = externalDirectory;
    this.externalDirectorySegmentCount = externalDirectory.segmentCount();
    this.nativeFs = nativeFs;
    this.externalFs = new RemoteExternalFileSystem(nativeFs.getDigestFunction());
    resetPathCanonicalizer();
  }

  public void beforeCommand(
      CombinedCache cache,
      AbstractActionInputPrefetcher inputPrefetcher,
      Reporter reporter,
      String buildRequestId,
      String commandId,
      MemoizingEvaluator evaluator,
      Duration remoteCacheTtl) {
    checkState(
        this.cache == null
            && this.inputPrefetcher == null
            && this.reporter == null
            && this.buildRequestId == null
            && this.commandId == null
            && this.evaluator == null
            && this.remoteCacheTtl == null
            && this.materializationExecutor == null);
    resetPathCanonicalizer();
    this.cache = cache;
    this.inputPrefetcher = inputPrefetcher;
    this.reporter = reporter;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.evaluator = evaluator;
    this.remoteCacheTtl = remoteCacheTtl;
    this.materializationExecutor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("remote-repo-materialization-", 0).factory());
  }

  public void afterCommand() {
    try {
      if (cache == null) {
        // Not all commands cause beforeCommand to be called, but afterCommand is called
        // unconditionally.
        return;
      }
      this.cache = null;
      this.inputPrefetcher = null;
      this.reporter = null;
      this.buildRequestId = null;
      this.commandId = null;
      this.remoteCacheTtl = null;
      // Materializations happen synchronously and upon request by other repo rules, so there is no
      // reason to await their orderly completion in afterCommand.
      materializationExecutor.shutdownNow();
      materializationExecutor = null;
      // Clean up the in-memory contents of materialized repos to save memory, or those that need to
      // be refetched to recover files that the remote cache has lost. This wouldn't be safe to do
      // eagerly as ongoing repo rule evaluations may still refer to the in-memory content and
      // refetching is not atomic.
      materializations.forEach(
          1,
          (repoName, materializationState) ->
              materializationState.state() == Future.State.SUCCESS
                      || reposWithLostFiles.contains(repoName)
                  ? repoName
                  : null,
          this::evictInMemoryRepo);
      invalidateRepoDirectories(evaluator, reposWithLostFiles);
      reposWithLostFiles.clear();
      this.evaluator = null;
    } finally {
      resetPathCanonicalizer();
    }
  }

  /** Removes the contents of the given repo from the in-memory overlay file system. */
  private void evictInMemoryRepo(String repoName) {
    PathFragment repoPath = externalDirectory.getChild(repoName);
    Lock lock = hostMaterializationLocks.get(repoPath);
    lock.lock();
    try {
      // Clear before and after changing repository state so a racing access can't repopulate stale
      // canonicalization entries.
      pathCanonicalizer.clearPrefix(repoPath);
      try {
        externalFs.deleteTree(repoPath);
      } catch (IOException e) {
        throw new IllegalStateException("In-memory file system is not expected to throw", e);
      }
      materializations.remove(repoName);
      markerFileContents.remove(repoName);
      pathCanonicalizer.clearPrefix(repoPath);
    } finally {
      lock.unlock();
    }
  }

  /** Invalidates the {@link SkyFunctions#REPOSITORY_DIRECTORY} nodes of the given repos. */
  private static void invalidateRepoDirectories(
      MemoizingEvaluator evaluator, Set<String> repoNames) {
    if (repoNames.isEmpty()) {
      return;
    }
    evaluator.delete(
        k ->
            k.functionName().equals(SkyFunctions.REPOSITORY_DIRECTORY)
                && repoNames.contains(((RepositoryName) k.argument()).getName()));
  }

  /**
   * Injects the given remote contents, possibly prefetching some files, and returns true on
   * success.
   */
  public boolean injectRemoteRepo(RepositoryName repo, Tree remoteContents, String markerFile)
      throws IOException, InterruptedException {
    String repoName = repo.getName();
    var repoDir = externalDirectory.getChild(repo.getName());
    Lock lock = hostMaterializationLocks.get(repoDir);
    lock.lock();
    try {
      reposBeingInjected.add(repoName);
      pathCanonicalizer.clearPrefix(repoDir);
      materializations.remove(repoName);
      markerFileContents.remove(repoName);
      pathCanonicalizer.clearPrefix(repoDir);
      deleteTree(repoDir);
      var unused = delete(externalDirectory.getChild(repo.getMarkerFileName()));
    } catch (IOException | RuntimeException e) {
      reposBeingInjected.remove(repoName);
      throw e;
    } finally {
      lock.unlock();
    }

    boolean injectionStateFinalized = false;
    try {
      var childMap =
          remoteContents.getChildrenList().stream()
              .collect(
                  toImmutableMap(cache.digestUtil::compute, directory -> directory, (a, b) -> a));
      var filesToPrefetch = new ArrayList<PathFragment>();
      injectRecursively(
          externalFs,
          repoDir,
          remoteContents.getRoot(),
          childMap,
          filesToPrefetch::add,
          Instant.now().plus(remoteCacheTtl));
      try {
        // TODO: This prefetches a large number of small files. Investigate whether BatchReadBlobs
        // would be more efficient.
        prefetch(filesToPrefetch);
      } catch (BulkTransferException e) {
        if (!e.allCausedByCacheNotFoundException()) {
          throw e;
        }
        // The cache has lost the .bzl files, which should be treated just like a cache miss.
        lock.lock();
        try {
          // Clear before and after changing repository state so a racing access can't repopulate
          // stale canonicalization entries.
          pathCanonicalizer.clearPrefix(repoDir);
          externalFs.deleteTree(repoDir);
          materializations.remove(repoName);
          markerFileContents.remove(repoName);
          reposBeingInjected.remove(repoName);
          pathCanonicalizer.clearPrefix(repoDir);
          injectionStateFinalized = true;
        } finally {
          lock.unlock();
        }
        return false;
      }
      // Create the repo directory on disk so that readdir reflects the overlaid state of the
      // external directory.
      nativeFs.createDirectoryAndParents(repoDir);
      lock.lock();
      try {
        // Keep the marker file contents in memory so that it can be written out when the repo is
        // materialized. This doubles as a presence marker for the in-memory repo contents.
        // Clear before and after changing repository state so a racing access can't repopulate
        // stale canonicalization entries.
        pathCanonicalizer.clearPrefix(repoDir);
        markerFileContents.put(repoName, markerFile);
        reposBeingInjected.remove(repoName);
        pathCanonicalizer.clearPrefix(repoDir);
        injectionStateFinalized = true;
      } finally {
        lock.unlock();
      }
      return true;
    } finally {
      if (!injectionStateFinalized) {
        lock.lock();
        try {
          reposBeingInjected.remove(repoName);
        } finally {
          lock.unlock();
        }
      }
    }
  }

  private static void injectRecursively(
      RemoteExternalFileSystem fs,
      PathFragment path,
      Directory dir,
      ImmutableMap<Digest, Directory> childMap,
      Consumer<PathFragment> filesToPrefetch,
      Instant expirationTime)
      throws IOException {
    fs.createDirectoryAndParents(path);
    for (var file : dir.getFilesList()) {
      var filePath = path.getRelative(unicodeToInternal(file.getName()));
      if (shouldPrefetch(filePath)) {
        filesToPrefetch.accept(filePath);
      }
      fs.injectFile(
          filePath,
          // Using the *WithMaterializationData variant ensures that the file benefits from the
          // FileContentsProxy optimization to avoid widespread invalidation when it is
          // materialized later, even if expiration times aren't relevant (depends on the usage
          // of the lease extension).
          FileArtifactValue.createForRemoteFileWithMaterializationData(
              DigestUtil.toBinaryDigest(file.getDigest()),
              file.getDigest().getSizeBytes(),
              /* locationIndex= */ 1,
              expirationTime,
              /* inMemoryOutput= */ false));
      fs.setExecutable(filePath, file.getIsExecutable());
      // The RE API does not track whether a file is readable or writable. We choose to make all
      // files readable and not writable to ensure that other repo rules can't accidentally modify
      // the cached repo.
      fs.setWritable(filePath, false);
    }
    for (var symlink : dir.getSymlinksList()) {
      fs.createSymbolicLink(
          path.getRelative(unicodeToInternal(symlink.getName())),
          PathFragment.create(unicodeToInternal(symlink.getTarget())));
    }
    for (var subdirNode : dir.getDirectoriesList()) {
      var subdirPath = path.getRelative(unicodeToInternal(subdirNode.getName()));
      var subdir = childMap.get(subdirNode.getDigest());
      if (subdir == null) {
        throw new IOException(
            "Directory %s with digest %s not found in tree"
                .formatted(subdirPath, subdirNode.getDigest().getHash()));
      }
      injectRecursively(fs, subdirPath, subdir, childMap, filesToPrefetch, expirationTime);
    }
  }

  /**
   * Materializes the given external repository to the native file system if it hasn't been
   * materialized yet. This method blocks until the materialization is complete.
   *
   * <p>This should only be used for cases in which the given repo is accessed non-hermetically,
   * such as when another repo rule that depends on its files executes a command. Selective reads by
   * Bazel or local actions are handled automatically by the file system or {@link
   * AbstractActionInputPrefetcher}.
   */
  public void ensureMaterialized(RepositoryName repo, ExtendedEventHandler reporter)
      throws IOException, InterruptedException {
    if (!markerFileContents.containsKey(repo.getName())) {
      // The repo has not been injected into the in-memory file system.
      return;
    }
    var unused =
        getFromFuture(
            materializations.computeIfAbsent(
                repo.getName(),
                unusedRepoName ->
                    materializationExecutor.submit(
                        () -> {
                          doMaterialize(repo, reporter);
                          return null;
                        })));
  }

  private void doMaterialize(RepositoryName repo, ExtendedEventHandler reporter)
      throws IOException, InterruptedException {
    reporter.handle(Event.debug("Materializing remote repo %s".formatted(repo)));
    var repoPath = externalDirectory.getChild(repo.getName());
    var remoteRepo = externalFs.getPath(repoPath);
    var walkResult = walk(remoteRepo);
    for (var directory : walkResult.directories()) {
      nativeFs.getPath(directory).createDirectory();
    }
    prefetch(walkResult.files());
    // Create symlinks last as some platforms don't allow creating a symlink to a non-existent
    // target.
    prefetch(walkResult.symlinks());

    // After the repo has been copied, atomically materialize the marker file. This ensures that the
    // repo doesn't have to be refetched after the next server restart.
    var markerFile = nativeFs.getPath(externalDirectory.getChild(repo.getMarkerFileName()));
    var markerFileSibling =
        nativeFs.getPath(externalDirectory.getChild(repo.getMarkerFileName() + ".tmp"));
    String markerContents;
    Lock lock = hostMaterializationLocks.get(repoPath);
    lock.lock();
    try {
      pathCanonicalizer.clearPrefix(repoPath);
      markerContents = markerFileContents.remove(repo.getName());
      // Clear again in case an access raced with the ownership switch above.
      pathCanonicalizer.clearPrefix(repoPath);
    } finally {
      lock.unlock();
    }
    FileSystemUtils.writeContentAsLatin1(markerFileSibling, markerContents);
    markerFileSibling.renameTo(markerFile);
  }

  private void prefetch(List<PathFragment> paths) throws IOException, InterruptedException {
    var unused =
        getFromFuture(
            inputPrefetcher.prefetchFilesInterruptibly(
                /* action= */ null,
                Lists.transform(paths, ActionInputHelper::fromPath),
                actionInput -> externalFs.getMetadata(actionInput.getExecPath()),
                ActionInputPrefetcher.Priority.CRITICAL,
                ActionInputPrefetcher.Reason.INPUTS));
  }

  /**
   * Informs the FS that no cache is available and in-memory repos can no longer be used.
   *
   * <p>Must not be called while accessing external repos.
   */
  public void notifyNoCacheAvailable(MemoizingEvaluator evaluator) {
    checkState(materializationExecutor == null, "must not be called when active");
    resetPathCanonicalizer();
    var reposToDiscard = ImmutableSet.copyOf(markerFileContents.keySet());
    reposToDiscard.forEach(this::evictInMemoryRepo);
    invalidateRepoDirectories(evaluator, reposToDiscard);
  }

  private record WalkResult(
      List<PathFragment> files, List<PathFragment> symlinks, List<PathFragment> directories) {}

  private static WalkResult walk(Path root) throws IOException {
    var result = new WalkResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    walk(root, result);
    return result;
  }

  private static void walk(Path root, WalkResult result) throws IOException {
    for (var dirent : root.readdir(Symlinks.NOFOLLOW)) {
      var fromChild = root.getChild(dirent.getName());
      switch (dirent.getType()) {
        case FILE -> result.files.add(fromChild.asFragment());
        case SYMLINK -> result.symlinks.add(fromChild.asFragment());
        case DIRECTORY -> {
          result.directories.add(fromChild.asFragment());
          walk(fromChild, result);
        }
        default -> throw new IOException("Unsupported file type: " + dirent);
      }
    }
  }

  /** Whether the file with the given path should be materialized eagerly when injecting a repo. */
  private static boolean shouldPrefetch(PathFragment path) {
    // .bzl files are typically small and the loads between them can form complex DAGs that can only
    // be discovered layer by layer, so prefetching is worthwhile to reduce the number of sequential
    // cache requests.
    // The REPO.bazel file, if present, is a dependency of any package and will thus have to be
    // fetched anyway.
    return path.getFileExtension().equals("bzl") || path.getBaseName().equals("REPO.bazel");
  }

  @Override
  public FileSystem getHostFileSystem() {
    return nativeFs.getHostFileSystem();
  }

  @Nullable
  @Override
  public AbstractActionInputPrefetcher.HostMaterialization getHostMaterialization(Path path)
      throws IOException {
    if (path.getFileSystem() != this || !path.asFragment().startsWith(externalDirectory)) {
      return null;
    }
    PathFragment logicalPath = path.asFragment();
    if (isBeingInjected(logicalPath)) {
      return null;
    }
    return new OverlayHostMaterialization(logicalPath, traceHostPath(logicalPath));
  }

  private boolean isBeingInjected(PathFragment path) {
    return !path.equals(externalDirectory)
        && reposBeingInjected.contains(path.getSegment(externalDirectorySegmentCount));
  }

  /** Traces a path without mutating ownership state or acquiring repository locks. */
  private HostTrace traceHostPath(PathFragment logicalPath) throws IOException {
    var symlinks = ImmutableList.<HostSymlink>builder();
    PathFragment resolvedPath = logicalPath;
    int remainingLinks = 32;
    int segmentIndex = 0;
    while (segmentIndex < resolvedPath.segmentCount()) {
      PathFragment candidate = resolvedPath.subFragment(0, segmentIndex + 1);
      FileSystem owner = fsForPath(candidate, /* cleanUpLostRepo= */ false);
      FileStatus status = owner.statIfFound(candidate, /* followSymlinks= */ false);
      if (status == null) {
        break;
      }
      if (!status.isSymbolicLink()) {
        segmentIndex++;
        continue;
      }
      if (remainingLinks-- == 0) {
        throw new FileSymlinkLoopException(logicalPath.getPathString() + ERR_TOO_MANY_SYMLINKS);
      }
      PathFragment target = owner.readSymbolicLink(candidate);
      symlinks.add(new HostSymlink(candidate, target));
      if (!target.isAbsolute()) {
        target = candidate.getParentDirectory().getRelative(target);
      }
      PathFragment suffix = resolvedPath.subFragment(segmentIndex + 1, resolvedPath.segmentCount());
      resolvedPath = target.getRelative(suffix);
      segmentIndex = 0;
    }
    return new HostTrace(resolvedPath, symlinks.build());
  }

  private PathFragment repositoryRoot(PathFragment path) {
    return path.startsWith(externalDirectory) && !path.equals(externalDirectory)
        ? externalDirectory.getChild(path.getSegment(externalDirectorySegmentCount))
        : externalDirectory;
  }

  /** Host materialization work computed from one component-by-component overlay traversal. */
  private final class OverlayHostMaterialization
      implements AbstractActionInputPrefetcher.HostMaterialization {
    private final PathFragment resolvedPath;
    private final PathFragment logicalPath;
    private final HostTrace trace;
    private final ImmutableList<HostSymlink> symlinks;
    private final ImmutableSet<PathFragment> repositoryRoots;
    private final ImmutableList<Lock> locks;

    OverlayHostMaterialization(PathFragment logicalPath, HostTrace trace) {
      this.logicalPath = logicalPath;
      this.trace = trace;
      this.resolvedPath = trace.resolvedPath();
      this.symlinks = trace.symlinks();
      var repositoryRoots = ImmutableSet.<PathFragment>builder();
      repositoryRoots.add(repositoryRoot(logicalPath));
      if (resolvedPath.startsWith(externalDirectory)) {
        repositoryRoots.add(repositoryRoot(resolvedPath));
      }
      for (HostSymlink symlink : symlinks) {
        if (symlink.linkPath().startsWith(externalDirectory)) {
          repositoryRoots.add(repositoryRoot(symlink.linkPath()));
        }
      }
      // bulkGet orders the actual stripes, not the keys, so overlapping multi-repository plans
      // acquire their locks in a common order.
      this.repositoryRoots = repositoryRoots.build();
      this.locks = ImmutableList.copyOf(hostMaterializationLocks.bulkGet(this.repositoryRoots));
    }

    @Override
    public Path getResolvedPath() {
      return getPath(resolvedPath);
    }

    @Override
    public AbstractActionInputPrefetcher.PreparedDownloadTarget prepareDownloadTarget(
        AbstractActionInputPrefetcher.HostPathChecker checker) throws IOException {
      lockAll();
      try {
        validateTraceLocked();
        Path hostTarget = prepareDownloadTargetLocked(resolvedPath);
        return new AbstractActionInputPrefetcher.PreparedDownloadTarget(
            hostTarget, checker.run(hostTarget));
      } finally {
        unlockAll();
      }
    }

    @Override
    public void finalizeDownload(AbstractActionInputPrefetcher.HostFinalizer finalizer)
        throws IOException {
      lockAll();
      try {
        validateTraceLocked();
        prepareDownloadTargetLocked(resolvedPath);
        finalizer.run();
      } finally {
        unlockAll();
      }
    }

    @Override
    public void materializeSymlinks() throws IOException {
      lockAll();
      try {
        validateTraceLocked();
        // Plant targets before links. This is required for platforms where the symlink type is
        // inferred from an existing target.
        for (HostSymlink symlink : symlinks.reverse()) {
          materializeSymlinkLocked(symlink);
        }
      } finally {
        unlockAll();
      }
    }

    private void lockAll() {
      for (Lock lock : locks) {
        lock.lock();
      }
    }

    private void unlockAll() {
      for (int i = locks.size() - 1; i >= 0; i--) {
        locks.get(i).unlock();
      }
    }

    private void validateTraceLocked() throws IOException {
      if (repositoryRoots.stream().anyMatch(RemoteExternalOverlayFileSystem.this::isBeingInjected)
          || !traceHostPath(logicalPath).equals(trace)) {
        throw new IOException(String.format("External repository path changed: %s", logicalPath));
      }
    }
  }

  /**
   * Verifies a host path from the trusted external root downward without following host links.
   *
   * <p>The final component is a regular-file download target. A missing target is left for the
   * caller's guarded content check to request from the remote cache.
   */
  private Path prepareDownloadTargetLocked(PathFragment logicalTarget) throws IOException {
    if (!logicalTarget.startsWith(externalDirectory) || logicalTarget.equals(externalDirectory)) {
      return nativeFs.getPath(logicalTarget).forHostFileSystem();
    }
    FileSystem currentOwner = fsForPath(logicalTarget);
    if (currentOwner == externalFs) {
      FileStatus logicalStatus = externalFs.statIfFound(logicalTarget, /* followSymlinks= */ false);
      if (logicalStatus == null || !logicalStatus.isFile()) {
        throw new IOException(
            String.format("Expected external file download target: %s", logicalTarget));
      }
    }
    prepareHostParentLocked(logicalTarget);
    Path hostTarget = nativeFs.getPath(logicalTarget).forHostFileSystem();
    FileStatus hostStatus = hostTarget.statIfFound(Symlinks.NOFOLLOW);
    if (hostStatus == null) {
      return hostTarget;
    }
    if (hostStatus.isFile()) {
      return hostTarget;
    }
    if (currentOwner != externalFs) {
      throw new IOException(String.format("Expected native file download target: %s", hostTarget));
    }
    if (hostStatus.isDirectory()) {
      hostTarget.deleteTree();
    } else {
      hostTarget.delete();
    }
    return hostTarget;
  }

  /** Ensures that every parent below the trusted external root is an expected real directory. */
  private void prepareHostParentLocked(PathFragment logicalPath) throws IOException {
    PathFragment logicalParent = logicalPath.getParentDirectory();
    if (logicalParent == null || !logicalParent.startsWith(externalDirectory)) {
      return;
    }
    Path hostExternalDirectory = nativeFs.getPath(externalDirectory).forHostFileSystem();
    FileStatus rootStatus = hostExternalDirectory.statIfFound(Symlinks.NOFOLLOW);
    if (rootStatus == null || !rootStatus.isDirectory()) {
      throw new IOException(
          String.format(
              "External repository directory is not a directory: %s", hostExternalDirectory));
    }

    PathFragment logicalDirectory = externalDirectory;
    Path hostDirectory = hostExternalDirectory;
    for (String segment : logicalParent.relativeTo(externalDirectory).segments()) {
      logicalDirectory = logicalDirectory.getChild(segment);
      hostDirectory = hostDirectory.getChild(segment);
      FileStatus logicalStatus = statIfFoundInternal(logicalDirectory, /* followSymlinks= */ false);
      if (logicalStatus == null || !logicalStatus.isDirectory()) {
        throw new IOException(
            String.format("Expected external repository directory: %s", logicalDirectory));
      }
      FileStatus hostStatus = hostDirectory.statIfFound(Symlinks.NOFOLLOW);
      if (hostStatus != null && hostStatus.isDirectory()) {
        continue;
      }
      if (hostStatus != null) {
        // Every ancestor has already been checked with NOFOLLOW, so unlinking this component
        // cannot traverse a stale link out of the repository tree.
        hostDirectory.delete();
      }
      hostDirectory.createDirectory();
    }
  }

  private void materializeSymlinkLocked(HostSymlink symlink) throws IOException {
    FileSystem currentOwner = fsForPath(symlink.linkPath());
    if (currentOwner != externalFs) {
      // The repository was materialized after this plan was created. Its native state is already
      // the host representation, so the old external state no longer has destructive authority.
      return;
    }
    FileStatus logicalStatus =
        currentOwner.statIfFound(symlink.linkPath(), /* followSymlinks= */ false);
    if (logicalStatus == null
        || !logicalStatus.isSymbolicLink()
        || !currentOwner.readSymbolicLink(symlink.linkPath()).equals(symlink.targetPath())) {
      throw new IOException(
          String.format("External repository symlink changed: %s", symlink.linkPath()));
    }
    prepareHostParentLocked(symlink.linkPath());
    Path hostLink = nativeFs.getPath(symlink.linkPath()).forHostFileSystem();
    try {
      if (hostLink.readSymbolicLink().equals(symlink.targetPath())) {
        return;
      }
    } catch (FileNotFoundException | NotASymlinkException ignored) {
      // Fall through and replace the stale host node.
    }
    FileStatus hostStatus = hostLink.statIfFound(Symlinks.NOFOLLOW);
    if (hostStatus != null && hostStatus.isDirectory()) {
      // The verified parent walk contains recursive removal to this final component.
      hostLink.deleteTree();
    } else if (hostStatus != null) {
      hostLink.delete();
    }
    hostLink.createSymbolicLink(symlink.targetPath());
  }

  // Always mirror tree deletions to both backing file systems. Native files may shadow entries in
  // an injected repository after selective prefetching and must not survive deletion of the
  // corresponding in-memory subtree.

  @Override
  public void deleteTree(PathFragment path) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(path, routedPath);
    nativeFs.deleteTree(routedPath.canonicalPath());
    externalFs.deleteTree(routedPath.canonicalPath());
  }

  @Override
  public void deleteTreesBelow(PathFragment dir) throws IOException {
    RoutedPath routedPath = route(dir, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(dir, routedPath);
    nativeFs.deleteTreesBelow(routedPath.canonicalPath());
    externalFs.deleteTreesBelow(routedPath.canonicalPath());
  }

  // Selects the backing file system for a path based on current repository ownership. During
  // cross-file-system routing, this is called for every canonical prefix so that a symlink can
  // change backings in either direction. Overrides still invoke the selected backing operation
  // directly so that backing-specific fast paths remain available.
  private FileSystem fsForPath(PathFragment path) {
    return fsForPath(path, /* cleanUpLostRepo= */ true);
  }

  private FileSystem fsForPath(PathFragment path, boolean cleanUpLostRepo) {
    if (path.startsWith(externalDirectory) && !path.equals(externalDirectory)) {
      String repoName = path.getSegment(externalDirectorySegmentCount);
      PathFragment repoPath = externalDirectory.getChild(repoName);
      var hasBeenInjected = markerFileContents.containsKey(repoName);
      var hasBeenMaterialized =
          materializations.getOrDefault(repoName, immediateCancelledFuture()).state()
              == Future.State.SUCCESS;
      if (hasBeenInjected && !hasBeenMaterialized) {
        // The repo may have been deleted due to refetching. Clean up in-memory state if that is the
        // case.
        if (externalFs.getPath(repoPath).exists()) {
          return externalFs;
        }
        if (!cleanUpLostRepo) {
          return nativeFs;
        }
        Lock lock = hostMaterializationLocks.get(repoPath);
        lock.lock();
        try {
          hasBeenInjected = markerFileContents.containsKey(repoName);
          hasBeenMaterialized =
              materializations.getOrDefault(repoName, immediateCancelledFuture()).state()
                  == Future.State.SUCCESS;
          if (hasBeenInjected && !hasBeenMaterialized) {
            if (externalFs.getPath(repoPath).exists()) {
              return externalFs;
            }
            materializations.remove(repoName);
            markerFileContents.remove(repoName);
            pathCanonicalizer.clearPrefix(repoPath);
          }
        } finally {
          lock.unlock();
        }
      }
      // Fall back to the native file system if the repo has been materialized, deleted, or never
      // injected.
    }
    return nativeFs;
  }

  private void resetPathCanonicalizer() {
    pathCanonicalizer = PathCanonicalizer.createSelective(this::resolveOneLinkForCanonicalParent);
  }

  private PathCanonicalizer.Resolution resolveOneLinkForCanonicalParent(PathFragment path)
      throws IOException {
    FileSystem owner = fsForPath(path);
    FileStatus status = owner.statIfFound(path, /* followSymlinks= */ false);
    if (status == null) {
      throw new MissingPathException(path);
    }
    PathFragment targetPath = status.isSymbolicLink() ? owner.readSymbolicLink(path) : null;
    // The external directory and its ancestors are fixed for the duration of a command. Cache
    // them even though they reside in nativeFs so every repository access does not repeat the
    // native prefix walk. Native paths below the external directory remain mutable.
    boolean cacheable = owner == externalFs || externalDirectory.startsWith(path);
    return new PathCanonicalizer.Resolution(targetPath, cacheable);
  }

  /**
   * Routes a path through both backing file systems, reusing cached canonical prefixes.
   *
   * <p>The final status is loaded only if a caller requests it. Missing components leave the
   * canonical existing prefix and unresolved suffix intact so that creation operations can still
   * use the routed path. Other I/O errors are propagated.
   */
  private RoutedPath routeAcrossFileSystems(PathFragment path, FollowMode followMode)
      throws IOException {
    return switch (followMode) {
      case FOLLOW_NONE -> {
        FileSystem owner = fsForPath(path);
        yield new RoutedPath(owner, path);
      }
      case FOLLOW_PARENT -> {
        PathFragment parent = path.getParentDirectory();
        if (parent == null) {
          yield routeAcrossFileSystems(path, FollowMode.FOLLOW_NONE);
        }
        RoutedPath routedParent = routeAcrossFileSystems(parent, FollowMode.FOLLOW_ALL);
        PathFragment canonicalPath = routedParent.canonicalPath().getChild(path.getBaseName());
        FileSystem owner = fsForPath(canonicalPath);
        FileStatus parentStatus = routedParent.status();
        yield parentStatus != null && parentStatus.isDirectory()
            ? new RoutedPath(owner, canonicalPath)
            : new RoutedPath(owner, canonicalPath, /* status= */ null);
      }
      case FOLLOW_ALL -> routeFollowingSymlinks(path);
    };
  }

  private RoutedPath routeFollowingSymlinks(PathFragment path) throws IOException {
    try {
      PathFragment canonicalPath = pathCanonicalizer.resolveSymbolicLinks(path);
      FileSystem owner = fsForPath(canonicalPath);
      return new RoutedPath(owner, canonicalPath);
    } catch (MissingPathException e) {
      PathFragment parent = path.getParentDirectory();
      if (parent == null) {
        return routeAcrossFileSystems(path, FollowMode.FOLLOW_NONE);
      }
      RoutedPath routedParent = routeAcrossFileSystems(parent, FollowMode.FOLLOW_ALL);
      PathFragment canonicalPath = routedParent.canonicalPath().getChild(path.getBaseName());
      FileSystem owner = fsForPath(canonicalPath);
      if (routedParent.status() == null || !routedParent.status().isDirectory()) {
        return new RoutedPath(owner, canonicalPath, /* status= */ null);
      }
      FileStatus status = owner.statIfFound(canonicalPath, /* followSymlinks= */ false);
      if (status == null || !status.isSymbolicLink()) {
        return new RoutedPath(owner, canonicalPath, status);
      }
      PathFragment target = owner.readSymbolicLink(canonicalPath);
      if (!target.isAbsolute()) {
        target = canonicalPath.getParentDirectory().getRelative(target);
      }
      return routeAcrossFileSystems(target, FollowMode.FOLLOW_ALL);
    }
  }

  private boolean requiresCrossFileSystemRouting(PathFragment path) {
    return path.startsWith(externalDirectory);
  }

  private RoutedPath route(PathFragment path, FollowMode followMode) throws IOException {
    return requiresCrossFileSystemRouting(path)
        ? routeAcrossFileSystems(path, followMode)
        : new RoutedPath(nativeFs, path);
  }

  private static FollowMode followMode(boolean followSymlinks) {
    return followSymlinks ? FollowMode.FOLLOW_ALL : FollowMode.FOLLOW_PARENT;
  }

  private static FileStatus statOrThrow(RoutedPath routedPath) throws IOException {
    FileStatus status = routedPath.status();
    return status != null
        ? status
        : routedPath.owner().stat(routedPath.canonicalPath(), /* followSymlinks= */ false);
  }

  private void clearCanonicalization(PathFragment path, RoutedPath routedPath) {
    pathCanonicalizer.clearPrefix(path);
    if (!routedPath.canonicalPath().equals(path)) {
      pathCanonicalizer.clearPrefix(routedPath.canonicalPath());
    }
  }

  private <T> T runRoutedOperation(
      PathFragment path,
      FollowMode followMode,
      FileSystemOperation<T> directOperation,
      RoutedPathOperation<T> routedOperation)
      throws IOException {
    if (!requiresCrossFileSystemRouting(path)) {
      return directOperation.run(nativeFs, path);
    }
    return routedOperation.run(routeAcrossFileSystems(path, followMode));
  }

  private <T> T runRoutedOperation(
      PathFragment path, FollowMode followMode, FileSystemOperation<T> operation)
      throws IOException {
    return runRoutedOperation(
        path,
        followMode,
        operation,
        routedPath -> operation.run(routedPath.owner(), routedPath.canonicalPath()));
  }

  private <T> T runScopedQuery(
      PathFragment path, FileSystemQuery<T> directQuery, RoutedQuery<T> routedQuery) {
    return requiresCrossFileSystemRouting(path)
        ? routedQuery.run()
        : directQuery.run(nativeFs, path);
  }

  private boolean queryRoutedCapability(
      PathFragment path, FollowMode followMode, FileSystemQuery<Boolean> query) {
    if (!requiresCrossFileSystemRouting(path)) {
      return query.run(nativeFs, path);
    }
    try {
      RoutedPath routedPath = routeAcrossFileSystems(path, followMode);
      return query.run(routedPath.owner(), routedPath.canonicalPath());
    } catch (IOException e) {
      return false;
    }
  }

  @Nullable
  private FileStatus statNullableAcrossFileSystems(PathFragment path, boolean followSymlinks) {
    try {
      return routeAcrossFileSystems(path, followMode(followSymlinks)).status();
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private FileStatus statIfFoundInternal(PathFragment path, boolean followSymlinks)
      throws IOException {
    return runRoutedOperation(
        path,
        followMode(followSymlinks),
        (fileSystem, directPath) -> fileSystem.statIfFound(directPath, followSymlinks),
        RoutedPath::status);
  }

  @Override
  public boolean delete(PathFragment path) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(path, routedPath);
    return routedPath.owner().delete(routedPath.canonicalPath());
  }

  @Override
  public byte[] getDigest(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.getDigest(canonicalPath));
  }

  @Nullable
  @Override
  public byte[] getFastDigest(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.getFastDigest(canonicalPath));
  }

  @Override
  public boolean supportsModifications(PathFragment path) {
    return queryRoutedCapability(path, FollowMode.FOLLOW_ALL, FileSystem::supportsModifications);
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return queryRoutedCapability(
        path, FollowMode.FOLLOW_PARENT, FileSystem::supportsSymbolicLinksNatively);
  }

  @Override
  public boolean supportsHardLinksNatively(PathFragment path) {
    return queryRoutedCapability(
        path, FollowMode.FOLLOW_PARENT, FileSystem::supportsHardLinksNatively);
  }

  @Override
  public boolean mayBeCaseOrNormalizationInsensitive() {
    return fsForPath(externalDirectory).mayBeCaseOrNormalizationInsensitive();
  }

  @Override
  public boolean createDirectory(PathFragment path) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(path, routedPath);
    return routedPath.owner().createDirectory(routedPath.canonicalPath());
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    clearCanonicalization(path, routedPath);
    routedPath.owner().createDirectoryAndParents(routedPath.canonicalPath());
  }

  @Override
  public long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    return runRoutedOperation(
        path,
        followMode(followSymlinks),
        (fileSystem, directPath) -> fileSystem.getFileSize(directPath, followSymlinks),
        routedPath -> statOrThrow(routedPath).getSize());
  }

  @Override
  public long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    return runRoutedOperation(
        path,
        followMode(followSymlinks),
        (fileSystem, directPath) -> fileSystem.getLastModifiedTime(directPath, followSymlinks),
        routedPath -> statOrThrow(routedPath).getLastModifiedTime());
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    routedPath.owner().setLastModifiedTime(routedPath.canonicalPath(), newTime);
  }

  @Override
  public FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    return runRoutedOperation(
        path,
        followMode(followSymlinks),
        (fileSystem, directPath) -> fileSystem.stat(directPath, followSymlinks),
        RemoteExternalOverlayFileSystem::statOrThrow);
  }

  @Override
  public void createSymbolicLink(
      PathFragment linkPath, PathFragment targetFragment, SymlinkTargetType hint)
      throws IOException {
    RoutedPath routedPath = route(linkPath, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(linkPath, routedPath);
    routedPath.owner().createSymbolicLink(routedPath.canonicalPath(), targetFragment, hint);
  }

  @Override
  public PathFragment readSymbolicLink(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_PARENT,
        (fileSystem, canonicalPath) -> fileSystem.readSymbolicLink(canonicalPath));
  }

  @Override
  public boolean exists(PathFragment path, boolean followSymlinks) {
    return runScopedQuery(
        path,
        (fileSystem, directPath) -> fileSystem.exists(directPath, followSymlinks),
        () -> statNullableAcrossFileSystems(path, followSymlinks) != null);
  }

  @Override
  public boolean exists(PathFragment path) {
    return exists(path, /* followSymlinks= */ true);
  }

  @Override
  public Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.getDirectoryEntries(canonicalPath));
  }

  @Override
  public boolean isReadable(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.isReadable(canonicalPath));
  }

  @Override
  public void setReadable(PathFragment path, boolean readable) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    routedPath.owner().setReadable(routedPath.canonicalPath(), readable);
  }

  @Override
  public boolean isWritable(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.isWritable(canonicalPath));
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    routedPath.owner().setWritable(routedPath.canonicalPath(), writable);
  }

  @Override
  public boolean isExecutable(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.isExecutable(canonicalPath));
  }

  @Override
  public void setExecutable(PathFragment path, boolean executable) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    routedPath.owner().setExecutable(routedPath.canonicalPath(), executable);
  }

  @Override
  public InputStream getInputStream(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, canonicalPath) -> fileSystem.getInputStream(canonicalPath));
  }

  @Override
  public SeekableByteChannel createReadWriteByteChannel(PathFragment path) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    return routedPath.owner().createReadWriteByteChannel(routedPath.canonicalPath());
  }

  @Override
  public OutputStream getOutputStream(PathFragment path, boolean append, boolean internal)
      throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    return routedPath.owner().getOutputStream(routedPath.canonicalPath(), append, internal);
  }

  @Override
  public void renameTo(PathFragment sourcePath, PathFragment targetPath) throws IOException {
    RoutedPath routedSource = route(sourcePath, FollowMode.FOLLOW_PARENT);
    RoutedPath routedTarget = route(targetPath, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(sourcePath, routedSource);
    clearCanonicalization(targetPath, routedTarget);
    if (routedSource.owner() != routedTarget.owner()) {
      throw new IOException(
          "Cannot rename across file systems: " + sourcePath + " to " + targetPath);
    }
    routedSource.owner().renameTo(routedSource.canonicalPath(), routedTarget.canonicalPath());
  }

  @Override
  public void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    RoutedPath routedLink = route(linkPath, FollowMode.FOLLOW_PARENT);
    RoutedPath routedOriginal = route(originalPath, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(linkPath, routedLink);
    if (routedLink.owner() != routedOriginal.owner()) {
      throw new IOException(
          "Cannot create hard link across file systems: " + linkPath + " to " + originalPath);
    }
    routedOriginal
        .owner()
        .createFSDependentHardLink(routedLink.canonicalPath(), routedOriginal.canonicalPath());
  }

  @Override
  public File getIoFile(PathFragment path) {
    return fsForPath(path).getIoFile(path);
  }

  @Override
  public java.nio.file.Path getNioPath(PathFragment path) {
    return fsForPath(path).getNioPath(path);
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    return runScopedQuery(
        path,
        FileSystem::getFileSystemType,
        () -> {
          try {
            RoutedPath routedPath = routeAcrossFileSystems(path, FollowMode.FOLLOW_ALL);
            return routedPath.owner().getFileSystemType(routedPath.canonicalPath());
          } catch (IOException e) {
            return "unknown";
          }
        });
  }

  @Override
  public byte[] getxattr(PathFragment path, String name, boolean followSymlinks)
      throws IOException {
    return runRoutedOperation(
        path,
        followMode(followSymlinks),
        (fileSystem, directPath) -> fileSystem.getxattr(directPath, name, followSymlinks),
        routedPath ->
            routedPath
                .owner()
                .getxattr(routedPath.canonicalPath(), name, /* followSymlinks= */ false));
  }

  @Nullable
  @Override
  public PathFragment resolveOneLink(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_PARENT,
        FileSystem::resolveOneLink,
        routedPath -> {
          FileStatus status = statOrThrow(routedPath);
          return status.isSymbolicLink()
              ? routedPath.owner().readSymbolicLink(routedPath.canonicalPath())
              : null;
        });
  }

  @Override
  public Path resolveSymbolicLinks(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, directPath) ->
            getPath(fileSystem.resolveSymbolicLinks(directPath).asFragment()),
        routedPath -> {
          statOrThrow(routedPath);
          return getPath(routedPath.canonicalPath());
        });
  }

  @Nullable
  @Override
  public FileStatus statNullable(PathFragment path, boolean followSymlinks) {
    return runScopedQuery(
        path,
        (fileSystem, directPath) -> fileSystem.statNullable(directPath, followSymlinks),
        () -> statNullableAcrossFileSystems(path, followSymlinks));
  }

  @Nullable
  @Override
  public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    return statIfFoundInternal(path, followSymlinks);
  }

  @Override
  public boolean isFile(PathFragment path, boolean followSymlinks) {
    return runScopedQuery(
        path,
        (fileSystem, directPath) -> fileSystem.isFile(directPath, followSymlinks),
        () -> {
          FileStatus status = statNullableAcrossFileSystems(path, followSymlinks);
          return status != null && status.isFile();
        });
  }

  @Override
  public boolean isSpecialFile(PathFragment path, boolean followSymlinks) {
    return runScopedQuery(
        path,
        (fileSystem, directPath) -> fileSystem.isSpecialFile(directPath, followSymlinks),
        () -> {
          FileStatus status = statNullableAcrossFileSystems(path, followSymlinks);
          return status != null && status.isSpecialFile();
        });
  }

  @Override
  public boolean isSymbolicLink(PathFragment path) {
    return runScopedQuery(
        path,
        FileSystem::isSymbolicLink,
        () -> {
          FileStatus status = statNullableAcrossFileSystems(path, /* followSymlinks= */ false);
          return status != null && status.isSymbolicLink();
        });
  }

  @Override
  public boolean isDirectory(PathFragment path, boolean followSymlinks) {
    return runScopedQuery(
        path,
        (fileSystem, directPath) -> fileSystem.isDirectory(directPath, followSymlinks),
        () -> {
          FileStatus status = statNullableAcrossFileSystems(path, followSymlinks);
          return status != null && status.isDirectory();
        });
  }

  @Override
  public PathFragment readSymbolicLinkUnchecked(PathFragment path) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_PARENT,
        (fileSystem, canonicalPath) -> fileSystem.readSymbolicLinkUnchecked(canonicalPath));
  }

  @Override
  public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks) throws IOException {
    return runRoutedOperation(
        path,
        FollowMode.FOLLOW_ALL,
        (fileSystem, directPath) -> fileSystem.readdir(directPath, followSymlinks),
        routedPath -> {
          Collection<Dirent> dirents =
              routedPath.owner().readdir(routedPath.canonicalPath(), /* followSymlinks= */ false);
          return followDirentSymlinks(routedPath.canonicalPath(), dirents, followSymlinks);
        });
  }

  private Collection<Dirent> followDirentSymlinks(
      PathFragment directoryPath, Collection<Dirent> dirents, boolean followSymlinks) {
    if (!followSymlinks) {
      return dirents;
    }
    List<Dirent> followedDirents = Lists.newArrayListWithCapacity(dirents.size());
    for (Dirent dirent : dirents) {
      if (dirent.getType() != Dirent.Type.SYMLINK) {
        followedDirents.add(dirent);
        continue;
      }
      FileStatus status;
      try {
        status =
            routeAcrossFileSystems(directoryPath.getChild(dirent.getName()), FollowMode.FOLLOW_ALL)
                .status();
      } catch (IOException e) {
        status = null;
      }
      followedDirents.add(new Dirent(dirent.getName(), direntFromStat(status)));
    }
    return followedDirents;
  }

  @Override
  public void chmod(PathFragment path, int mode) throws IOException {
    RoutedPath routedPath = route(path, FollowMode.FOLLOW_ALL);
    routedPath.owner().chmod(routedPath.canonicalPath(), mode);
  }

  @Override
  public void createHardLink(PathFragment linkPath, PathFragment originalPath) throws IOException {
    RoutedPath routedLink = route(linkPath, FollowMode.FOLLOW_PARENT);
    RoutedPath routedOriginal = route(originalPath, FollowMode.FOLLOW_PARENT);
    clearCanonicalization(linkPath, routedLink);
    if (routedLink.owner() != routedOriginal.owner()) {
      throw new IOException(
          "Cannot create hard link across file systems: " + linkPath + " to " + originalPath);
    }
    routedLink.owner().createHardLink(routedLink.canonicalPath(), routedOriginal.canonicalPath());
  }

  @Override
  public void prefetchPackageAsync(PathFragment path, int maxDirs) {
    fsForPath(path).prefetchPackageAsync(path, maxDirs);
  }

  @Override
  public PathFragment createTempDirectory(PathFragment parent, String prefix) throws IOException {
    RoutedPath routedParent = route(parent, FollowMode.FOLLOW_ALL);
    PathFragment created =
        routedParent.owner().createTempDirectory(routedParent.canonicalPath(), prefix);
    return parent.getChild(created.getBaseName());
  }

  private final class RemoteExternalFileSystem
      extends RemoteActionFileSystem.RemoteInMemoryFileSystem {

    RemoteExternalFileSystem(DigestHashFunction hashFunction) {
      super(hashFunction);
    }

    private RemoteActionExecutionContext makeRemoteContext(PathFragment relativePath) {
      String repoName = relativePath.subFragment(0, 1).getBaseName();
      var metadata = TracingMetadataUtils.buildMetadata(buildRequestId, commandId, repoName);
      // Files in the remote external repo that Bazel reads are worth writing through to the
      // disk cache, as they are likely to be read again on future cold builds.
      return RemoteActionExecutionContext.create(metadata)
          .withReadCachePolicy(RemoteActionExecutionContext.CachePolicy.ANY_CACHE)
          .withWriteCachePolicy(RemoteActionExecutionContext.CachePolicy.ANY_CACHE);
    }

    private FileArtifactValue getMetadata(PathFragment path) throws IOException {
      var status = stat(path, /* followSymlinks= */ false);
      if (!status.isSymbolicLink()) {
        return ((RemoteActionFileSystem.RemoteInMemoryFileInfo) status).getMetadata();
      }
      return FileArtifactValue.createForUnresolvedSymlink(externalFs.getPath(path));
    }

    @Override
    public synchronized InputStream getInputStream(PathFragment path) throws IOException {
      // .bzl and REPO.bazel files are prefetched to the native file system during injection, but
      // only if they are regular files, a symlink with such a name is kept in the in-memory overlay
      // only. We thus need to follow symlinks before attempting to read a supposedly prefetched
      // file.
      path = resolveSymbolicLinks(path).asFragment();
      if (shouldPrefetch(path)) {
        return nativeFs.getInputStream(path);
      }
      var relativePath = path.relativeTo(externalDirectory);
      var info =
          (RemoteActionFileSystem.RemoteInMemoryFileInfo) stat(path, /* followSymlinks= */ true);
      reporter.post(
          new ExtendedEventHandler.FetchProgress() {
            @Override
            public String getResourceIdentifier() {
              return relativePath.getPathString();
            }

            @Override
            public String getProgress() {
              return "(%s)".formatted(bytesCountToDisplayString(info.getSize()));
            }

            @Override
            public boolean isFinished() {
              return false;
            }
          });
      var digest = DigestUtil.buildDigest(info.getMetadata().getDigest(), info.getSize());
      try {
        var contentFuture =
            cache.downloadBlob(
                makeRemoteContext(relativePath),
                path.getPathString(),
                /* execPath= */ null,
                digest);
        waitForBulkTransfer(ImmutableList.of(contentFuture));
        return new ByteArrayInputStream(contentFuture.get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted while waiting for remote file transfer");
      } catch (BulkTransferException e) {
        if (e.allCausedByCacheNotFoundException()) {
          reposWithLostFiles.add(relativePath.getSegment(0));
          throw new DetailedIOException(
              "%s/%s with digest %s is no longer available in the remote cache"
                  .formatted(
                      externalDirectory.getBaseName(), relativePath, DigestUtil.toString(digest)),
              e,
              FailureDetails.Filesystem.Code.REMOTE_FILE_EVICTED,
              SkyFunctionException.Transience.TRANSIENT);
        }
        throw e;
      } catch (ExecutionException e) {
        throw new IllegalStateException("waitForBulkTransfer should have thrown", e);
      } finally {
        reporter.post(
            new ExtendedEventHandler.FetchProgress() {
              @Override
              public String getResourceIdentifier() {
                return relativePath.getPathString();
              }

              @Override
              public String getProgress() {
                return "";
              }

              @Override
              public boolean isFinished() {
                return true;
              }
            });
      }
    }

    @Override
    public byte[] getDigest(PathFragment path) throws IOException {
      var info =
          (RemoteActionFileSystem.RemoteInMemoryFileInfo) stat(path, /* followSymlinks= */ true);
      return info.getMetadata().getDigest();
    }

    @Override
    public synchronized byte[] getFastDigest(PathFragment path) throws IOException {
      return getDigest(path);
    }
  }
}
