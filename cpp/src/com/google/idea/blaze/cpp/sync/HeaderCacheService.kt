/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.cpp.sync

import com.google.idea.blaze.base.filecache.FileCache
import com.google.idea.blaze.base.command.buildresult.LocalFileArtifact
import com.google.idea.blaze.base.ideinfo.ArtifactLocation
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo
import com.google.idea.blaze.base.ideinfo.TargetKey
import com.google.idea.blaze.base.logging.LoggedDirectoryProvider
import com.google.idea.blaze.base.model.BlazeProjectData
import com.google.idea.blaze.base.model.primitives.ExecutionRootPath
import com.google.idea.blaze.base.projectview.ProjectViewSet
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.base.scope.Scope
import com.google.idea.blaze.base.scope.scopes.TimingScope
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager
import com.google.idea.blaze.base.sync.SyncMode
import com.google.idea.blaze.base.sync.aspects.BlazeBuildOutputs
import com.google.idea.blaze.base.sync.data.BlazeDataStorage
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectDataPath
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLockAbsence
import com.jetbrains.cidr.lang.OCFileTypeHelpers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*

private val LOG = logger<HeaderCacheService>()

private const val CACHE_DIRECTORY = "headerCache"

@Service(Service.Level.PROJECT)
@Suppress("UnstableApiUsage")
class HeaderCacheService(private val project: Project) {

  companion object {
    const val ENABLED_KEY: String = "bazel.cpp.header.cache.enabled"

    @JvmStatic
    fun of(project: Project): HeaderCacheService = project.service()

    @JvmStatic
    val enabled: Boolean
      get() = try {
        Registry.`is`(ENABLED_KEY)
      } catch (_: MissingResourceException) {
        false
      }
  }

  val cacheDirectory: Path by lazy {
    // TODO: do we need a read action here? is the lazy initialization a race?
    val importSettings = BlazeImportSettingsManager.getInstance(project).importSettings

    if (importSettings != null) {
      BlazeDataStorage.getProjectDataDir(importSettings).toPath().resolve(CACHE_DIRECTORY)
    } else {
      project.getProjectDataPath(CACHE_DIRECTORY)
    }
  }

  // tracks files which are actually stored in the cache
  private val cacheTracker: MutableSet<String> = mutableSetOf()

  private fun cacheDirectory(configurationId: String): Path {
    return cacheDirectory.resolve(configurationId.ifBlank { "default" })
  }

  private fun cacheDirectory(key: TargetKey): Path {
    return cacheDirectory(key.configuration())
  }

  @Synchronized
  @RequiresReadLockAbsence
  @RequiresBackgroundThread
  @Throws(IOException::class)
  private fun clear() {
    cacheTracker.clear()

    if (Files.exists(cacheDirectory)) {
      // On windows this could be replaced with a rename and asynchronous delete for better performance.
      NioFiles.deleteRecursively(cacheDirectory)
    }

    LOG.trace("cleared cc includes cache")
  }

  @Synchronized
  @RequiresReadLockAbsence
  @RequiresBackgroundThread
  fun refresh(projectData: BlazeProjectData, nonInc: Boolean) {
    if (nonInc) {
      clear()
    } else {
      // only reset in memory data on incremental sync
      cacheTracker.clear()
    }

    for ((key, target) in projectData.targetMap().map()) {
      refreshTarget(projectData, key, target)
    }
  }

  private fun refreshTarget(projectData: BlazeProjectData, key: TargetKey, target: TargetIdeInfo) {
    val info = target.getcIdeInfo() ?: return

    val targetCacheDirectory = cacheDirectory(key)

    for (header in info.compilationContext().headers()) {
      cacheArtifact(projectData, key, targetCacheDirectory, header, "header")
    }

    for (source in target.sources) {
      if (!isSourceOrHeaderFile(source)) continue
      cacheArtifact(projectData, key, targetCacheDirectory, source, "source")
    }
  }

  private fun cacheArtifact(
    projectData: BlazeProjectData,
    key: TargetKey,
    targetCacheDirectory: Path,
    location: ArtifactLocation,
    kind: String,
  ) {
    if (!isCacheable(location)) return

    // check if the file is already present in the cache
    val cacheKey = cacheKey(key, location)
    if (!cacheTracker.add(cacheKey)) return

    val path = resolveCachePath(targetCacheDirectory, key, location)

    try {
      Files.createDirectories(path.parent)

      // delete existing entry to handle type changes (symlink <-> regular file) on incremental sync
      Files.deleteIfExists(path)

      val artifact = projectData.artifactLocationDecoder().resolveOutput(location)

      // for local files, check if the file is a symlink (e.g. _virtual_includes)
      if (artifact is LocalFileArtifact) {
        val localPath = artifact.file.toPath()
        if (Files.isSymbolicLink(localPath) || isExternalSourceArtifact(location)) {
          // fall through to content-copy if symlink creation failed (e.g. Windows without Developer Mode)
          if (tryCreateSymlink(path, localPath.toRealPath())) return
        }
      }

      // content copy for regular (generated) files, remote files, or as a fallback when symlink creation fails
      artifact.inputStream.use { src ->
        Files.newOutputStream(
          path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        ).use { dst ->
          src.transferTo(dst)
        }
      }
    } catch (e: IOException) {
      cacheTracker.remove(cacheKey)
      LOG.warn("failed to cache $kind ${location.relativePath()} for ${key.label()} (${key.configuration()})", e)
    }
  }

  /**
   * Attempts to create a symbolic link at [link] pointing to [target].
   * Returns true if successful, false otherwise (e.g. on Windows without Developer Mode).
   */
  private fun tryCreateSymlink(link: Path, target: Path): Boolean {
    return try {
      Files.createSymbolicLink(link, target)
      true
    } catch (e: IOException) {
      LOG.debug("failed to create symlink $link -> $target, falling back to copy", e)
      try { Files.deleteIfExists(link) } catch (_: IOException) {}
      false
    }
  }

  @Synchronized
  fun resolve(target: TargetKey, executionRootPath: ExecutionRootPath): Optional<Path> {
    return resolve(target.configuration(), executionRootPath);
  }

  @Synchronized
  fun resolve(configurationId: String, executionRootPath: ExecutionRootPath): Optional<Path> {
    val path = executionRootPath.path()
    if (!isInBazelBin(path)) return Optional.empty()

    return Optional.of(
      if (path.nameCount <= 3) {
        cacheDirectory(configurationId)
      } else {
        cacheDirectory(configurationId).resolve(path.subpath(3, path.nameCount))
      }
    )
  }

  @Synchronized
  fun resolve(configurationId: String, location: ArtifactLocation): Optional<Path> {
    if (!isInBazelBin(location)) return Optional.empty()

    return Optional.of(resolveCachePath(cacheDirectory(configurationId), null, location))
  }

  @Synchronized
  fun resolve(target: TargetKey, location: ArtifactLocation): Optional<Path> {
    if (!isCacheable(location)) return Optional.empty()

    return Optional.of(resolveCachePath(cacheDirectory(target), target, location))
  }

  private fun cacheKey(key: TargetKey, location: ArtifactLocation): String {
    return key.configuration() + "/" + location.getExecutionRootRelativePath()
  }

  private fun isInBazelBin(path: Path): Boolean {
    return path.nameCount >= 3
        && path.getName(0).toString() == "bazel-out"
        && path.getName(2).toString() == "bin";
  }

  private fun isInBazelBin(location: ArtifactLocation): Boolean {
    return location.rootPath().isNotBlank() && isInBazelBin(Path.of(location.rootPath()))
  }

  private fun isCacheable(location: ArtifactLocation): Boolean {
    return isInBazelBin(location) || isExternalSourceArtifact(location)
  }

  private fun isExternalSourceArtifact(location: ArtifactLocation): Boolean {
    return location.isExternal && location.isSource
  }

  private fun isSourceOrHeaderFile(location: ArtifactLocation): Boolean {
    val fileName = Path.of(location.relativePath()).fileName.toString()
    return OCFileTypeHelpers.isSourceFile(fileName) || OCFileTypeHelpers.isHeaderFile(fileName)
  }

  private fun resolveCachePath(cacheDirectory: Path, key: TargetKey?, location: ArtifactLocation): Path {
    val root = Path.of(location.rootPath())

    // for external root paths (e.g. bazel-out/k8-fastbuild/bin/external/bazel_tools) the external prefix
    // (e.g. external/bazel_tools) needs to map into the cache as well
    val cacheRoot = if (isInBazelBin(root) && root.nameCount > 3) {
      cacheDirectory.resolve(root.subpath(3, root.nameCount))
    } else if (location.isExternal) {
      cacheDirectory.resolve("external").resolve(externalWorkspaceName(key, root))
    } else {
      cacheDirectory
    }

    return cacheRoot.resolve(location.relativePath())
  }

  private fun externalWorkspaceName(key: TargetKey?, root: Path): String {
    val workspaceName = key?.label()?.externalWorkspaceName()
    if (!workspaceName.isNullOrBlank()) return workspaceName

    val externalIndex = (0 until root.nameCount)
      .firstOrNull { root.getName(it).toString() == "external" }
    if (externalIndex != null && externalIndex + 1 < root.nameCount) {
      return root.getName(externalIndex + 1).toString()
    }

    return root.fileName?.toString() ?: "unknown"
  }
}

private class HeaderFileCache : FileCache {

  override fun getName(): String = "Header Cache"

  override fun onSync(
    project: Project,
    parentCtx: BlazeContext,
    projectViewSet: ProjectViewSet,
    projectData: BlazeProjectData,
    oldProjectData: BlazeProjectData?,
    syncMode: SyncMode,
  ) {
    if (!HeaderCacheService.enabled || !syncMode.involvesBlazeBuild()) return
    LOG.trace("refresh requested onSync: $syncMode")

    Scope.push(parentCtx) { ctx ->
      ctx.push(TimingScope(name, TimingScope.EventType.Other))
      HeaderCacheService.of(project).refresh(projectData, nonInc = syncMode == SyncMode.FULL)
    }
  }

  override fun refreshFiles(
    project: Project,
    context: BlazeContext,
    buildOutputs: BlazeBuildOutputs,
  ) {
    if (!HeaderCacheService.enabled) return
    LOG.trace("refresh files requested")

    val projectData = BlazeProjectDataManager.getInstance(project).getBlazeProjectData() ?: return
    HeaderCacheService.of(project).refresh(projectData, nonInc = false)
  }

  override fun initialize(project: Project) {}
}

private class HeaderCacheLoggedDirectory : LoggedDirectoryProvider {

  override fun getLoggedDirectory(project: Project): Optional<LoggedDirectoryProvider.LoggedDirectory> {
    if (!HeaderCacheService.enabled) return Optional.empty()

    return Optional.of(
      LoggedDirectoryProvider.LoggedDirectory.builder()
        .setPath(HeaderCacheService.of(project).cacheDirectory)
        .setOriginatingIdePart("CLwB Header Cache")
        .setPurpose("Cache headers and C++ sources from the execution root")
        .build()
    )
  }
}
