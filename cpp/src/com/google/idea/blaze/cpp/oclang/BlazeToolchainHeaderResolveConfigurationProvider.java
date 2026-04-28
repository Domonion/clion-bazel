/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.cpp.oclang;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.settings.BlazeImportSettings.ProjectType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.cidr.execution.CidrResolveConfigurationProvider;
import com.jetbrains.cidr.lang.CLanguageKind;
import com.jetbrains.cidr.lang.OCFileTypeHelpers;
import com.jetbrains.cidr.lang.daemon.OCFileScopeProvider;
import com.jetbrains.cidr.lang.daemon.ProjectSourceLocationKind;
import com.jetbrains.cidr.lang.preprocessor.OCResolveRootAndConfiguration;
import com.jetbrains.cidr.lang.preprocessor.OCResolveRootAndConfigurationProvider;
import com.jetbrains.cidr.lang.workspace.OCLibraryFileResolveConfigurationProvider;
import com.jetbrains.cidr.lang.workspace.OCCompilerSettings;
import com.jetbrains.cidr.lang.workspace.OCResolveConfiguration;
import com.jetbrains.cidr.lang.workspace.OCResolveConfigurations;
import com.jetbrains.cidr.lang.workspace.OCWorkspace;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchPath;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchRoot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import kotlin.coroutines.Continuation;
import javax.annotation.Nullable;

/**
 * Assigns Bazel-managed toolchain headers to the same C++ resolve configuration as their includers.
 *
 * <p>Bazel C++ toolchains often pass libc++ as a relative include root such as
 * {@code external/+cc_toolchains_extension+clang_toolchains-llvm_libcxx/include}. The regular
 * include resolver may open that header through the output-base external repository. Such files are
 * not target sources, so CLion otherwise parses them without the Bazel target's C++ switches.
 */
public class BlazeToolchainHeaderResolveConfigurationProvider
    implements OCLibraryFileResolveConfigurationProvider,
        OCResolveRootAndConfigurationProvider,
        CidrResolveConfigurationProvider,
        OCFileScopeProvider {

  private static final String TOOLCHAIN_EXTERNAL_FRAGMENT =
      "/external/+cc_toolchains_extension+clang_toolchains-";

  @Nullable
  @Override
  public Collection<OCResolveConfiguration> getResolveConfigurations(
      PsiFile file, ProgressIndicator indicator) {
    if (indicator != null) {
      indicator.checkCanceled();
    }
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return null;
    }

    ImmutableList<OCResolveConfiguration> configurations =
        getMatchingConfigurations(file.getProject(), virtualFile, indicator);
    return configurations.isEmpty() ? null : configurations;
  }

  @Override
  public Collection<OCResolveConfiguration> getAllResolveConfigurationsForFile(
      PsiFile file, @Nullable ProgressIndicator indicator) {
    if (indicator != null) {
      indicator.checkCanceled();
    }
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return ImmutableList.of();
    }
    return getMatchingConfigurations(file.getProject(), virtualFile, indicator);
  }

  @Override
  public Collection<VirtualFile> findRoots(Project project, VirtualFile file) {
    ImmutableList<OCResolveConfiguration> configurations =
        getMatchingConfigurations(project, file, /* indicator= */ null);
    if (configurations.isEmpty()) {
      return ImmutableList.of(file);
    }

    ImmutableSet.Builder<VirtualFile> roots = ImmutableSet.builder();
    for (OCResolveConfiguration configuration : configurations) {
      roots.addAll(configuration.getSources());
    }
    ImmutableSet<VirtualFile> result = roots.build();
    return result.isEmpty() ? ImmutableList.of(file) : result.asList();
  }

  @Nullable
  @Override
  public OCResolveRootAndConfiguration infer(VirtualFile file, Project project) {
    ImmutableList<OCResolveConfiguration> configurations =
        getMatchingConfigurations(project, file, /* indicator= */ null);
    if (configurations.isEmpty()) {
      return null;
    }

    Pair<OCResolveConfiguration, Boolean> selected =
        OCResolveConfigurations.findPreselectedOrSuitableConfiguration(project, configurations);
    OCResolveConfiguration configuration = selected != null ? selected.first : configurations.get(0);
    return configuration != null
        ? new OCResolveRootAndConfiguration(configuration, CLanguageKind.CPP, file)
        : null;
  }

  public static boolean isBazelToolchainHeader(VirtualFile file) {
    return hasToolchainExternalFragment(file.getPath());
  }

  static boolean isBazelToolchainHeaderInConfiguration(
      OCResolveConfiguration configuration, VirtualFile file) {
    return isPotentialHeader(file)
        && Blaze.getProjectType(configuration.getProject()) == ProjectType.ASPECT_SYNC
        && containsToolchainHeader(configuration, file);
  }

  static ImmutableList<OCResolveConfiguration> getMatchingConfigurations(
      Project project, VirtualFile file, @Nullable ProgressIndicator indicator) {
    if (Blaze.getProjectType(project) != ProjectType.ASPECT_SYNC || !isPotentialHeader(file)) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<OCResolveConfiguration> result = ImmutableList.builder();
    for (OCResolveConfiguration configuration : OCWorkspace.getInstance(project).getConfigurations()) {
      if (indicator != null) {
        indicator.checkCanceled();
      }
      if (containsToolchainHeader(configuration, file)) {
        result.add(configuration);
      }
    }
    return result.build();
  }

  private static boolean containsToolchainHeader(
      OCResolveConfiguration configuration, VirtualFile file) {
    if (file == null) {
      return false;
    }
    OCCompilerSettings settings = configuration.getCompilerSettings(CLanguageKind.CPP);
    if (settings == null) {
      return false;
    }

    for (HeadersSearchRoot root : settings.getHeadersSearchRoots().getAllRoots()) {
      VirtualFile rootFile = root.getVirtualFile();
      if (rootFile == null
          || root.getKind() == HeadersSearchPath.Kind.USER
          || !hasToolchainExternalFragment(rootFile.getPath())) {
        continue;
      }
      if (isSameOrAncestor(rootFile, file)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ProjectSourceLocationKind getProjectSourceLocationKind(
      Project project, VirtualFile file, boolean waitForBackend) {
    return getMatchingConfigurations(project, file, /* indicator= */ null).isEmpty()
        ? ProjectSourceLocationKind.UNKNOWN
        : ProjectSourceLocationKind.PROJECT_OR_LIBRARY_SOURCE;
  }

  @Override
  public Object waitGetProjectSourceLocationKind(
      Project project,
      VirtualFile file,
      Continuation<? super ProjectSourceLocationKind> continuation) {
    return getProjectSourceLocationKind(project, file, /* waitForBackend= */ true);
  }

  private static boolean isPotentialHeader(VirtualFile file) {
    if (file == null || file.isDirectory()) {
      return false;
    }
    String name = file.getName();
    return name.indexOf('.') < 0 || OCFileTypeHelpers.isHeaderFile(name);
  }

  private static boolean isSameOrAncestor(VirtualFile root, VirtualFile file) {
    if (VfsUtilCore.isAncestor(root, file, /* strict= */ false)) {
      return true;
    }

    Path rootPath = root.toNioPath();
    Path filePath = file.toNioPath();
    if (rootPath != null && filePath != null && filePath.normalize().startsWith(rootPath.normalize())) {
      return true;
    }

    try {
      return rootPath != null
          && filePath != null
          && filePath.toRealPath().startsWith(rootPath.toRealPath());
    } catch (IOException ignored) {
      return false;
    }
  }

  private static boolean hasToolchainExternalFragment(String path) {
    return path.replace('\\', '/').contains(TOOLCHAIN_EXTERNAL_FRAGMENT);
  }
}
