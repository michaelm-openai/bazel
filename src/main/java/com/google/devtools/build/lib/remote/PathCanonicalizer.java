// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.devtools.build.lib.vfs.FileSymlinkLoopException;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Canonicalizes paths like {@link FileSystem#resolveSymbolicLinks}, while storing the intermediate
 * results in a trie so they can be reused by future canonicalizations.
 *
 * <p>This is an implementation detail of the union filesystems in this package, factored out for
 * testing. Because their symlinks can straddle underlying filesystems, the performance of large
 * filesystem scans can be greatly improved with a custom {@link FileSystem#resolveSymbolicLinks}
 * implementation that leverages the trie to avoid repeated work.
 *
 * <p>On case-insensitive filesystems, accessing the same path through different case variations
 * will produce distinct trie entries. This could be fixed, but it's a performance rather than a
 * correctness concern, and shouldn't matter most of the time.
 *
 * <p>Thread-safe: concurrent calls to {@link #resolveSymbolicLinks} are supported. As with {@link
 * FileSystem#resolveSymbolicLinks}, the result is undefined if the filesystem is mutated
 * concurrently.
 */
final class PathCanonicalizer {

  interface Resolver {
    /**
     * Returns the result of {@link FileSystem#readSymbolicLink} if the path is a symlink, otherwise
     * null. All but the last path segment must be canonical.
     *
     * @throws IOException if the file type or symlink target path could not be determined
     */
    @Nullable
    PathFragment resolveOneLink(PathFragment path) throws IOException;
  }

  /** The raw symlink target, or null for a non-link, and whether that exact result is cacheable. */
  record Resolution(@Nullable PathFragment targetPath, boolean cacheable) {}

  @FunctionalInterface
  interface SelectiveResolver {
    /**
     * Resolves one path component and reports whether that exact result can be reused.
     *
     * <p>The backing file system must be selected once, and both the resolution and cacheability
     * decision must describe that backing. Cacheable results must remain stable until their prefix
     * is cleared.
     */
    Resolution resolveOneLink(PathFragment path) throws IOException;
  }

  /** A trie node. */
  private sealed interface Node permits SymlinkNode, ParentNode {}

  /** A trie node corresponding to a symlink. */
  private record SymlinkNode(PathFragment targetPath) implements Node {}

  /** A trie node that can have children. */
  private abstract static sealed class ParentNode extends ConcurrentHashMap<String, Node>
      implements Node permits NonSymlinkNode, UncachedNode {
    ParentNode() {
      super(/* initialCapacity= */ 1);
    }
  }

  /** A cached trie node not corresponding to a symlink. */
  private static final class NonSymlinkNode extends ParentNode {}

  /** A trie node whose file type and symlink target must be resolved on every access. */
  private static final class UncachedNode extends ParentNode {}

  /** Connects a temporary chain of uncached nodes to the retained trie. */
  private record PendingAttachment(ParentNode parent, String segment, UncachedNode child) {}

  @Nullable private final Resolver resolver;
  @Nullable private final SelectiveResolver selectiveResolver;
  private final NonSymlinkNode root = new NonSymlinkNode();
  // Selective resolver callbacks perform I/O without holding this lock. Pairing publication with
  // invalidation and checking the generation prevents a callback that spans clearPrefix from
  // publishing its old result afterward.
  private final Object cacheMutationLock = new Object();
  private volatile long generation;

  PathCanonicalizer(Resolver resolver) {
    this.resolver = resolver;
    this.selectiveResolver = null;
  }

  /**
   * Creates a canonicalizer that reuses results according to {@code selectiveResolver}. Uncacheable
   * nodes remain in the trie solely to anchor cacheable descendants.
   */
  static PathCanonicalizer createSelective(SelectiveResolver selectiveResolver) {
    return new PathCanonicalizer(/* resolver= */ null, selectiveResolver);
  }

  private PathCanonicalizer(
      @Nullable Resolver resolver, @Nullable SelectiveResolver selectiveResolver) {
    this.resolver = resolver;
    this.selectiveResolver = selectiveResolver;
  }

  /** Returns the root node for an absolute path. */
  private NonSymlinkNode getRootNode(PathFragment path) {
    checkArgument(path.isAbsolute());
    // Unix has a single root. Windows has one root per drive.
    if (path.getDriveStrLength() > 1) {
      return (NonSymlinkNode)
          root.computeIfAbsent(path.getDriveStr(), unused -> new NonSymlinkNode());
    }
    return root;
  }

  /**
   * Canonicalizes a path, reusing cached information if possible.
   *
   * @param path the path to canonicalize.
   * @param maxLinks the maximum number of symlinks that can be followed in the process of
   *     canonicalizing the path.
   * @throws FileSymlinkLoopException if too many symlinks had to be followed.
   * @throws IOException if an I/O error occurs
   * @return the canonical path.
   */
  private PathFragment resolveSymbolicLinks(PathFragment path, int maxLinks) throws IOException {
    return selectiveResolver == null
        ? resolveSymbolicLinksCached(path, maxLinks)
        : resolveSymbolicLinksSelective(path, maxLinks);
  }

  private PathFragment resolveSymbolicLinksCached(PathFragment path, int maxLinks)
      throws IOException {
    // This code is carefully written to be as fast as possible when the path is already canonical
    // and has been previously cached. Avoid making changes without benchmarking. A tree artifact
    // with hundreds of thousands of files makes for a good benchmark.

    ParentNode node = getRootNode(path);
    Iterable<String> segments = path.segments();
    int segmentIndex = 0;

    // Loop invariants:
    // - `segmentIndex` is the index of the current `segment` relative to the start of `path`. The
    //   first segment has index 0.
    // - `path` is the absolute path to canonicalize. If `segmentIndex` > 0, `path` is already
    //    canonical up to and including `segmentIndex` - 1.
    // - `node` is the trie node corresponding to the `path` prefix ending with `segmentIndex` - 1,
    //   or to the root path when `segmentIndex` is 0.
    for (String segment : segments) {
      Node nextNode = node.get(segment);
      if (nextNode == null) {
        PathFragment naivePath = path.subFragment(0, segmentIndex + 1);
        long expectedGeneration = generation;
        PathFragment targetPath = resolver.resolveOneLink(naivePath);
        Node resolvedNode = targetPath != null ? new SymlinkNode(targetPath) : new NonSymlinkNode();
        synchronized (cacheMutationLock) {
          if (generation == expectedGeneration) {
            Node existingNode = node.putIfAbsent(segment, resolvedNode);
            nextNode = existingNode != null ? existingNode : resolvedNode;
          }
        }
        if (nextNode == null) {
          // The resolver crossed an invalidation. Start over from the retained trie rather than
          // publishing into a node that may have been detached by clearPrefix.
          return resolveSymbolicLinksCached(path, maxLinks);
        }
      }

      switch (nextNode) {
        case SymlinkNode(PathFragment resolvedTarget) -> {
          return followSymlink(path, segmentIndex, resolvedTarget, maxLinks);
        }
        case ParentNode parentNode -> {
          node = parentNode;
          segmentIndex++;
        }
      }
    }

    return path;
  }

  private PathFragment resolveSymbolicLinksSelective(PathFragment path, int maxLinks)
      throws IOException {
    while (true) {
      long expectedGeneration = generation;
      PathFragment result = resolveSymbolicLinksSelective(path, maxLinks, expectedGeneration);
      if (result != null && generation == expectedGeneration) {
        return result;
      }
    }
  }

  @Nullable
  private PathFragment resolveSymbolicLinksSelective(
      PathFragment path, int maxLinks, long expectedGeneration) throws IOException {
    ParentNode node = getRootNode(path);
    Iterable<String> segments = path.segments();
    int segmentIndex = 0;
    PendingAttachment pendingAttachment = null;

    for (String segment : segments) {
      Node nextNode = node.get(segment);
      if (nextNode == null || nextNode instanceof UncachedNode) {
        PathFragment naivePath = path.subFragment(0, segmentIndex + 1);
        Resolution resolution = selectiveResolver.resolveOneLink(naivePath);
        if (generation != expectedGeneration) {
          return null;
        }
        PathFragment targetPath = resolution.targetPath();
        if (resolution.cacheable()) {
          nextNode = targetPath != null ? new SymlinkNode(targetPath) : new NonSymlinkNode();
          if (!publish(node, segment, nextNode, pendingAttachment, expectedGeneration)) {
            return null;
          }
          pendingAttachment = null;
        } else {
          if (targetPath != null) {
            nextNode = new SymlinkNode(targetPath);
          } else if (nextNode instanceof UncachedNode) {
            // Retained uncached nodes have stable descendants and must be revisited on every
            // lookup, but can still serve as their anchor while they remain non-symlinks.
          } else {
            var uncachedNode = new UncachedNode();
            if (pendingAttachment == null) {
              pendingAttachment = new PendingAttachment(node, segment, uncachedNode);
            } else {
              // This node is not reachable from the retained trie yet, so no synchronization is
              // needed. The full chain is attached only if it reaches a cacheable descendant.
              node.put(segment, uncachedNode);
            }
            nextNode = uncachedNode;
          }
        }
      }
      switch (nextNode) {
        case SymlinkNode(PathFragment resolvedTarget) -> {
          return followSymlink(path, segmentIndex, resolvedTarget, maxLinks, expectedGeneration);
        }
        case ParentNode parentNode -> {
          node = parentNode;
          segmentIndex++;
        }
      }
    }

    return path;
  }

  /** Publishes a cacheable result and any uncached ancestors needed to reach it. */
  private boolean publish(
      ParentNode parent,
      String segment,
      Node result,
      @Nullable PendingAttachment pendingAttachment,
      long expectedGeneration) {
    synchronized (cacheMutationLock) {
      if (generation != expectedGeneration) {
        return false;
      }
      parent.put(segment, result);
      if (pendingAttachment != null
          && pendingAttachment
                  .parent()
                  .putIfAbsent(pendingAttachment.segment(), pendingAttachment.child())
              != null) {
        // Another lookup attached a chain at the same location. Restart from the retained trie
        // instead of publishing into a detached chain.
        return false;
      }
      return true;
    }
  }

  private PathFragment followSymlink(
      PathFragment path, int segmentIndex, PathFragment targetPath, int maxLinks)
      throws IOException {
    return followSymlink(path, segmentIndex, targetPath, maxLinks, /* expectedGeneration= */ -1);
  }

  @Nullable
  private PathFragment followSymlink(
      PathFragment path,
      int segmentIndex,
      PathFragment targetPath,
      int maxLinks,
      long expectedGeneration)
      throws IOException {
    if (maxLinks == 0) {
      throw new FileSymlinkLoopException(path.getPathString() + FileSystem.ERR_TOO_MANY_SYMLINKS);
    }

    // Path normalization handles uplevel references in relative targets.
    PathFragment newPath;
    if (targetPath.isAbsolute()) {
      newPath = targetPath.getRelative(path.subFragment(segmentIndex + 1));
    } else {
      newPath =
          path.subFragment(0, segmentIndex)
              .getRelative(targetPath)
              .getRelative(path.subFragment(segmentIndex + 1));
    }

    // For absolute symlinks, we must start over. For relative symlinks, it would be possible to
    // restart after the already canonicalized prefix, but they're too rare to be worth optimizing.
    return selectiveResolver == null
        ? resolveSymbolicLinksCached(newPath, maxLinks - 1)
        : resolveSymbolicLinksSelective(newPath, maxLinks - 1, expectedGeneration);
  }

  /**
   * Canonicalizes a path, reusing cached information if possible.
   *
   * <p>See {@link FileSystem#resolveSymbolicLinks} for the full specification.
   *
   * @param path the path to canonicalize.
   * @throws FileSymlinkLoopException if too many symlinks had to be followed.
   * @throws IOException if an I/O error occurs
   * @return the canonical path.
   */
  PathFragment resolveSymbolicLinks(PathFragment path) throws IOException {
    return resolveSymbolicLinks(path, FileSystem.MAX_SYMLINKS);
  }

  /** Removes cached information for a path prefix. */
  void clearPrefix(PathFragment pathPrefix) {
    synchronized (cacheMutationLock) {
      generation++;
      Node node = getRootNode(pathPrefix);
      ParentNode parent = null;
      String parentSegment = null;
      Iterator<String> segments = pathPrefix.segments().iterator();
      boolean hasNext = segments.hasNext();

      while (node != null && hasNext) {
        String segment = segments.next();
        hasNext = segments.hasNext();

        switch (node) {
          case SymlinkNode symlinkNode -> {
            // Invalidate all intermediate symlinks.
            if (parent != null) {
              parent.remove(parentSegment);
            }
            return;
          }
          case ParentNode parentNode -> {
            if (!hasNext) {
              // Found the path prefix.
              parentNode.remove(segment);
            } else {
              parent = parentNode;
              parentSegment = segment;
              node = parentNode.get(segment);
            }
          }
        }
      }
    }
  }

  /** Returns the number of nodes retained by the trie. */
  int nodeCountForTesting() {
    return countNodes(root);
  }

  private static int countNodes(Node node) {
    return switch (node) {
      case SymlinkNode unused -> 1;
      case ParentNode parentNode ->
          1 + parentNode.values().stream().mapToInt(PathCanonicalizer::countNodes).sum();
    };
  }
}
