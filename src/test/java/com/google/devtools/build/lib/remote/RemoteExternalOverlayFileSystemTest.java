// Copyright 2026 The Bazel Authors. All rights reserved.
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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.testing.vfs.SpiedFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.OutputStream;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteExternalOverlayFileSystem}. */
@RunWith(JUnit4.class)
public final class RemoteExternalOverlayFileSystemTest {
  private final PathFragment externalDirectory = PathFragment.create("/external");
  private final DigestUtil digestUtil =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
  private final InMemoryFileSystem nativeFs = new InMemoryFileSystem(DigestHashFunction.SHA256);

  private CombinedCache cache;
  private RemoteExternalOverlayFileSystem overlay;

  @Before
  public void setUp() {
    cache =
        new CombinedCache(
            new InMemoryCacheClient(),
            /* diskCacheClient= */ null,
            /* symlinkTemplate= */ null,
            digestUtil,
            /* chunkingEnabled= */ false);
    overlay = createOverlay(nativeFs);
  }

  private RemoteExternalOverlayFileSystem createOverlay(FileSystem nativeFileSystem) {
    return createOverlay(externalDirectory, nativeFileSystem);
  }

  private RemoteExternalOverlayFileSystem createOverlay(
      PathFragment scopedExternalDirectory, FileSystem nativeFileSystem) {
    var inputPrefetcher = mock(AbstractActionInputPrefetcher.class);
    when(inputPrefetcher.prefetchFilesInterruptibly(any(), any(), any(), any(), any()))
        .thenReturn(immediateVoidFuture());
    var newOverlay = new RemoteExternalOverlayFileSystem(scopedExternalDirectory, nativeFileSystem);
    newOverlay.beforeCommand(
        cache,
        inputPrefetcher,
        /* reporter= */ null,
        "build-request-id",
        "command-id",
        /* evaluator= */ null,
        Duration.ofHours(1));
    return newOverlay;
  }

  @After
  public void tearDown() {
    overlay.afterCommand();
    cache.release();
  }

  private void injectCachedRepo(Tree remoteContents) throws Exception {
    assertThat(
            overlay.injectRemoteRepo(
                RepositoryName.createUnvalidated("cached"), remoteContents, "marker"))
        .isTrue();
  }

  private PathFragment injectCachedLinkToNativeDirectory() throws Exception {
    PathFragment nativeDir = externalDirectory.getRelative("native/dir");
    nativeFs.createDirectoryAndParents(nativeDir);
    FileSystemUtils.writeContent(
        nativeFs.getPath(nativeDir.getRelative("child")), new byte[] {1, 2, 3});
    nativeFs.createSymbolicLink(
        nativeDir.getRelative("child-link"), externalDirectory.getRelative("cached/plain"));

    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0])))
                    .addSymlinks(
                        SymlinkNode.newBuilder()
                            .setName("dirlink")
                            .setTarget(nativeDir.getPathString())))
            .build());
    return externalDirectory.getRelative("cached/dirlink");
  }

  @Test
  public void statIfFound_outsideExternalDirectory_delegatesDirectly() throws Exception {
    SpiedFileSystem nativeSpy = SpiedFileSystem.createInMemorySpy();
    PathFragment path = PathFragment.create("/workspace/a/b/file");
    nativeSpy.createDirectoryAndParents(path.getParentDirectory());
    FileSystemUtils.writeContent(nativeSpy.getPath(path), new byte[0]);
    var scopedOverlay = new RemoteExternalOverlayFileSystem(externalDirectory, nativeSpy);
    clearInvocations(nativeSpy);

    assertThat(scopedOverlay.statIfFound(path, /* followSymlinks= */ true)).isNotNull();

    verify(nativeSpy).statIfFound(path, /* followSymlinks= */ true);
    verify(nativeSpy, times(1)).statIfFound(any(), anyBoolean());
  }

  @Test
  public void readdir_outsideExternalDirectory_delegatesDirectly() throws Exception {
    SpiedFileSystem nativeSpy = SpiedFileSystem.createInMemorySpy();
    PathFragment directory = PathFragment.create("/workspace/a/b");
    nativeSpy.createDirectoryAndParents(directory);
    FileSystemUtils.writeContent(nativeSpy.getPath(directory.getRelative("file")), new byte[0]);
    var scopedOverlay = new RemoteExternalOverlayFileSystem(externalDirectory, nativeSpy);
    clearInvocations(nativeSpy);

    assertThat(scopedOverlay.readdir(directory, /* followSymlinks= */ true))
        .containsExactly(new Dirent("file", Dirent.Type.FILE));

    verify(nativeSpy).readdir(directory, /* followSymlinks= */ true);
    verify(nativeSpy, times(1)).readdir(any(), anyBoolean());
    verify(nativeSpy, never()).statIfFound(any(), anyBoolean());
  }

  @Test
  public void createDirectory_outsideExternalDirectory_delegatesDirectly() throws Exception {
    SpiedFileSystem nativeSpy = SpiedFileSystem.createInMemorySpy();
    PathFragment parent = PathFragment.create("/workspace/a/b");
    nativeSpy.createDirectoryAndParents(parent);
    PathFragment path = parent.getRelative("created");
    var scopedOverlay = new RemoteExternalOverlayFileSystem(externalDirectory, nativeSpy);
    clearInvocations(nativeSpy);

    assertThat(scopedOverlay.createDirectory(path)).isTrue();

    verify(nativeSpy).createDirectory(path);
    verify(nativeSpy, times(1)).createDirectory(any());
    verify(nativeSpy, never()).statIfFound(any(), anyBoolean());
  }

  @Test
  public void repeatedInjectedRepoAccesses_cacheExternalDirectoryAncestry() throws Exception {
    PathFragment deepExternalDirectory = PathFragment.create("/output/base/external");
    SpiedFileSystem nativeSpy = SpiedFileSystem.createInMemorySpy();
    var scopedOverlay = createOverlay(deepExternalDirectory, nativeSpy);
    try {
      assertThat(
              scopedOverlay.injectRemoteRepo(
                  RepositoryName.createUnvalidated("cached"),
                  Tree.newBuilder()
                      .setRoot(
                          Directory.newBuilder()
                              .addFiles(
                                  FileNode.newBuilder()
                                      .setName("one")
                                      .setDigest(digestUtil.compute(new byte[0])))
                              .addFiles(
                                  FileNode.newBuilder()
                                      .setName("two")
                                      .setDigest(digestUtil.compute(new byte[0]))))
                      .build(),
                  "marker"))
          .isTrue();
      clearInvocations(nativeSpy);

      assertThat(
              scopedOverlay.statIfFound(
                  deepExternalDirectory.getRelative("cached/one"), /* followSymlinks= */ true))
          .isNotNull();
      assertThat(
              scopedOverlay.statIfFound(
                  deepExternalDirectory.getRelative("cached/two"), /* followSymlinks= */ true))
          .isNotNull();

      verify(nativeSpy).statIfFound(PathFragment.create("/output"), /* followSymlinks= */ false);
      verify(nativeSpy)
          .statIfFound(PathFragment.create("/output/base"), /* followSymlinks= */ false);
      verify(nativeSpy).statIfFound(deepExternalDirectory, /* followSymlinks= */ false);
      verify(nativeSpy, times(3)).statIfFound(any(), anyBoolean());
    } finally {
      scopedOverlay.afterCommand();
    }
  }

  @Test
  public void statIfFound_childOfFileInInjectedRepo_returnsNull() throws Exception {
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0])))
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("plain")))
            .build());

    assertThat(
            overlay.statIfFound(
                externalDirectory.getRelative("cached/plain/child"), /* followSymlinks= */ true))
        .isNull();
    assertThat(
            overlay.statIfFound(
                externalDirectory.getRelative("cached/link/child"), /* followSymlinks= */ true))
        .isNull();
  }

  @Test
  public void getFileSystemType_injectedFileUsesOwningFileSystem() throws Exception {
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0]))))
            .build());

    assertThat(overlay.getFileSystemType(externalDirectory.getRelative("cached/plain")))
        .isEqualTo("inmemoryfs");
  }

  @Test
  public void directoryLinkFromNativeToInjectedRepo_ignoresNativeShadow() throws Exception {
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0]))))
            .build());
    PathFragment nativeRepo = externalDirectory.getRelative("native");
    nativeFs.createDirectoryAndParents(nativeRepo);
    PathFragment link = nativeRepo.getRelative("link");
    nativeFs.createSymbolicLink(link, externalDirectory.getRelative("cached"));
    assertThat(nativeFs.getDirectoryEntries(externalDirectory.getRelative("cached"))).isEmpty();

    assertThat(overlay.getDirectoryEntries(link)).containsExactly("plain");
    assertThat(overlay.readdir(link, /* followSymlinks= */ false))
        .containsExactly(new Dirent("plain", Dirent.Type.FILE));
  }

  @Test
  public void statIfFound_nativeLinkToInjectedFile_ignoresSuccessfulNativeShadow()
      throws Exception {
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0]))))
            .build());
    PathFragment target = externalDirectory.getRelative("cached/plain");
    FileSystemUtils.writeContent(nativeFs.getPath(target), new byte[] {1, 2, 3});
    PathFragment link = externalDirectory.getRelative("native/link");
    nativeFs.createDirectoryAndParents(link.getParentDirectory());
    nativeFs.createSymbolicLink(link, target);
    assertThat(nativeFs.statIfFound(link, /* followSymlinks= */ true).getSize()).isEqualTo(3);

    var status = overlay.statIfFound(link, /* followSymlinks= */ true);

    assertThat(status).isNotNull();
    assertThat(status.isFile()).isTrue();
    assertThat(status.getSize()).isEqualTo(0);
    assertThat(overlay.getFileSize(link, /* followSymlinks= */ true)).isEqualTo(0);
  }

  @Test
  public void nativeMutationWithinCommand_doesNotReuseCanonicalPath() throws Exception {
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0]))))
            .build());
    PathFragment path = externalDirectory.getRelative("native/path");
    nativeFs.createDirectoryAndParents(path);
    assertThat(overlay.getDirectoryEntries(path)).isEmpty();

    assertThat(nativeFs.delete(path)).isTrue();
    nativeFs.createSymbolicLink(path, externalDirectory.getRelative("cached"));

    assertThat(overlay.getDirectoryEntries(path)).containsExactly("plain");
  }

  @Test
  public void reinjectAfterMaterialization_selectsNewInjectedContents() throws Exception {
    RepositoryName repo = RepositoryName.createUnvalidated("cached");
    assertThat(
            overlay.injectRemoteRepo(
                repo,
                Tree.newBuilder()
                    .setRoot(
                        Directory.newBuilder()
                            .addFiles(
                                FileNode.newBuilder()
                                    .setName("old")
                                    .setDigest(digestUtil.compute(new byte[0]))))
                    .build(),
                "old marker"))
        .isTrue();
    overlay.ensureMaterialized(repo, mock(ExtendedEventHandler.class));

    assertThat(
            overlay.injectRemoteRepo(
                repo,
                Tree.newBuilder()
                    .setRoot(
                        Directory.newBuilder()
                            .addFiles(
                                FileNode.newBuilder()
                                    .setName("new")
                                    .setDigest(digestUtil.compute(new byte[0]))))
                    .build(),
                "new marker"))
        .isTrue();

    assertThat(overlay.getDirectoryEntries(externalDirectory.getRelative("cached")))
        .containsExactly("new");
  }

  @Test
  public void routingCache_pathReplacedThroughSymlinkedParent_invalidatesCanonicalTarget()
      throws Exception {
    Directory emptyDirectory = Directory.getDefaultInstance();
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("entry")
                            .setDigest(digestUtil.compute(emptyDirectory))))
            .addChildren(emptyDirectory)
            .build());
    PathFragment aliases = externalDirectory.getRelative("aliases");
    nativeFs.createDirectoryAndParents(aliases);
    nativeFs.createSymbolicLink(
        aliases.getRelative("link"), externalDirectory.getRelative("cached"));
    PathFragment path = aliases.getRelative("link/entry");
    PathFragment nativeTarget = externalDirectory.getRelative("native/target");
    nativeFs.createDirectoryAndParents(nativeTarget);
    FileSystemUtils.writeContent(nativeFs.getPath(nativeTarget.getRelative("child")), new byte[0]);

    // Cache the injected canonical target as a non-symlink.
    assertThat(overlay.getDirectoryEntries(path)).isEmpty();

    assertThat(overlay.delete(path)).isTrue();
    overlay.createSymbolicLink(path, nativeTarget);

    assertThat(overlay.readSymbolicLink(path)).isEqualTo(nativeTarget);
    assertThat(overlay.resolveSymbolicLinks(path).asFragment()).isEqualTo(nativeTarget);
    assertThat(overlay.getDirectoryEntries(path)).containsExactly("child");
  }

  @Test
  public void statIfFound_relativeLinkBelowLinkedDirectory_crossesFileSystems() throws Exception {
    var inner =
        Directory.newBuilder()
            .addSymlinks(
                SymlinkNode.newBuilder().setName("cross").setTarget("../../../native/back"))
            .build();
    var deep =
        Directory.newBuilder()
            .addDirectories(
                DirectoryNode.newBuilder().setName("dir").setDigest(digestUtil.compute(inner)))
            .build();
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0])))
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("deep")
                            .setDigest(digestUtil.compute(deep)))
                    .addSymlinks(SymlinkNode.newBuilder().setName("alias").setTarget("deep/dir")))
            .addChildren(deep)
            .addChildren(inner)
            .build());
    PathFragment nativeRepo = externalDirectory.getRelative("native");
    nativeFs.createDirectoryAndParents(nativeRepo);
    nativeFs.createSymbolicLink(
        nativeRepo.getRelative("back"), externalDirectory.getRelative("cached/plain"));

    var reverseStatus =
        overlay.statIfFound(nativeRepo.getRelative("back"), /* followSymlinks= */ true);
    assertThat(reverseStatus).isNotNull();
    assertThat(reverseStatus.isFile()).isTrue();

    var status =
        overlay.statIfFound(
            externalDirectory.getRelative("cached/alias/cross"), /* followSymlinks= */ true);
    assertThat(status).isNotNull();
    assertThat(status.isFile()).isTrue();
  }

  @Test
  public void statIfFound_noFollowBelowCrossFileSystemDirectoryLink_followsParent()
      throws Exception {
    PathFragment child = injectCachedLinkToNativeDirectory().getRelative("child");

    var status = overlay.statIfFound(child, /* followSymlinks= */ false);

    assertThat(status).isNotNull();
    assertThat(status.isFile()).isTrue();
  }

  @Test
  public void getFileSize_belowCrossFileSystemDirectoryLink_routesToTargetOwner() throws Exception {
    PathFragment child = injectCachedLinkToNativeDirectory().getRelative("child");

    assertThat(overlay.getFileSize(child, /* followSymlinks= */ true)).isEqualTo(3);
    assertThat(overlay.getFileSize(child, /* followSymlinks= */ false)).isEqualTo(3);
  }

  @Test
  public void routedNativeLeaf_onlyLoadsStatusForOperationsThatNeedIt() throws Exception {
    SpiedFileSystem nativeSpy = SpiedFileSystem.createInMemorySpy();
    PathFragment target = externalDirectory.getRelative("native/file");
    nativeSpy.createDirectoryAndParents(target.getParentDirectory());
    FileSystemUtils.writeContent(nativeSpy.getPath(target), new byte[] {1, 2, 3});
    var scopedOverlay = createOverlay(nativeSpy);
    try {
      assertThat(
              scopedOverlay.injectRemoteRepo(
                  RepositoryName.createUnvalidated("cached"),
                  Tree.newBuilder()
                      .setRoot(
                          Directory.newBuilder()
                              .addSymlinks(
                                  SymlinkNode.newBuilder()
                                      .setName("link")
                                      .setTarget(target.getPathString())))
                      .build(),
                  "marker"))
          .isTrue();
      PathFragment link = externalDirectory.getRelative("cached/link");
      clearInvocations(nativeSpy);

      assertThat(scopedOverlay.getFileSize(link, /* followSymlinks= */ true)).isEqualTo(3);

      // The selective canonicalizer resolves the mutable native leaf once and the lazy routed
      // status loads it once. The backing metadata getter must not stat it a third time.
      verify(nativeSpy, times(2)).statIfFound(target, /* followSymlinks= */ false);
      verify(nativeSpy, never()).getFileSize(target, /* followSymlinks= */ false);
      clearInvocations(nativeSpy);

      try (var in = scopedOverlay.getInputStream(link)) {
        assertThat(in.read()).isEqualTo(1);
      }

      // Input streams only need the routed owner and canonical path, so no routed status is
      // loaded after canonicalization.
      verify(nativeSpy, times(1)).statIfFound(target, /* followSymlinks= */ false);
      verify(nativeSpy).getInputStream(target);
    } finally {
      scopedOverlay.afterCommand();
    }
  }

  @Test
  public void capabilityQueries_useOwnerSelectedByRouting() throws Exception {
    FileSystem nativeWithoutCapabilities =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public boolean supportsModifications(PathFragment path) {
            return false;
          }

          @Override
          public boolean supportsSymbolicLinksNatively(PathFragment path) {
            return false;
          }

          @Override
          public boolean supportsHardLinksNatively(PathFragment path) {
            return false;
          }
        };
    PathFragment nativeDirectory = externalDirectory.getRelative("native/dir");
    nativeWithoutCapabilities.createDirectoryAndParents(nativeDirectory);
    var scopedOverlay = createOverlay(nativeWithoutCapabilities);
    try {
      assertThat(
              scopedOverlay.injectRemoteRepo(
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
      PathFragment path = externalDirectory.getRelative("cached/dirlink/new-link");

      assertThat(scopedOverlay.supportsModifications(path)).isFalse();
      assertThat(scopedOverlay.supportsSymbolicLinksNatively(path)).isFalse();
      assertThat(scopedOverlay.supportsHardLinksNatively(path)).isFalse();
    } finally {
      scopedOverlay.afterCommand();
    }
  }

  @Test
  public void getDirectoryEntriesAndReaddir_crossFileSystemDirectoryLink_routeDirectory()
      throws Exception {
    PathFragment linkedDir = injectCachedLinkToNativeDirectory();

    assertThat(overlay.getDirectoryEntries(linkedDir)).containsExactly("child", "child-link");
    assertThat(overlay.readdir(linkedDir, /* followSymlinks= */ false))
        .containsExactly(
            new Dirent("child", Dirent.Type.FILE), new Dirent("child-link", Dirent.Type.SYMLINK));
    assertThat(overlay.readdir(linkedDir, /* followSymlinks= */ true))
        .containsExactly(
            new Dirent("child", Dirent.Type.FILE), new Dirent("child-link", Dirent.Type.FILE));
  }

  @Test
  public void readdir_routedDirectoryLeavesAndReentersExternalDirectory() throws Exception {
    PathFragment nativeDirectory = PathFragment.create("/workspace/dir");
    nativeFs.createDirectoryAndParents(nativeDirectory);
    nativeFs.createSymbolicLink(
        nativeDirectory.getRelative("back"), externalDirectory.getRelative("cached/plain"));
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("plain")
                            .setDigest(digestUtil.compute(new byte[0])))
                    .addSymlinks(
                        SymlinkNode.newBuilder()
                            .setName("dirlink")
                            .setTarget(nativeDirectory.getPathString())))
            .build());

    assertThat(
            overlay.readdir(
                externalDirectory.getRelative("cached/dirlink"), /* followSymlinks= */ true))
        .containsExactly(new Dirent("back", Dirent.Type.FILE));
  }

  @Test
  public void createDirectory_belowCrossFileSystemDirectoryLink_routesParent() throws Exception {
    PathFragment linkedDir = injectCachedLinkToNativeDirectory();

    assertThat(overlay.createDirectory(linkedDir.getRelative("created"))).isTrue();

    assertThat(
            nativeFs.isDirectory(
                externalDirectory.getRelative("native/dir/created"), /* followSymlinks= */ true))
        .isTrue();
  }

  @Test
  public void deleteTree_belowCrossFileSystemDirectoryLink_routesParent() throws Exception {
    PathFragment child = injectCachedLinkToNativeDirectory().getRelative("child");

    overlay.deleteTree(child);

    assertThat(nativeFs.exists(externalDirectory.getRelative("native/dir/child"))).isFalse();
  }

  @Test
  public void createTempDirectory_crossFileSystemParent_returnsLexicalPath() throws Exception {
    PathFragment linkedDir = injectCachedLinkToNativeDirectory();

    PathFragment tempDir = overlay.createTempDirectory(linkedDir, "tmp-");

    assertThat(tempDir.getParentDirectory()).isEqualTo(linkedDir);
    assertThat(tempDir.getBaseName()).startsWith("tmp-");
    assertThat(overlay.isDirectory(tempDir, /* followSymlinks= */ true)).isTrue();
    assertThat(
            nativeFs.isDirectory(
                externalDirectory.getRelative("native/dir").getRelative(tempDir.getBaseName()),
                /* followSymlinks= */ true))
        .isTrue();
  }

  @Test
  public void renameTo_belowCrossFileSystemDirectoryLink_routesParents() throws Exception {
    PathFragment linkedDir = injectCachedLinkToNativeDirectory();
    PathFragment source = linkedDir.getRelative("child");
    PathFragment target = linkedDir.getRelative("renamed");

    overlay.renameTo(source, target);

    assertThat(nativeFs.exists(externalDirectory.getRelative("native/dir/child"))).isFalse();
    assertThat(nativeFs.exists(externalDirectory.getRelative("native/dir/renamed"))).isTrue();
  }

  @Test
  public void getOutputStream_danglingCrossFileSystemLink_createsNativeTarget() throws Exception {
    PathFragment nativeTarget = externalDirectory.getRelative("native/created");
    nativeFs.createDirectoryAndParents(nativeTarget.getParentDirectory());
    injectCachedRepo(
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addSymlinks(
                        SymlinkNode.newBuilder()
                            .setName("dangling")
                            .setTarget(nativeTarget.getPathString())))
            .build());
    PathFragment link = externalDirectory.getRelative("cached/dangling");
    assertThat(overlay.statIfFound(link, /* followSymlinks= */ true)).isNull();

    try (OutputStream out =
        overlay.getOutputStream(link, /* append= */ false, /* internal= */ false)) {
      out.write(1);
    }

    assertThat(nativeFs.getFileSize(nativeTarget, /* followSymlinks= */ true)).isEqualTo(1);
  }
}
