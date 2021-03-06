/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.util.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Provides a POSIX like file system layered on top of Google Cloud Storage (GCS).
 *
 * All file system aspects (eg, path) are encapsulated in this class, they are
 * not exposed to the underlying layer. That is, all interactions with the
 * underlying layer are strictly in terms of buckets and objects.
 */
public class GoogleCloudStorageFileSystem {

  // URI scheme for GCS.
  public static final String SCHEME = "gs";

  // URI of the root path.
  public static final URI GCS_ROOT = URI.create(SCHEME + ":/");

  // Logger.
  public static final Logger LOG =
      LoggerFactory.getLogger(GoogleCloudStorageFileSystem.class);

  // GCS access instance.
  private GoogleCloudStorage gcs;

  // FS options
  private final GoogleCloudStorageFileSystemOptions options;

  // Executor for updating directory timestamps.
  private ExecutorService updateTimestampsExecutor = new ThreadPoolExecutor(
      2 /* core thread count */, 2 /* max thread count */, 2 /* keepAliveTime */,
      TimeUnit.SECONDS /* keepAliveTime unit */, new LinkedBlockingQueue<Runnable>(1000),
      new ThreadFactoryBuilder()
          .setNameFormat("gcsfs-timestamp-updates-%d")
          .setDaemon(true)
          .build());

  // Comparator used for sorting paths.
  //
  // For some bulk operations, we need to operate on parent directories before
  // we operate on their children. To achieve this, we sort paths such that
  // shorter paths appear before longer paths. Also, we sort lexicographically
  // within paths of the same length (this is not strictly required but helps when
  // debugging/testing).
  @VisibleForTesting
  static Comparator<URI> pathComparator = new Comparator<URI>() {
    @Override
    public int compare(URI a, URI b) {
      String as = a.toString();
      String bs = b.toString();
      return (as.length() == bs.length())
          ? as.compareTo(bs)
          : Integer.compare(as.length(), bs.length());
    }
  };

  // Comparator used for sorting a collection of FileInfo items based on path comparison.
  @VisibleForTesting
  static Comparator<FileInfo> fileInfoPathComparator = new Comparator<FileInfo> () {
    @Override
    public int compare(FileInfo file1, FileInfo file2) {
      return pathComparator.compare(file1.getPath(), file2.getPath());
    }
  };

  /**
   * Constructs an instance of GoogleCloudStorageFileSystem.
   *
   * @param credential OAuth2 credential that allows access to GCS.
   * @param options Options for how this filesystem should operate and configure its
   *    underlying storage.
   * @throws IOException
   */
  public GoogleCloudStorageFileSystem(
      Credential credential,
      GoogleCloudStorageFileSystemOptions options) throws IOException {
    LOG.debug("GCSFS({})", options.getCloudStorageOptions().getAppName());
    options.throwIfNotValid();

    Preconditions.checkArgument(credential != null, "credential must not be null");

    this.options = options;
    this.gcs = new GoogleCloudStorageImpl(options.getCloudStorageOptions(), credential);

    if (options.isMetadataCacheEnabled()) {
      DirectoryListCache resourceCache = null;
      switch (options.getCacheType()) {
        case IN_MEMORY: {
          resourceCache = InMemoryDirectoryListCache.getInstance();
          break;
        }
        case FILESYSTEM_BACKED: {
          Preconditions.checkArgument(!Strings.isNullOrEmpty(options.getCacheBasePath()),
              "When using FILESYSTEM_BACKED DirectoryListCache, cacheBasePath must not be null.");
          resourceCache = new FileSystemBackedDirectoryListCache(options.getCacheBasePath());
          break;
        }
        default:
          throw new IllegalArgumentException(String.format(
              "DirectoryListCache.Type '%s' not supported.", options.getCacheType()));
      }
      resourceCache.getMutableConfig().setMaxEntryAgeMillis(options.getCacheMaxEntryAgeMillis());
      resourceCache.getMutableConfig().setMaxInfoAgeMillis(options.getCacheMaxInfoAgeMillis());
      gcs = new CacheSupplementedGoogleCloudStorage(gcs, resourceCache);
    }
  }

  /**
   * Constructs a GoogleCloudStorageFilesystem based on an already-configured underlying
   * GoogleCloudStorage {@code gcs}.
   */
  public GoogleCloudStorageFileSystem(GoogleCloudStorage gcs) throws IOException {
    this(gcs, GoogleCloudStorageFileSystemOptions.newBuilder().build());
  }

  /**
   * Constructs a GoogleCloudStorageFilesystem based on an already-configured underlying
   * GoogleCloudStorage {@code gcs}. Any options pertaining to GCS creation will be ignored.
   */
  public GoogleCloudStorageFileSystem(
      GoogleCloudStorage gcs, GoogleCloudStorageFileSystemOptions options) throws IOException {
    this.gcs = gcs;
    this.options = options;
  }

  @VisibleForTesting
  void setUpdateTimestampsExecutor(ExecutorService executor) {
    this.updateTimestampsExecutor = executor;
  }


  /**
   * Retrieve the options that were used to create this
   * GoogleCloudStorageFileSystem.
   */
  public GoogleCloudStorageFileSystemOptions getOptions() {
    return options;
  }

  /**
   * Creates and opens an object for writing.
   * If the object already exists, it is deleted.
   *
   * @param path Object full path of the form gs://bucket/object-path.
   * @return A channel for writing to the given object.
   * @throws IOException
   */
  public WritableByteChannel create(URI path) throws IOException {
    LOG.debug("create({})", path);
    return create(path, CreateFileOptions.DEFAULT);
  }

  /**
   * Convert {@code CreateFileOptions} to {@code CreateObjectOptions}.
   */
  public static CreateObjectOptions objectOptionsFromFileOptions(CreateFileOptions options) {
    return new CreateObjectOptions(options.overwriteExisting(), options.getContentType(),
        options.getAttributes());
  }

  /**
   * Creates and opens an object for writing.
   *
   * @param path Object full path of the form gs://bucket/object-path.
   * @return A channel for writing to the given object.
   * @throws IOException
   */
  public WritableByteChannel create(URI path, CreateFileOptions options)
      throws IOException {

    LOG.debug("create({})", path);
    Preconditions.checkNotNull(path);
    Preconditions.checkArgument(!FileInfo.isDirectoryPath(path),
        "Cannot create a file whose name looks like a directory.");

    // Check if a directory of that name exists.
    URI dirPath = FileInfo.convertToDirectoryPath(path);
    if (exists(dirPath)) {
      throw new IOException("A directory with that name exists: " + path);
    }

    // Ensure that parent directories exist.
    URI parentPath = getParentPath(path);
    if (parentPath != null) {
      mkdirs(parentPath);
    }

    return createInternal(path, options);
  }

  /**
   * Creates and opens an object for writing.
   * If the object already exists, it is deleted.
   *
   * @param path Object full path of the form gs://bucket/object-path.
   * @return A channel for writing to the given object.
   * @throws IOException
   */
  WritableByteChannel createInternal(URI path, CreateFileOptions options)
      throws IOException {

    // Validate the given path. false == do not allow empty object name.
    StorageResourceId resourceId = validatePathAndGetId(path, false);
    WritableByteChannel channel = gcs.create(resourceId, objectOptionsFromFileOptions(options));
    tryUpdateTimestampsForParentDirectories(ImmutableList.of(path), ImmutableList.<URI>of());
    return channel;
  }

  /**
   * Opens an object for reading.
   *
   * @param path Object full path of the form gs://bucket/object-path.
   * @return A channel for reading from the given object.
   * @throws FileNotFoundException if the given path does not exist.
   * @throws IOException if object exists but cannot be opened.
   */
  public SeekableByteChannel open(URI path)
      throws IOException {

    LOG.debug("open({})", path);
    Preconditions.checkNotNull(path);
    Preconditions.checkArgument(!FileInfo.isDirectoryPath(path),
        "Cannot open a directory for reading: " + path);

    // Validate the given path. false == do not allow empty object name.
    StorageResourceId resourceId = validatePathAndGetId(path, false);
    return gcs.open(resourceId);
  }

  /**
   * Deletes one or more items indicated by the given path.
   *
   * If path points to a directory:
   * -- if recursive is true,
   *    all items under that path are recursively deleted followed by
   *    deletion of the directory.
   * -- else,
   *    -- the directory is deleted if it is empty,
   *    -- else, an IOException is thrown.
   *
   * The recursive parameter is ignored for a file.
   *
   * @param path Path of the item to delete.
   * @param recursive If true, all sub-items are also deleted.
   *
   * @throws FileNotFoundException if the given path does not exist.
   * @throws IOException
   */
  public void delete(URI path, boolean recursive)
      throws IOException {

    LOG.debug("delete({}, {})", path, recursive);
    Preconditions.checkNotNull(path);
    Preconditions.checkArgument(!path.equals(GCS_ROOT), "Cannot delete root path.");

    // Throw FileNotFoundException if the path does not exist.
    FileInfo fileInfo = getFileInfo(path);
    if (!fileInfo.exists()) {
      throw getFileNotFoundException(path);
    }

    List<URI> itemsToDelete = new ArrayList<>();
    List<URI> bucketsToDelete = new ArrayList<>();

    // Delete sub-items if it is a directory.
    if (fileInfo.isDirectory()) {
      List<URI> subpaths = listFileNames(fileInfo, recursive);
      if (recursive) {
        itemsToDelete.addAll(subpaths);
      } else {
        if (subpaths.size() > 0) {
          throw new DirectoryNotEmptyException("Cannot delete a non-empty directory.");
        }
      }
    }

    if (fileInfo.getItemInfo().isBucket()) {
      bucketsToDelete.add(fileInfo.getPath());
    } else {
      itemsToDelete.add(fileInfo.getPath());
    }

    deleteInternal(itemsToDelete, bucketsToDelete);
  }

  /**
   * Deletes all items in the given path list followed by all bucket items.
   */
  private void deleteInternal(List<URI> paths, List<URI> bucketPaths)
      throws IOException {
    // TODO(user): We might need to separate out children into separate batches from parents to
    // avoid deleting a parent before somehow failing to delete a child.

    // Delete children before their parents.
    //
    // Note: we modify the input list, which is ok for current usage.
    // We should make a copy in case that changes in future.
    Collections.sort(paths, pathComparator);
    Collections.reverse(paths);

    if (paths.size() > 0) {
      List<StorageResourceId> objectsToDelete = new ArrayList<>();
      for (URI path : paths) {
        StorageResourceId resourceId = validatePathAndGetId(path, false);
        objectsToDelete.add(resourceId);
      }
      gcs.deleteObjects(objectsToDelete);
      // Any path that was deleted, we should update the parent except for parents we also deleted
      tryUpdateTimestampsForParentDirectories(paths, paths);
    }

    if (bucketPaths.size() > 0) {
      List<String> bucketsToDelete = new ArrayList<>();
      for (URI path : bucketPaths) {
        StorageResourceId resourceId = validatePathAndGetId(path, true);
        gcs.waitForBucketEmpty(resourceId.getBucketName());
        bucketsToDelete.add(resourceId.getBucketName());
      }
      gcs.deleteBuckets(bucketsToDelete);
    }
  }

  /**
   * Indicates whether the given item exists.
   *
   * @param path Path of the item to check.
   * @return true if the given item exists, false otherwise.
   * @throws IOException
   */
  public boolean exists(URI path)
      throws IOException {
    LOG.debug("exists({})", path);
    return getFileInfo(path).exists();
  }

  /**
   * Creates the list of directories specified in {@code exactDirPaths}; doesn't perform validation
   * and doesn't create their parent dirs if their parent dirs don't already exist. Use with
   * caution.
   */
  public void repairDirs(List<URI> exactDirPaths)
      throws IOException{
    LOG.debug("repairDirs({})", exactDirPaths);
    List<StorageResourceId> dirsToCreate = new ArrayList<>();
    for (URI dirUri : exactDirPaths) {
      StorageResourceId resourceId = validatePathAndGetId(dirUri, true);
      if (resourceId.isStorageObject()) {
        resourceId = FileInfo.convertToDirectoryPath(resourceId);
        dirsToCreate.add(resourceId);
      }
    }

    if (dirsToCreate.isEmpty()) {
      return;
    }

    /*
     * Note that in both cases, we do not update parent directory timestamps. The idea is that:
     * 1) directory repair isn't a user-invoked action, 2) directory repair shouldn't be thought
     * of "creating" directories, instead it drops markers to help GCSFS and GHFS find directories
     * that already "existed" and 3) it's extra RPCs on top of the list and create empty object RPCs
     */
    if (dirsToCreate.size() == 1) {
      // Don't go through batch interface for a single-item case to avoid batching overhead.
      gcs.createEmptyObject(dirsToCreate.get(0));
    } else if (dirsToCreate.size() > 1) {
      gcs.createEmptyObjects(dirsToCreate);
    }

    LOG.warn("Successfully repaired {} directories.", dirsToCreate.size());
  }

  /**
   * Creates a directory at the specified path. Also creates any parent
   * directories as necessary. Similar to 'mkdir -p' command.
   *
   * @param path Path of the directory to create.
   * @throws IOException
   */
  public void mkdirs(URI path)
      throws IOException {
    LOG.debug("mkdirs({})", path);
    Preconditions.checkNotNull(path);

    if (path.equals(GCS_ROOT)) {
      // GCS_ROOT directory always exists, no need to go through the rest of the method.
      return;
    }

    StorageResourceId resourceId = validatePathAndGetId(path, true);

    // Ensure that the path looks like a directory path.
    if (resourceId.isStorageObject()) {
      resourceId = FileInfo.convertToDirectoryPath(resourceId);
      path = getPath(resourceId.getBucketName(), resourceId.getObjectName());
    }

    // Create a list of all intermediate paths.
    // for example,
    // gs://foo/bar/zoo/ => (gs://foo/, gs://foo/bar/, gs://foo/bar/zoo/)
    //
    // We also need to find out if any of the subdir path item exists as a file
    // therefore, also create a list of file equivalent of each subdir path.
    // for example,
    // gs://foo/bar/zoo/ => (gs://foo/bar, gs://foo/bar/zoo)
    //
    // Note that a bucket cannot exist as a file therefore no need to
    // check for gs://foo above.
    List<URI> subDirPaths = new ArrayList<>();
    List<String> subdirs = getSubDirs(resourceId.getObjectName());
    for (String subdir : subdirs) {
      URI subPath = getPath(resourceId.getBucketName(), subdir, true);
      subDirPaths.add(subPath);
      LOG.debug("mkdirs: sub-path: {}", subPath);
      if (!Strings.isNullOrEmpty(subdir)) {
        URI subFilePath =
            getPath(resourceId.getBucketName(), subdir.substring(0, subdir.length() - 1), true);
        subDirPaths.add(subFilePath);
        LOG.debug("mkdirs: sub-path: {}", subFilePath);
      }
    }

    // Add the bucket portion.
    URI bucketPath = getPath(resourceId.getBucketName());
    subDirPaths.add(bucketPath);
    LOG.debug("mkdirs: sub-path: {}", bucketPath);

    // Get status of each intermediate path.
    List<FileInfo> subDirInfos = getFileInfos(subDirPaths);

    // Each intermediate path must satisfy one of the following conditions:
    // -- it does not exist or
    // -- if it exists, it is a directory
    //
    // If any of the intermediate paths violates these requirements then
    // bail out early so that we do not end up with partial set of
    // created sub-directories. It is possible that the status of intermediate
    // paths can change after we make this check therefore this is a
    // good faith effort and not a guarantee.
    for (FileInfo fileInfo : subDirInfos) {
      if (fileInfo.exists() && !fileInfo.isDirectory()) {
        throw new IOException(
            "Cannot create directories because of existing file: "
            + fileInfo.getPath());
      }
    }

    // Create missing sub-directories in order of shortest prefix first.
    Collections.sort(subDirInfos, new Comparator<FileInfo> () {
      @Override
      public int compare(FileInfo file1, FileInfo file2) {
        return Integer.valueOf(file1.getPath().toString().length())
            .compareTo(file2.getPath().toString().length());
      }
    });

    // Make buckets immediately, otherwise collect directories into a list for batch creation.
    List<StorageResourceId> dirsToCreate = new ArrayList<>();
    for (FileInfo fileInfo : subDirInfos) {
      if (fileInfo.isDirectory() && !fileInfo.exists()) {
        StorageResourceId dirId = fileInfo.getItemInfo().getResourceId();
        Preconditions.checkArgument(!dirId.isRoot(), "Cannot create root directory.");
        if (dirId.isBucket()) {
          gcs.create(dirId.getBucketName());
          continue;
        }

        // Ensure that the path looks like a directory path.
        dirId = FileInfo.convertToDirectoryPath(dirId);
        dirsToCreate.add(dirId);
      }
    }

    if (dirsToCreate.size() == 1) {
      // Don't go through batch interface for a single-item case to avoid batching overhead.
      gcs.createEmptyObject(dirsToCreate.get(0));
    } else if (dirsToCreate.size() > 1) {
      gcs.createEmptyObjects(dirsToCreate);
    }

    List<URI> createdDirectories =
        Lists.transform(dirsToCreate, new Function<StorageResourceId, URI>() {
          @Override
          public URI apply(StorageResourceId resourceId) {
            return getPath(resourceId.getBucketName(), resourceId.getObjectName());
          }
        });

    // Update parent directories, but not the ones we just created because we just created them.
    tryUpdateTimestampsForParentDirectories(createdDirectories, createdDirectories);
  }

  /**
   * Renames the given item's path.
   *
   * The operation is disallowed if any of the following is true:
   * -- src == GCS_ROOT
   * -- src is a file and dst == GCS_ROOT
   * -- src does not exist
   * -- dst is a file that already exists
   * -- parent of the destination does not exist.
   *
   * Otherwise, the expected behavior is as follows:
   * -- if src is a directory
   *    -- dst is an existing file => disallowed
   *    -- dst is a directory => rename the directory.
   *
   * -- if src is a file
   *    -- dst is a file => rename the file.
   *    -- dst is a directory => similar to the previous case after
   *                             appending src file-name to dst
   *
   * Note:
   * This function is very expensive to call for directories that
   * have many sub-items.
   *
   * @param src Path of the item to rename.
   * @param dst New path of the item.
   * @throws FileNotFoundException if src does not exist.
   * @throws IOException
   */
  public void rename(URI src, URI dst)
      throws IOException {

    LOG.debug("rename({}, {})", src, dst);
    Preconditions.checkNotNull(src);
    Preconditions.checkNotNull(dst);
    Preconditions.checkArgument(!src.equals(GCS_ROOT), "Root path cannot be renamed.");

    // Leaf item of the source path.
    String srcItemName = getItemName(src);

    // Parent of the destination path.
    URI dstParent = getParentPath(dst);

    // Obtain info on source, destination and destination-parent.
    List<URI> paths = new ArrayList<>();
    paths.add(src);
    paths.add(dst);
    if (dstParent != null) {
      // dstParent is null if dst is GCS_ROOT.
      paths.add(dstParent);
    }
    List<FileInfo> fileInfo = getFileInfos(paths);
    FileInfo srcInfo = fileInfo.get(0);
    FileInfo dstInfo = fileInfo.get(1);
    FileInfo dstParentInfo = null;
    if (dstParent != null) {
      dstParentInfo = fileInfo.get(2);
    }

    // Make sure paths match what getFileInfo() returned (it can add / at the end).
    src = srcInfo.getPath();
    dst = dstInfo.getPath();

    // Throw if the source file does not exist.
    if (!srcInfo.exists()) {
      throw getFileNotFoundException(src);
    }

    // Throw if src is a file and dst == GCS_ROOT
    if (!srcInfo.isDirectory() && dst.equals(GCS_ROOT)) {
      throw new IOException("A file cannot be created in root.");
    }

    // Throw if the destination is a file that already exists.
    if (dstInfo.exists() && !dstInfo.isDirectory()) {
      throw new IOException("Cannot overwrite existing file: " + dst);
    }

    // Rename operation cannot be completed if parent of destination does not exist.
    if ((dstParentInfo != null) && !dstParentInfo.exists()) {
      throw new IOException("Cannot rename because path does not exist: " + dstParent);
    }

    // Having taken care of the initial checks, apply the regular rules.
    // After applying the rules, we will be left with 2 paths such that:
    // -- either both are files or both are directories
    // -- src exists and dst leaf does not exist
    if (srcInfo.isDirectory()) {
      // -- if src is a directory
      //    -- dst is an existing file => disallowed
      //    -- dst is a directory => rename the directory.

      // The first case (dst is an existing file) is already checked earlier.
      // If the destination path looks like a file, make it look like a
      // directory path. This is because users often type 'mv foo bar'
      // rather than 'mv foo bar/'.
      if (!dstInfo.isDirectory()) {
        dst = FileInfo.convertToDirectoryPath(dst);
        dstInfo = getFileInfo(dst);
      }

      if (dstInfo.exists()) {
        if (dst.equals(GCS_ROOT)) {
          dst = getPath(srcItemName);
        } else {
          dst = dst.resolve(srcItemName);
        }
      }
    } else {
      // -- src is a file
      //    -- dst is a file => rename the file.
      //    -- dst is a directory => similar to the previous case after
      //                             appending src file-name to dst

      if (dstInfo.isDirectory()) {
        if (!dstInfo.exists()) {
          throw new IOException("Cannot rename because path does not exist: " + dstInfo.getPath());
        } else {
          dst = dst.resolve(srcItemName);
        }
      } else {
        // Destination is a file.
        // See if there is a directory of that name.
        URI dstDir = FileInfo.convertToDirectoryPath(dst);
        FileInfo dstDirInfo = getFileInfo(dstDir);
        if (dstDirInfo.exists()) {
          dst = dstDir.resolve(srcItemName);
        }
      }
    }

    renameInternal(srcInfo, dst);
  }

  /**
   * Composes inputs into a single GCS object. This performs a GCS Compose. Objects will be composed
   * according to the order they appear in the input. The destination object, if already present,
   * will be overwritten. Sources and destination are assumed to be in the same bucket.
   *
   * @param sources the list of URIs to be composed
   * @param destination the resulting URI with composed sources
   * @param contentType content-type of the composed object
   * @throws IOException if the Compose operation was unsuccessful
   */
  public void compose(List<URI> sources, URI destination, String contentType) throws IOException {
    StorageResourceId destResource = StorageResourceId.fromObjectName(destination.toString());
    List<String> sourceObjects =
        Lists.transform(
            sources,
            new Function<URI, String>() {
              @Override
              public String apply(URI uri) {
                return StorageResourceId.fromObjectName(uri.toString()).getObjectName();
              }
            });
    gcs.compose(
        destResource.getBucketName(), sourceObjects, destResource.getObjectName(), contentType);
  }

  /**
   * Renames the given path without checking any parameters.
   *
   * GCS does not support atomic renames therefore a rename is
   * implemented as copying source metadata to destination and then
   * deleting source metadata. Note that only the metadata is copied
   * and not the content of any file.
   */
  private void renameInternal(FileInfo srcInfo, URI dst)
      throws IOException {

    // List of individual paths to rename; we will try to carry out the copies in this list's
    // order.
    List<URI> srcItemNames = new ArrayList<>();

    // Mapping from each src to its respective dst.
    Map<URI, URI> dstItemNames = new HashMap<>();

    if (srcInfo.isDirectory()) {
      srcItemNames = listFileNames(srcInfo, true);

      // Sort src items so that parent directories appear before their children.
      // That allows us to copy parent directories before we copy their children.
      Collections.sort(srcItemNames, pathComparator);

      // Create the destination directory.
      dst = FileInfo.convertToDirectoryPath(dst);
      mkdir(dst);

      // Create a list of sub-items to copy.
      String prefix = srcInfo.getPath().toString();
      for (URI srcItemName : srcItemNames) {
        String relativeItemName = srcItemName.toString().substring(prefix.length());
        URI dstItemName = dst.resolve(relativeItemName);
        dstItemNames.put(srcItemName, dstItemName);
      }

    } else {
      srcItemNames.add(srcInfo.getPath());
      dstItemNames.put(srcInfo.getPath(), dst);
    }
    Preconditions.checkState(srcItemNames.size() == dstItemNames.size(),
        "srcItemNames.size() != dstItemNames.size(), '%s' vs '%s'", srcItemNames, dstItemNames);

    if (srcItemNames.size() > 0) {

      String srcBucketName = null;
      String dstBucketName = null;
      List<String> srcObjectNames = new ArrayList<>();
      List<String> dstObjectNames = new ArrayList<>();

      // Prepare list of items to copy.
      for (URI srcItemName : srcItemNames) {
        StorageResourceId resourceId;

        resourceId = validatePathAndGetId(srcItemName, true);
        srcBucketName = resourceId.getBucketName();
        String srcObjectName = resourceId.getObjectName();
        srcObjectNames.add(srcObjectName);

        resourceId = validatePathAndGetId(dstItemNames.get(srcItemName), true);
        dstBucketName = resourceId.getBucketName();
        String dstObjectName = resourceId.getObjectName();
        dstObjectNames.add(dstObjectName);
      }

      // Perform copy.
      gcs.copy(srcBucketName, srcObjectNames, dstBucketName, dstObjectNames);

      // So far, only the destination directories are updated. Only do those now:
      List<URI> destinationUris = new ArrayList<>(dstObjectNames.size());
      for (String dstObjectName : dstObjectNames) {
        destinationUris.add(getPath(dstBucketName, dstObjectName));
      }
      tryUpdateTimestampsForParentDirectories(destinationUris, destinationUris);
    }

    List<URI> bucketsToDelete = new ArrayList<>();

    if (srcInfo.isDirectory()) {
      if (srcInfo.getItemInfo().isBucket()) {
        bucketsToDelete.add(srcInfo.getPath());
      } else {
        // If src is a directory then srcItemNames does not contain its own name,
        // therefore add it to the list before we delete items in the list.
        srcItemNames.add(srcInfo.getPath());
      }
    }

    // Delete the items we successfully copied.
    deleteInternal(srcItemNames, bucketsToDelete);
  }

  /**
   * If the given item is a directory then the paths of its immediate
   * children are returned, otherwise the path of the given item is returned.
   *
   * @param fileInfo FileInfo of an item.
   * @return Paths of children (if directory) or self path.
   * @throws IOException
   */
  public List<URI> listFileNames(FileInfo fileInfo)
      throws IOException {
    return listFileNames(fileInfo, false);
  }

  /**
   * If the given item is a directory then the paths of its
   * children are returned, otherwise the path of the given item is returned.
   *
   * @param fileInfo FileInfo of an item.
   * @param recursive If true, path of all children are returned;
   *                  else, only immediate children are returned.
   * @return Paths of children (if directory) or self path.
   * @throws IOException
   */
  public List<URI> listFileNames(FileInfo fileInfo, boolean recursive)
      throws IOException {

    Preconditions.checkNotNull(fileInfo);
    URI path = fileInfo.getPath();
    LOG.debug("listFileNames({})", path);
    List<URI> paths = new ArrayList<>();
    List<String> childNames;

    // If it is a directory, obtain info about its children.
    if (fileInfo.isDirectory()) {
      if (fileInfo.exists()) {
        if (fileInfo.isGlobalRoot()) {
          childNames = gcs.listBucketNames();

          // Obtain path for each child.
          for (String childName : childNames) {
            URI childPath = getPath(childName);
            paths.add(childPath);
            LOG.debug("listFileNames: added: {}", childPath);
          }
        } else {
          // A null delimiter asks GCS to return all objects with a given prefix,
          // regardless of their 'directory depth' relative to the prefix;
          // that is what we want for a recursive list. On the other hand,
          // when a delimiter is specified, only items with relative depth
          // of 1 are returned.
          String delimiter = recursive ? null : GoogleCloudStorage.PATH_DELIMITER;

          // Obtain paths of children.
          childNames = gcs.listObjectNames(
              fileInfo.getItemInfo().getBucketName(),
              fileInfo.getItemInfo().getObjectName(),
              delimiter);

          // Obtain path for each child.
          for (String childName : childNames) {
            URI childPath = getPath(fileInfo.getItemInfo().getBucketName(), childName);
            paths.add(childPath);
            LOG.debug("listFileNames: added: {}", childPath);
          }
        }
      }
    } else {
      paths.add(path);
      LOG.debug("listFileNames: added single original path since !isDirectory(): {}", path);
    }

    return paths;
  }

  /**
   * Checks that {@code path} doesn't already exist as a directory object, and if so, performs
   * an object listing using the full path as the match prefix so that if there are any objects
   * that imply {@code path} is a parent directory, we will discover its existence as a returned
   * GCS 'prefix'. In such a case, the directory object will be explicitly created.
   *
   * @return true if a repair was successfully made, false if a repair was unnecessary or failed.
   */
  public boolean repairPossibleImplicitDirectory(URI path)
      throws IOException {
    LOG.debug("repairPossibleImplicitDirectory({})", path);
    Preconditions.checkNotNull(path);

    // First, obtain information about the given path.
    FileInfo pathInfo = getFileInfo(path);

    pathInfo = repairPossibleImplicitDirectory(pathInfo);

    if (pathInfo.exists()) {
      // Definitely didn't exist before, and now it does exist.
      LOG.debug("Successfully repaired path '{}'", path);
      return true;
    } else {
      LOG.debug("Repair claimed to succeed, but somehow failed for path '{}'", path);
      return false;
    }
  }

  /**
   * Helper for repairing possible implicit directories, taking a previously obtained FileInfo
   * and returning a re-fetched FileInfo after attemping the repair. The returned FileInfo
   * may still report !exists() if the repair failed.
   */
  private FileInfo repairPossibleImplicitDirectory(FileInfo pathInfo)
      throws IOException {
    if (pathInfo.exists()) {
      // It already exists, so there's nothing to repair; there must have been a mistake.
      return pathInfo;
    }

    if (pathInfo.isGlobalRoot() || pathInfo.getItemInfo().isBucket()
        || pathInfo.getItemInfo().getObjectName().equals(GoogleCloudStorage.PATH_DELIMITER)) {
      // Implicit directories are only applicable for non-trivial object names.
      return pathInfo;
    }

    // TODO(user): Refactor the method name and signature to make this less hacky; the logic of
    // piggybacking on auto-repair within listObjectInfo is sound because listing with prefixes
    // is a natural prerequisite for verifying that an implicit directory indeed exists. We just
    // need to make it more clear that the method is actually "list and maybe repair".
    try {
      gcs.listObjectInfo(
          pathInfo.getItemInfo().getBucketName(),
          FileInfo.convertToFilePath(pathInfo.getItemInfo().getObjectName()),
          GoogleCloudStorage.PATH_DELIMITER);
    } catch (IOException ioe) {
      LOG.error("Got exception trying to listObjectInfo on " + pathInfo, ioe);
      // It's possible our repair succeeded anyway.
    }

    pathInfo = getFileInfo(pathInfo.getPath());
    return pathInfo;
  }

  /**
   * Equivalent to a recursive listing of {@code prefix}, except that {@code prefix} doesn't
   * have to represent an actual object but can just be a partial prefix string, and there
   * is no auto-repair of implicit directories since we can't detect implicit directories
   * without listing by 'delimiter'. The 'authority' component of the {@code prefix} *must*
   * be the complete authority, however; we can only list prefixes of *objects*, not buckets.
   *
   * @param prefix the prefix to use to list all matching objects.
   */
  public List<FileInfo> listAllFileInfoForPrefix(URI prefix)
      throws IOException {
    LOG.debug("listAllFileInfoForPrefix({})", prefix);
    Preconditions.checkNotNull(prefix);

    StorageResourceId prefixId = validatePathAndGetId(prefix, true);
    Preconditions.checkState(
        !prefixId.isRoot(), "Prefix must not be global root, got '%s'", prefix);
    // Use 'null' for delimiter to get full 'recursive' listing.
    List<GoogleCloudStorageItemInfo> itemInfos = gcs.listObjectInfo(
        prefixId.getBucketName(), prefixId.getObjectName(), null);
    List<FileInfo> fileInfos = FileInfo.fromItemInfos(itemInfos);
    Collections.sort(fileInfos, fileInfoPathComparator);
    return fileInfos;
  }

  /**
   * If the given path points to a directory then the information about its
   * children is returned, otherwise information about the given file is returned.
   *
   * Note:
   * This function is expensive to call, especially for a directory with many
   * children. Use the alternative
   * {@link GoogleCloudStorageFileSystem#listFileNames(FileInfo)} if you only need
   * names of children and no other attributes.
   *
   * @param path Given path.
   * @param enableAutoRepair if true, attempt to repair implicit directories when detected.
   * @return Information about a file or children of a directory.
   * @throws FileNotFoundException if the given path does not exist.
   * @throws IOException
   */
  public List<FileInfo> listFileInfo(URI path, boolean enableAutoRepair)
      throws IOException {
    LOG.debug("listFileInfo({}, {})", path, enableAutoRepair);
    Preconditions.checkNotNull(path);

    URI dirPath = FileInfo.convertToDirectoryPath(path);
    List<FileInfo> baseAndDirInfos = getFileInfosRaw(ImmutableList.of(path, dirPath));
    Preconditions.checkState(
        baseAndDirInfos.size() == 2, "Expected baseAndDirInfos.size() == 2, got %s",
        baseAndDirInfos.size());

    // If the non-directory object exists, return a single-element list directly.
    if (!baseAndDirInfos.get(0).isDirectory() && baseAndDirInfos.get(0).exists()) {
      List<FileInfo> listedInfo = new ArrayList<>();
      listedInfo.add(baseAndDirInfos.get(0));
      return listedInfo;
    }

    // The second element is definitely a directory-path FileInfo.
    FileInfo dirInfo = baseAndDirInfos.get(1);
    if (!dirInfo.exists()) {
      if (enableAutoRepair) {
        dirInfo = repairPossibleImplicitDirectory(dirInfo);
      } else if (options.getCloudStorageOptions()
                  .isInferImplicitDirectoriesEnabled()) {
        StorageResourceId dirId = dirInfo.getItemInfo().getResourceId();
        if (!dirInfo.isDirectory()) {
          dirId = FileInfo.convertToDirectoryPath(dirId);
        }
        dirInfo = FileInfo.fromItemInfo(getInferredItemInfo(dirId));
      }
    }

    // Still doesn't exist after attempted repairs (or repairs were disabled).
    if (!dirInfo.exists()) {
      throw getFileNotFoundException(path);
    }

    List<GoogleCloudStorageItemInfo> itemInfos;
    if (dirInfo.isGlobalRoot()) {
      itemInfos = gcs.listBucketInfo();
    } else {
      itemInfos = gcs.listObjectInfo(
          dirInfo.getItemInfo().getBucketName(),
          dirInfo.getItemInfo().getObjectName(),
          GoogleCloudStorage.PATH_DELIMITER);
    }
    List<FileInfo> fileInfos = FileInfo.fromItemInfos(itemInfos);
    Collections.sort(fileInfos, fileInfoPathComparator);
    return fileInfos;
  }

  /**
   * Gets information about the given path item.
   *
   * @param path The path we want information about.
   * @return Information about the given path item.
   * @throws IOException
   */
  public FileInfo getFileInfo(URI path)
      throws IOException {
    LOG.debug("getFileInfo({})", path);
    Preconditions.checkArgument(path != null, "path must not be null");

    // Validate the given path. true == allow empty object name.
    // One should be able to get info about top level directory (== bucket),
    // therefore we allow object name to be empty.
    StorageResourceId resourceId = validatePathAndGetId(path, true);
    GoogleCloudStorageItemInfo itemInfo = gcs.getItemInfo(resourceId);
    // TODO(user): Here and below, just request foo and foo/ simultaneously in the same batch
    // request and choose the relevant one.
    if (!itemInfo.exists() && !FileInfo.isDirectory(itemInfo)) {
      // If the given file does not exist, see if a directory of
      // the same name exists.
      StorageResourceId newResourceId = FileInfo.convertToDirectoryPath(resourceId);
      LOG.debug("getFileInfo({}) : not found. trying: {}", path, newResourceId.toString());
      GoogleCloudStorageItemInfo newItemInfo = gcs.getItemInfo(newResourceId);
      // Only swap out the old not-found itemInfo if the "converted" itemInfo actually exists; if
      // both forms do not exist, we will just go with the original non-converted itemInfo.
      if (newItemInfo.exists()) {
        LOG.debug(
            "getFileInfo: swapping not-found info: %s for converted info: %s",
            itemInfo, newItemInfo);
        itemInfo = newItemInfo;
        resourceId = newResourceId;
      }
    }

    if (!itemInfo.exists()
        && options.getCloudStorageOptions().isInferImplicitDirectoriesEnabled()
        && !itemInfo.isRoot()
        && !itemInfo.isBucket()) {
      StorageResourceId newResourceId = resourceId;
      if (!FileInfo.isDirectory(itemInfo)) {
        newResourceId = FileInfo.convertToDirectoryPath(resourceId);
      }
      LOG.debug("getFileInfo({}) : still not found, trying inferred: {}",
          path, newResourceId.toString());
      GoogleCloudStorageItemInfo newItemInfo = getInferredItemInfo(resourceId);
      if (newItemInfo.exists()) {
        LOG.debug(
            "getFileInfo: swapping not-found info: %s for inferred info: %s",
            itemInfo, newItemInfo);
        itemInfo = newItemInfo;
        resourceId = newResourceId;
      }
    }

    FileInfo fileInfo = FileInfo.fromItemInfo(itemInfo);
    LOG.debug("getFileInfo: {}", fileInfo);
    return fileInfo;
  }

  /**
   * Gets information about each path in the given list; more efficient than calling getFileInfo()
   * on each path individually in a loop.
   *
   * @param paths List of paths.
   * @return Information about each path in the given list.
   * @throws IOException
   */
  public List<FileInfo> getFileInfos(List<URI> paths)
      throws IOException {
    LOG.debug("getFileInfos(list)");
    Preconditions.checkArgument(paths != null, "paths must not be null");

    // First, parse all the URIs into StorageResourceIds while validating them.
    List<StorageResourceId> resourceIdsForPaths = new ArrayList<>();
    for (URI path : paths) {
      resourceIdsForPaths.add(validatePathAndGetId(path, true));
    }

    // Call the bulk getItemInfos method to retrieve per-id info.
    List<GoogleCloudStorageItemInfo> itemInfos = gcs.getItemInfos(resourceIdsForPaths);

    // Possibly re-fetch for "not found" items, which may require implicit casting to directory
    // paths (e.g., StorageObject that lacks a trailing slash). Hold mapping from post-conversion
    // StorageResourceId to the index of itemInfos the new item will replace.
    // NB: HashMap here is required; if we wish to use TreeMap we must implement Comparable in
    // StorageResourceId.
    // TODO(user): Use HashMultimap if it ever becomes possible for the input to list the same
    // URI multiple times and actually expects the returned list to list those duplicate values
    // the same multiple times.
    Map<StorageResourceId, Integer> convertedIdsToIndex = new HashMap<>();
    for (int i = 0; i < itemInfos.size(); ++i) {
      if (!itemInfos.get(i).exists() && !FileInfo.isDirectory(itemInfos.get(i))) {
        StorageResourceId convertedId =
            FileInfo.convertToDirectoryPath(itemInfos.get(i).getResourceId());
        LOG.debug("getFileInfos({}) : not found. trying: {}",
            itemInfos.get(i).getResourceId(), convertedId);
        convertedIdsToIndex.put(convertedId, i);
      }
    }

    // If we found potential items needing re-fetch after converting to a directory path, we issue
    // a new bulk fetch and then patch the returned items into their respective indices in the list.
    if (!convertedIdsToIndex.isEmpty()) {
      List<StorageResourceId> convertedResourceIds =
           new ArrayList<>(convertedIdsToIndex.keySet());
      List<GoogleCloudStorageItemInfo> convertedInfos = gcs.getItemInfos(convertedResourceIds);
      for (int i = 0; i < convertedResourceIds.size(); ++i) {
        if (convertedInfos.get(i).exists()) {
          int replaceIndex = convertedIdsToIndex.get(convertedResourceIds.get(i));
          LOG.debug("getFileInfos: swapping not-found info: {} for converted info: {}",
              itemInfos.get(replaceIndex), convertedInfos.get(i));
          itemInfos.set(replaceIndex, convertedInfos.get(i));
        }
      }
    }

    // If we are inferring directories and we still have some items that
    // are not found, run through the items again looking for inferred
    // directories.
    if (options.getCloudStorageOptions().isInferImplicitDirectoriesEnabled()) {
      Map<StorageResourceId, Integer> inferredIdsToIndex = new HashMap<>();
      for (int i = 0; i < itemInfos.size(); ++i) {
        if (!itemInfos.get(i).exists()) {
          StorageResourceId inferredId = itemInfos.get(i).getResourceId();
          if (!FileInfo.isDirectory(itemInfos.get(i))) {
            inferredId = FileInfo.convertToDirectoryPath(inferredId);
          }
          LOG.debug("getFileInfos({}) : still not found, trying inferred: {}",
              itemInfos.get(i).getResourceId(), inferredId);
          inferredIdsToIndex.put(inferredId, i);
        }
      }

      if (!inferredIdsToIndex.isEmpty()) {
        List<StorageResourceId> inferredResourceIds =
             new ArrayList<>(inferredIdsToIndex.keySet());
        List<GoogleCloudStorageItemInfo> inferredInfos =
            getInferredItemInfos(inferredResourceIds);
        for (int i = 0; i < inferredResourceIds.size(); ++i) {
          if (inferredInfos.get(i).exists()) {
            int replaceIndex =
                inferredIdsToIndex.get(inferredResourceIds.get(i));
            LOG.debug("getFileInfos: swapping not-found info: "
                + "%s for inferred info: %s",
                itemInfos.get(replaceIndex), inferredInfos.get(i));
            itemInfos.set(replaceIndex, inferredInfos.get(i));
          }
        }
      }
    }

    // Finally, plug each GoogleCloudStorageItemInfo into a respective FileInfo before returning.
    return FileInfo.fromItemInfos(itemInfos);
  }

  /**
   * Efficiently gets info about each path in the list without performing auto-retry with casting
   * paths to "directory paths". This means that even if "foo/" exists, fetching "foo" will return
   * a !exists() info, unlike {@link #getFileInfos(List<URI>)}.
   *
   * @param paths List of paths.
   * @return Information about each path in the given list.
   * @throws IOException
   */
  private List<FileInfo> getFileInfosRaw(List<URI> paths)
      throws IOException {
    LOG.debug("getFileInfosRaw({})", paths.toString());
    Preconditions.checkArgument(paths != null, "paths must not be null");

    // First, parse all the URIs into StorageResourceIds while validating them.
    List<StorageResourceId> resourceIdsForPaths = new ArrayList<>();
    for (URI path : paths) {
      resourceIdsForPaths.add(validatePathAndGetId(path, true));
    }

    // Call the bulk getItemInfos method to retrieve per-id info.
    List<GoogleCloudStorageItemInfo> itemInfos = gcs.getItemInfos(resourceIdsForPaths);

    // Finally, plug each GoogleCloudStorageItemInfo into a respective FileInfo before returning.
    return FileInfo.fromItemInfos(itemInfos);
  }

  /**
   * Gets information about an inferred object that represents a directory
   * but which is not explicitly represented in GCS.
   *
   * @param dirId identifies either root, a Bucket, or a StorageObject
   * @return information about the given item
   * @throws IOException on IO error
   */
  private GoogleCloudStorageItemInfo getInferredItemInfo(
      StorageResourceId dirId) throws IOException {

    if (dirId.isRoot() || dirId.isBucket()) {
      return GoogleCloudStorageImpl.createItemInfoForNotFound(dirId);
    }

    StorageResourceId bucketId = new StorageResourceId(dirId.getBucketName());
    if (!gcs.getItemInfo(bucketId).exists()) {
      // If the bucket does not exist, don't try to look for children.
      return GoogleCloudStorageImpl.createItemInfoForNotFound(dirId);
    }

    dirId = FileInfo.convertToDirectoryPath(dirId);

    String bucketName = dirId.getBucketName();
    // We have ensured that the path ends in the delimiter,
    // so now we can just use that path as the prefix.
    String objectNamePrefix = dirId.getObjectName();
    String delimiter = GoogleCloudStorage.PATH_DELIMITER;

    List<String> objectNames = gcs.listObjectNames(
        bucketName, objectNamePrefix, delimiter, 1);

    if (objectNames.size() > 0) {
      // At least one object with that prefix exists, so infer a directory.
      return GoogleCloudStorageImpl.createItemInfoForInferredDirectory(dirId);
    } else {
      return GoogleCloudStorageImpl.createItemInfoForNotFound(dirId);
    }
  }

  /**
   * Gets information about multiple inferred objects and/or buckets.
   * Items that are "not found" will still have an entry in the returned list;
   * exists() will return false for these entries.
   *
   * @param resourceIds names of the GCS StorageObjects or
   *        Buckets for which to retrieve info.
   * @return information about the given resourceIds.
   * @throws IOException on IO error
   */
  private List<GoogleCloudStorageItemInfo> getInferredItemInfos(
      List<StorageResourceId> resourceIds) throws IOException {
    List<GoogleCloudStorageItemInfo> itemInfos = new ArrayList<>();
    for (int i = 0; i < resourceIds.size(); ++i) {
      itemInfos.add(getInferredItemInfo(resourceIds.get(i)));
    }
    return itemInfos;
  }

  /**
   * Releases resources used by this instance.
   */
  public void close() {
    if (gcs != null) {
      LOG.debug("close()");
      try {
        gcs.close();
      } finally {
        gcs = null;
      }
    }

    if (updateTimestampsExecutor != null) {
      updateTimestampsExecutor.shutdown();
      try {
        if (!updateTimestampsExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
          LOG.warn("Forcibly shutting down timestamp update threadpool.");
          updateTimestampsExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        LOG.warn("Interrupted awaiting timestamp update threadpool.");
      }
      updateTimestampsExecutor = null;
    }
  }

  /**
   * Creates a directory at the specified path.
   *
   * There are two conventions for using objects as directories in GCS.
   * 1. An object of zero size with name ending in /
   * 2. An object of zero size with name ending in _$folder$
   *
   * #1 is the recommended convention by the GCS team. We use it when
   * creating a directory.
   *
   * However, some old tools still use #2. We will decide based on customer
   * use cases whether to support #2 as well. For now, we only support #1.
   *
   * Note that a bucket is always considered a directory.
   * Doesn't create parent directories; normal use cases should only call mkdirs().
   */
  @VisibleForTesting
  public void mkdir(URI path)
      throws IOException {

    LOG.debug("mkdir({})", path);
    Preconditions.checkNotNull(path);
    Preconditions.checkArgument(!path.equals(GCS_ROOT), "Cannot create root directory.");

    StorageResourceId resourceId = validatePathAndGetId(path, true);

    // If this is a top level directory, create the corresponding bucket.
    if (resourceId.isBucket()) {
      gcs.create(resourceId.getBucketName());
      return;
    }

    // Ensure that the path looks like a directory path.
    resourceId = FileInfo.convertToDirectoryPath(resourceId);

    // Not a top-level directory, create 0 sized object.
    gcs.createEmptyObject(resourceId);

    tryUpdateTimestampsForParentDirectories(ImmutableList.of(path), ImmutableList.<URI>of());
  }

  /**
   * For each listed modified object, attempt to update the modification time
   * of the parent directory.
   * @param modifiedObjects The objects that have been modified
   * @param excludedParents A list of parent directories that we shouldn't attempt to update.
   */
  protected void updateTimestampsForParentDirectories(
      List<URI> modifiedObjects, List<URI> excludedParents) throws IOException {
    LOG.debug("updateTimestampsForParentDirectories({}, {})", modifiedObjects, excludedParents);

    Predicate<String> shouldIncludeInTimestampUpdatesPredicate =
        options.getShouldIncludeInTimestampUpdatesPredicate();
    Set<URI> excludedParentPathsSet = new HashSet<>(excludedParents);

    HashSet<URI> parentUrisToUpdate = new HashSet<>(modifiedObjects.size());
    for (URI modifiedObjectUri : modifiedObjects) {
      URI parentPathUri = getParentPath(modifiedObjectUri);
      if (!excludedParentPathsSet.contains(parentPathUri)
          && shouldIncludeInTimestampUpdatesPredicate.apply(parentPathUri.getPath())) {
        parentUrisToUpdate.add(parentPathUri);
      }
    }

    Map<String, byte[]> modificationAttributes = new HashMap<>();
    FileInfo.addModificationTimeToAttributes(modificationAttributes, Clock.SYSTEM);

    List<UpdatableItemInfo> itemUpdates = new ArrayList<>(parentUrisToUpdate.size());

    for (URI parentUri : parentUrisToUpdate) {
      StorageResourceId resourceId = validatePathAndGetId(parentUri, true);
      if (!resourceId.isBucket() && !resourceId.isRoot()) {
        itemUpdates.add(new UpdatableItemInfo(resourceId, modificationAttributes));
      }
    }

    if (!itemUpdates.isEmpty()) {
      gcs.updateItems(itemUpdates);
    } else {
      LOG.debug("All paths were excluded from directory timestamp updating.");
    }
  }

  /**
   * For each listed modified object, attempt to update the modification time
   * of the parent directory.
   *
   * This method will log & swallow exceptions thrown by the GCSIO layer.
   * @param modifiedObjects The objects that have been modified
   * @param excludedParents A list of parent directories that we shouldn't attempt to update.
   */
  protected void tryUpdateTimestampsForParentDirectories(
      final List<URI> modifiedObjects, final List<URI> excludedParents) {
    LOG.debug("tryUpdateTimestampsForParentDirectories({}, {})", modifiedObjects, excludedParents);

    // If we're calling tryUpdateTimestamps, we don't actually care about the results. Submit
    // these requests via a background thread and continue on.
    try {
      updateTimestampsExecutor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            updateTimestampsForParentDirectories(modifiedObjects, excludedParents);
          } catch (IOException ioe) {
            LOG.debug("Exception caught when trying to update parent directory timestamps.", ioe);
          }
        }
      });
    } catch (RejectedExecutionException ree) {
      LOG.debug("Exhausted threadpool and queue space while updating parent timestamps", ree);
    }
  }

  /**
   * For objects whose name looks like a path (foo/bar/zoo),
   * returns intermediate sub-paths.
   *
   * for example,
   * foo/bar/zoo => returns: (foo/, foo/bar/)
   * foo => returns: ()
   *
   * @param objectName Name of an object.
   * @return List of sub-directory like paths.
   */
  static List<String> getSubDirs(String objectName) {
    List<String> subdirs = new ArrayList<>();
    if (!Strings.isNullOrEmpty(objectName)) {
      // Create a list of all subdirs.
      // for example,
      // foo/bar/zoo => (foo/, foo/bar/)
      int currentIndex = 0;
      while (currentIndex < objectName.length()) {
        int index = objectName.indexOf('/', currentIndex);
        if (index < 0) {
          break;
        }
        subdirs.add(objectName.substring(0, index + 1));
        currentIndex = index + 1;
      }
    }

    return subdirs;
  }

  /**
   * Validates the given URI and if valid, returns the associated StorageResourceId.
   *
   * @param path The GCS URI to validate.
   * @param allowEmptyObjectName If true, a missing object name is not considered invalid.
   * @return a StorageResourceId that may be the GCS root, a Bucket, or a StorageObject.
   */
  public static StorageResourceId validatePathAndGetId(URI path, boolean allowEmptyObjectName) {
    LOG.debug("validatePathAndGetId('{}', {})", path, allowEmptyObjectName);
    Preconditions.checkNotNull(path);

    if (!SCHEME.equals(path.getScheme())) {
      throw new IllegalArgumentException(
          "Google Cloud Storage path supports only '" + SCHEME + "' scheme, instead got '"
          + path.getScheme() + "'.");
    }

    String bucketName;
    String objectName;

    if (path.equals(GCS_ROOT)) {
      return StorageResourceId.ROOT;
    } else {
      bucketName = path.getAuthority();

      // We want not only the raw path, but also any "query" or "fragment" at the end; URI doesn't
      // have a method for "everything past the authority", so instead we start with the entire
      // scheme-specific part and strip off the authority.
      String schemeSpecificPart = path.getRawSchemeSpecificPart();
      Preconditions.checkState(schemeSpecificPart.startsWith("//" + bucketName),
          "Expected schemeSpecificStart to start with '//%s', instead got '%s'",
          bucketName, schemeSpecificPart);
      objectName = schemeSpecificPart.substring(2 + bucketName.length());

      bucketName = validateBucketName(bucketName);
      objectName = validateObjectName(objectName, allowEmptyObjectName);

      // TODO(user): Pull the logic for checking empty object name out of validateObjectName into
      // here.
      if (Strings.isNullOrEmpty(objectName)) {
        return new StorageResourceId(bucketName);
      } else {
        return new StorageResourceId(bucketName, objectName);
      }
    }
  }

  /**
   * Validate the given bucket name to make sure that it can be used
   * as a part of a file system path.
   *
   * Note: this is not designed to duplicate the exact checks that GCS
   * would perform on the server side. We make some checks
   * that are relevant to using GCS as a file system.
   *
   * @param bucketName Bucket name to check.
   */
  static String validateBucketName(String bucketName) {

    // If the name ends with /, remove it.
    bucketName = FileInfo.convertToFilePath(bucketName);

    if (Strings.isNullOrEmpty(bucketName)) {
      throw new IllegalArgumentException(
          "Google Cloud Storage bucket name cannot be empty.");
    }

    if (bucketName.indexOf('/') >= 0) {
      throw new IllegalArgumentException(
          "Google Cloud Storage bucket name must not contain '/' character.");
    }

    return bucketName;
  }

  /**
   * Validate the given object name to make sure that it can be used
   * as a part of a file system path.
   *
   * Note: this is not designed to duplicate the exact checks that GCS
   * would perform on the server side. We make some checks
   * that are relevant to using GCS as a file system.
   *
   * @param objectName Object name to check.
   * @param allowEmptyObjectName If true, a missing object name is not considered invalid.
   */
  static String validateObjectName(String objectName, boolean allowEmptyObjectName) {
    LOG.debug("validateObjectName('{}', {})", objectName, allowEmptyObjectName);

    String objectNameMessage = "Google Cloud Storage path must include non-empty object name.";

    if (objectName == null) {
      if (allowEmptyObjectName) {
        objectName = "";
      } else {
        throw new IllegalArgumentException(objectNameMessage);
      }
    }

    // We want objectName to look like a traditional file system path,
    // therefore, disallow objectName with consecutive '/' chars.
    for (int i = 0; i < (objectName.length() - 1); i++) {
      if (objectName.charAt(i) == '/') {
        if (objectName.charAt(i + 1) == '/') {
          throw new IllegalArgumentException(String.format(
              "Google Cloud Storage path must not have consecutive '/' characters, got '%s'",
              objectName));
        }
      }
    }

    // Remove leading '/' if it exists.
    if (objectName.startsWith(GoogleCloudStorage.PATH_DELIMITER)) {
      objectName = objectName.substring(1);
    }

    if ((objectName.length() == 0) && !allowEmptyObjectName) {
      throw new IllegalArgumentException(objectNameMessage);
    }

    LOG.debug("validateObjectName -> '{}'", objectName);
    return objectName;
  }

  /**
   * Constructs and returns full path for the given bucket name.
   */
  public static URI getPath(String bucketName) {
    return getPath(bucketName, null, true);
  }

  /**
   * Constructs and returns full path for the given object name.
   */
  public static URI getPath(String bucketName, String objectName) {
    return getPath(bucketName, objectName, false);
  }

  /**
   * Constructs and returns full path for the given object name.
   */
  public static URI getPath(String bucketName, String objectName, boolean allowEmptyObjectName) {
    if (allowEmptyObjectName && (bucketName == null) && (objectName == null)) {
      return GCS_ROOT;
    }

    bucketName = validateBucketName(bucketName);
    objectName = validateObjectName(objectName, allowEmptyObjectName);

    URI pathUri = null;
    String path = SCHEME + "://" + bucketName + GoogleCloudStorage.PATH_DELIMITER + objectName;
    try {
      pathUri = new URI(path);
    } catch (URISyntaxException e) {
      // This should be very rare given the earlier checks.
      String msg = String.format("Invalid bucket name (%s) or object name (%s)",
          bucketName, objectName);
      LOG.error(msg, e);
      throw new IllegalArgumentException(msg, e);
    }

    return pathUri;
  }

  /**
   * Gets the leaf item of the given path.
   */
  static String getItemName(URI path) {

    Preconditions.checkNotNull(path);

    // There is no leaf item for the root path.
    if (path.equals(GCS_ROOT)) {
      return null;
    }

    StorageResourceId resourceId = validatePathAndGetId(path, true);

    if (resourceId.isBucket()) {
      return resourceId.getBucketName();
    } else {
      int index;
      if (FileInfo.objectHasDirectoryPath(resourceId.getObjectName())) {
        index = resourceId.getObjectName().lastIndexOf(
            GoogleCloudStorage.PATH_DELIMITER, resourceId.getObjectName().length() - 2);
      } else {
        index = resourceId.getObjectName().lastIndexOf(GoogleCloudStorage.PATH_DELIMITER);
      }
      if (index < 0) {
        return resourceId.getObjectName();
      } else {
        return resourceId.getObjectName().substring(index + 1);
      }
    }
  }

  /**
   * Gets the parent directory of the given path.
   *
   * @param path Path to convert.
   * @return Path of parent directory of the given item or null for root path.
   */
  public static URI getParentPath(URI path) {
    Preconditions.checkNotNull(path);

    // Root path has no parent.
    if (path.equals(GCS_ROOT)) {
      return null;
    }

    StorageResourceId resourceId = validatePathAndGetId(path, true);

    if (resourceId.isBucket()) {
      return GCS_ROOT;
    } else {
      int index;
      if (FileInfo.objectHasDirectoryPath(resourceId.getObjectName())) {
        index = resourceId.getObjectName().lastIndexOf(
            GoogleCloudStorage.PATH_DELIMITER, resourceId.getObjectName().length() - 2);
      } else {
        index = resourceId.getObjectName().lastIndexOf(GoogleCloudStorage.PATH_DELIMITER);
      }
      if (index < 0) {
        return getPath(resourceId.getBucketName());
      } else {
        return getPath(
            resourceId.getBucketName(), resourceId.getObjectName().substring(0, index + 1));
      }
    }
  }

  /**
   * Creates FileNotFoundException with a suitable message.
   */
  static FileNotFoundException getFileNotFoundException(URI path) {
    return new FileNotFoundException(
        String.format("Item not found: %s", path));
  }

  /**
   * Retrieve our internal gcs, for testing purposes only.
   */
  @VisibleForTesting
  GoogleCloudStorage getGcs() {
    return gcs;
  }
}
