// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventBusEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteActionInputFetcher}. */
@RunWith(JUnit4.class)
public class RemoteActionInputFetcherTest extends ActionInputPrefetcherTestBase {
  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());

  private DigestUtil digestUtil;

  @Override
  public void setUp() throws IOException {
    super.setUp();
    Path dev = fs.getPath("/dev");
    dev.createDirectory();
    dev.setWritable(false);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION);
  }

  @Override
  protected AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas) {
    CombinedCache combinedCache = newCombinedCache(digestUtil, cas);
    return new RemoteActionInputFetcher(
        new Reporter(new EventBusEventHandler(eventBus)),
        "none",
        "none",
        combinedCache,
        execRoot,
        tempPathGenerator,
        DUMMY_REMOTE_OUTPUT_CHECKER,
        ActionOutputDirectoryHelper.createForTesting(),
        OutputPermissions.READONLY);
  }

  @Test
  public void testStagingVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(EventBusEventHandler.createWithNewEventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);
    VirtualActionInput a = ActionsTestUtil.createVirtualActionInput("file1", "hello world");

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(a),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert
    Path p = execRoot.getRelative(a.getExecPath());
    assertThat(FileSystemUtils.readContent(p, StandardCharsets.UTF_8)).isEqualTo("hello world");
    assertThat(p.isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingEmptyVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(EventBusEventHandler.createWithNewEventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(VirtualActionInput.EMPTY_MARKER),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert that nothing happened
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_missingFiles_failsWithSpecificMessage() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    var error =
        assertThrows(
            BulkTransferException.class,
            () ->
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(a),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    var m = metadata.get(a);
    var digest = DigestUtil.buildDigest(m.getDigest(), m.getSize());
    assertThat(error)
        .hasMessageThat()
        .contains(String.format("%s/%s", digest.getHash(), digest.getSizeBytes()));
  }

  @Test
  public void prefetchFiles_overlaySymlinkReplacedOnHost_repairsSymlink() throws Exception {
    PathFragment externalDirectory = PathFragment.create("/external");
    var overlay = new RemoteExternalOverlayFileSystem(externalDirectory, fs);
    byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
    HashCode hash = HASH_FUNCTION.getHashFunction().hashBytes(contents);
    Map<HashCode, byte[]> cas = new HashMap<>();
    cas.put(hash, contents);
    CombinedCache overlayCache = newCombinedCache(digestUtil, new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);
    overlay.beforeCommand(
        overlayCache,
        prefetcher,
        /* reporter= */ null,
        "build-request-id",
        "command-id",
        /* evaluator= */ null,
        Duration.ofHours(1));
    try {
      Digest digest = digestUtil.compute(contents);
      Directory repoDirectory =
          Directory.newBuilder()
              .addFiles(FileNode.newBuilder().setName("target").setDigest(digest))
              .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("target"))
              .build();
      assertThat(
              overlay.injectRemoteRepo(
                  RepositoryName.createUnvalidated("cached"),
                  Tree.newBuilder()
                      .setRoot(
                          Directory.newBuilder()
                              .addDirectories(
                                  DirectoryNode.newBuilder()
                                      .setName("dir")
                                      .setDigest(digestUtil.compute(repoDirectory))))
                      .addChildren(repoDirectory)
                      .build(),
                  "marker"))
          .isTrue();

      ArtifactRoot repoRoot =
          ArtifactRoot.asDerivedRoot(
              overlay.getPath("/external"), RootType.OUTPUT, /* rootDir= */ "cached");
      Artifact input = ActionsTestUtil.createArtifact(repoRoot, "dir/link");
      FileArtifactValue metadata =
          FileArtifactValue.createForRemoteFileWithMaterializationData(
              hash.asBytes(),
              contents.length,
              /* locationIndex= */ 1,
              /* expirationTime= */ null,
              /* inMemoryOutput= */ false);
      Path hostPath = input.getPath().forHostFileSystem();
      Path hostParent = hostPath.getParentDirectory();
      Path outsideDirectory = fs.getPath("/outside");
      Path outsideTarget = outsideDirectory.getChild("target");
      Path outsideSentinel = outsideDirectory.getChild("sentinel");
      outsideDirectory.createDirectoryAndParents();
      FileSystemUtils.writeContent(
          outsideTarget, "outside target".getBytes(StandardCharsets.UTF_8));
      FileSystemUtils.writeContent(outsideSentinel, "sentinel".getBytes(StandardCharsets.UTF_8));
      hostParent.deleteTree();
      hostParent.createSymbolicLink(outsideDirectory.asFragment());

      wait(
          prefetcher.prefetchFilesInterruptibly(
              action, ImmutableList.of(input), unused -> metadata, Priority.MEDIUM, Reason.INPUTS));
      assertThat(hostPath.readSymbolicLink()).isEqualTo(PathFragment.create("target"));
      assertThat(FileSystemUtils.readContent(hostPath, StandardCharsets.UTF_8))
          .isEqualTo("contents");
      assertThat(FileSystemUtils.readContent(outsideTarget, StandardCharsets.UTF_8))
          .isEqualTo("outside target");
      assertThat(FileSystemUtils.readContent(outsideSentinel, StandardCharsets.UTF_8))
          .isEqualTo("sentinel");

      hostPath.delete();
      FileSystemUtils.writeContent(hostPath, "stale".getBytes(StandardCharsets.UTF_8));

      wait(
          prefetcher.prefetchFilesInterruptibly(
              action, ImmutableList.of(input), unused -> metadata, Priority.MEDIUM, Reason.INPUTS));

      assertThat(hostPath.isSymbolicLink()).isTrue();
      assertThat(hostPath.readSymbolicLink()).isEqualTo(PathFragment.create("target"));
      assertThat(FileSystemUtils.readContent(hostPath, StandardCharsets.UTF_8))
          .isEqualTo("contents");

      hostPath.delete();
      hostPath.createDirectory();
      FileSystemUtils.writeContent(
          hostPath.getChild("stale-child"), "stale".getBytes(StandardCharsets.UTF_8));

      wait(
          prefetcher.prefetchFilesInterruptibly(
              action, ImmutableList.of(input), unused -> metadata, Priority.MEDIUM, Reason.INPUTS));

      assertThat(hostPath.isSymbolicLink()).isTrue();
      assertThat(hostPath.readSymbolicLink()).isEqualTo(PathFragment.create("target"));
      assertThat(FileSystemUtils.readContent(hostPath, StandardCharsets.UTF_8))
          .isEqualTo("contents");

      // The target download is already in downloadCache. Replacing its parent must repair the
      // route before that cache is consulted and force the missing target to be downloaded again.
      hostParent.deleteTree();
      hostParent.createSymbolicLink(outsideDirectory.asFragment());

      wait(
          prefetcher.prefetchFilesInterruptibly(
              action, ImmutableList.of(input), unused -> metadata, Priority.MEDIUM, Reason.INPUTS));

      assertThat(hostParent.isDirectory(Symlinks.NOFOLLOW)).isTrue();
      assertThat(hostPath.isSymbolicLink()).isTrue();
      assertThat(hostPath.readSymbolicLink()).isEqualTo(PathFragment.create("target"));
      assertThat(FileSystemUtils.readContent(hostPath, StandardCharsets.UTF_8))
          .isEqualTo("contents");
      assertThat(FileSystemUtils.readContent(outsideTarget, StandardCharsets.UTF_8))
          .isEqualTo("outside target");
      assertThat(FileSystemUtils.readContent(outsideSentinel, StandardCharsets.UTF_8))
          .isEqualTo("sentinel");
      assertThat(outsideDirectory.getDirectoryEntries())
          .containsExactly(outsideTarget, outsideSentinel);
    } finally {
      overlay.afterCommand();
      overlayCache.release();
    }
  }

  @Test
  public void prefetchFiles_overlayDirectoryLink_materializesEveryLinkComponent() throws Exception {
    PathFragment externalDirectory = PathFragment.create("/external");
    var overlay = new RemoteExternalOverlayFileSystem(externalDirectory, fs);
    CombinedCache overlayCache = newCombinedCache(digestUtil, new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());
    overlay.beforeCommand(
        overlayCache,
        prefetcher,
        /* reporter= */ null,
        "build-request-id",
        "command-id",
        /* evaluator= */ null,
        Duration.ofHours(1));
    try {
      Path nativeDirectory = fs.getPath("/external/native/dir");
      nativeDirectory.createDirectoryAndParents();
      FileSystemUtils.writeContent(
          nativeDirectory.getChild("child"), "contents".getBytes(StandardCharsets.UTF_8));
      nativeDirectory.getChild("child-link").createSymbolicLink(PathFragment.create("child"));

      assertThat(
              overlay.injectRemoteRepo(
                  RepositoryName.createUnvalidated("cached"),
                  Tree.newBuilder()
                      .setRoot(
                          Directory.newBuilder()
                              .addSymlinks(
                                  SymlinkNode.newBuilder()
                                      .setName("dirlink")
                                      .setTarget(nativeDirectory.getPathString())))
                      .build(),
                  "marker"))
          .isTrue();

      ArtifactRoot repoRoot =
          ArtifactRoot.asDerivedRoot(
              overlay.getPath("/external"), RootType.OUTPUT, /* rootDir= */ "cached");
      Artifact child = ActionsTestUtil.createArtifact(repoRoot, "dirlink/child");
      Artifact childLink = ActionsTestUtil.createArtifact(repoRoot, "dirlink/child-link");
      byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
      FileArtifactValue metadata =
          FileArtifactValue.createForNormalFile(
              HASH_FUNCTION.getHashFunction().hashBytes(contents).asBytes(),
              /* proxy= */ null,
              contents.length);

      wait(
          prefetcher.prefetchFilesInterruptibly(
              action,
              ImmutableList.of(child, childLink),
              unused -> metadata,
              Priority.MEDIUM,
              Reason.INPUTS));

      Path hostDirectoryLink = overlay.getPath("/external/cached/dirlink").forHostFileSystem();
      assertThat(hostDirectoryLink.isSymbolicLink()).isTrue();
      assertThat(hostDirectoryLink.readSymbolicLink()).isEqualTo(nativeDirectory.asFragment());
      assertThat(
              FileSystemUtils.readContent(
                  hostDirectoryLink.getChild("child"), StandardCharsets.UTF_8))
          .isEqualTo("contents");
      Path hostChildLink = hostDirectoryLink.getChild("child-link");
      assertThat(hostChildLink.isSymbolicLink()).isTrue();
      assertThat(hostChildLink.readSymbolicLink()).isEqualTo(PathFragment.create("child"));
      assertThat(FileSystemUtils.readContent(hostChildLink, StandardCharsets.UTF_8))
          .isEqualTo("contents");
    } finally {
      overlay.afterCommand();
      overlayCache.release();
    }
  }

  @Test
  public void prefetchFiles_outputSymlinkReplacedByNonEmptyDirectory_doesNotDeleteContents()
      throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    PathFragment target = artifactRoot.getRoot().asPath().getChild("target").asFragment();
    Artifact input = createRemoteArtifact("link", "contents", target, metadata, cas);
    Path staleChild = input.getPath().getChild("child");
    staleChild.getParentDirectory().createDirectory();
    FileSystemUtils.writeContent(staleChild, "stale".getBytes(StandardCharsets.UTF_8));

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    assertThrows(
        IOException.class,
        () ->
            wait(
                prefetcher.prefetchFilesInterruptibly(
                    action,
                    ImmutableList.of(input),
                    metadata::get,
                    Priority.MEDIUM,
                    Reason.INPUTS)));

    assertThat(input.getPath().isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(FileSystemUtils.readContent(staleChild, StandardCharsets.UTF_8)).isEqualTo("stale");
    verify(fs).delete(input.getPath().asFragment());
    verify(fs, never()).deleteTree(input.getPath().asFragment());
  }

  private CombinedCache newCombinedCache(DigestUtil digestUtil, Map<HashCode, byte[]> cas) {
    Map<Digest, byte[]> cacheEntries = Maps.newHashMapWithExpectedSize(cas.size());
    for (Map.Entry<HashCode, byte[]> entry : cas.entrySet()) {
      cacheEntries.put(
          DigestUtil.buildDigest(entry.getKey().asBytes(), entry.getValue().length),
          entry.getValue());
    }
    return new CombinedCache(
        new InMemoryCacheClient(cacheEntries),
        /* diskCacheClient= */ null,
        /* symlinkTemplate= */ null,
        digestUtil,
        /* chunkingEnabled= */ false);
  }
}
