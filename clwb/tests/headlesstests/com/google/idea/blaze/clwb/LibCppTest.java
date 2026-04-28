/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.blaze.clwb;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.idea.blaze.base.bazel.BazelVersion;
import com.google.idea.blaze.clwb.base.ClwbHeadlessTestCase;
import com.google.idea.blaze.cpp.oclang.BlazeToolchainHeaderResolveConfigurationProvider;
import com.google.idea.testing.headless.BazelVersionRule;
import com.google.idea.testing.headless.OSRule;
import com.google.idea.testing.headless.ProjectViewBuilder;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.system.OS;
import com.jetbrains.cidr.execution.CidrResolveConfigurationProvider;
import com.jetbrains.cidr.lang.CLanguageKind;
import com.jetbrains.cidr.lang.daemon.ProjectSourceLocationKind;
import com.jetbrains.cidr.lang.preprocessor.OCResolveRootAndConfigurationProvider;
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches.Format;
import com.jetbrains.cidr.lang.workspace.OCCompilerSettings;
import com.jetbrains.cidr.lang.workspace.OCLibraryFileResolveConfigurationProvider;
import com.jetbrains.cidr.lang.workspace.OCResolveConfiguration;
import com.jetbrains.cidr.lang.workspace.compiler.ClangCompilerKind;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchPath;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchRoot;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LibCppTest extends ClwbHeadlessTestCase {

  // only the macOS and linux runners have llvm available
  @Rule
  public final OSRule osRule = new OSRule(OS.Linux, OS.macOS);

  @Test
  public void testClwb() throws IOException {
    final var clangPath = findClang();
    assumeTrue("clang not found", clangPath != null);
    assertExists(new File(clangPath));

    final var errors = runSync(defaultSyncParams().build());
    errors.assertNoErrors();

    checkCompiler();
    checkLibCpp();
    checkLibCppStringHeaderWinsOverTargetHeader();
    checkCompilerSwitchOrdering();
    checkRadlerDependencyPathOrdering();
    checkLibCppHeaderResolveConfiguration();
  }

  @Override
  protected ProjectViewBuilder projectViewText(BazelVersion version) {
    final var builder = super.projectViewText(version);
    final var clangPath = findClang();

    if (clangPath == null) {
      return builder;
    }

    // set the compiler to clang, only required for linux
    builder.addBuildFlag(
        "--repo_env=CC=" + clangPath,
        "--repo_env=CXX=" + clangPath,
        "--action_env=CC=" + clangPath,
        "--action_env=CXX=" + clangPath
    );

    return builder.addBuildFlag(
        // use libc++ instead of libstdc++
        "--cxxopt=-stdlib=libc++",
        "--linkopt=-stdlib=libc++"
    );
  }

  private void checkCompiler() {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");
    assertThat(compilerSettings.getCompilerKind()).isEqualTo(ClangCompilerKind.INSTANCE);
  }

  private void checkLibCpp() throws IOException {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");
    final var roots = compilerSettings.getHeadersSearchRoots().getAllRoots();

    final var candidates = roots.stream()
        .map(this::resolveIostream)
        .filter(Objects::nonNull)
        .toList();
    assertThat(candidates).hasSize(1);

    final var text = VfsUtilCore.loadText(candidates.getFirst());
    assertThat(text).contains("// Part of the LLVM Project");
  }

  private void checkLibCppStringHeaderWinsOverTargetHeader() throws IOException {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");
    final var stringHeader = resolveAngleHeader("string.h", compilerSettings);

    assertThat(stringHeader).isNotNull();
    final var text = VfsUtilCore.loadText(stringHeader);
    assertThat(text).contains("// Part of the LLVM Project");
    assertThat(text).doesNotContain("SHADOW_STRING_H");
  }

  private void checkCompilerSwitchOrdering() {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");
    final var switches = compilerSettings.getCompilerSwitches().getList(Format.BASH_SHELL);

    final var libcxxIndex = indexOfSwitchContaining(switches, "-stdlib=libc++");
    final var shadowHeaderIndex = indexOfSwitchContaining(switches, "/main/shadow");

    assertThat(libcxxIndex).isAtLeast(0);
    assertThat(shadowHeaderIndex).isAtLeast(0);
    assertThat(libcxxIndex).isLessThan(shadowHeaderIndex);
  }

  private void checkRadlerDependencyPathOrdering() throws IOException {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");
    final var radlerOrder = radlerDependencyPathOrder(compilerSettings.getHeadersSearchPaths());

    assertThat(
            compilerSettings.getHeadersSearchPaths().stream()
                .map(HeadersSearchPath::getPath)
                .anyMatch(path -> path.replace('\\', '/').endsWith("/main/shadow")))
        .isTrue();

    final var stringHeader = resolveAngleHeader("string.h", radlerOrder);
    assertThat(stringHeader).isNotNull();
    final var text = VfsUtilCore.loadText(stringHeader);
    assertThat(text).contains("// Part of the LLVM Project");
    assertThat(text).doesNotContain("SHADOW_STRING_H");

    final var cStringHeader = resolveIncludeNextHeader("string.h", stringHeader, radlerOrder);
    assertThat(cStringHeader).isNotNull();
    final var cStringText = VfsUtilCore.loadText(cStringHeader);
    assertThat(cStringText).contains("strlen");
    assertThat(cStringText).doesNotContain("// Part of the LLVM Project");
    assertThat(cStringText).doesNotContain("SHADOW_STRING_H");
  }

  private static List<HeadersSearchPath> radlerDependencyPathOrder(List<HeadersSearchPath> paths) {
    final var result = new ArrayList<HeadersSearchPath>();
    final var builtins = new ArrayList<HeadersSearchPath>();

    for (final var path : paths) {
      if (path.isRecursive() || path.isFrameworksSearchPath()) {
        continue;
      }
      if (path.isBuiltInHeaders()) {
        builtins.add(path);
      } else {
        result.add(path);
      }
    }
    result.addAll(builtins);
    return result;
  }

  private void checkLibCppHeaderResolveConfiguration() throws IOException {
    final var sourceConfiguration = findFileResolveConfiguration("main/main.cc");
    final var sourceSettings = findFileCompilerSettings("main/main.cc");
    final var cstringHeader = resolveAngleHeader("cstring", sourceSettings);

    assertThat(cstringHeader).isNotNull();
    final var cstringText = VfsUtilCore.loadText(cstringHeader);
    assertThat(cstringText).contains("// Part of the LLVM Project");

    // This regression covers Bazel-managed toolchain headers. On machines where the fixture
    // resolves libc++ from a system clang installation, keep the existing libc++ assertions above.
    if (!BlazeToolchainHeaderResolveConfigurationProvider.isBazelToolchainHeader(cstringHeader)) {
      return;
    }

    final var psiFile = PsiManager.getInstance(myProject).findFile(cstringHeader);
    assertThat(psiFile).isNotNull();

    final var libraryConfigurations =
        OCLibraryFileResolveConfigurationProvider.getConfigurations(
            psiFile, new EmptyProgressIndicator());
    assertThat(libraryConfigurations).contains(sourceConfiguration);

    final CidrResolveConfigurationProvider radlerProvider =
        new BlazeToolchainHeaderResolveConfigurationProvider();
    final var radlerConfigurations =
        radlerProvider.getAllResolveConfigurationsForFile(psiFile, new EmptyProgressIndicator());
    assertThat(radlerConfigurations).contains(sourceConfiguration);
    assertThat(radlerProvider.findRoots(myProject, cstringHeader))
        .containsAtLeastElementsIn(sourceConfiguration.getSources());
    assertThat(
            ((BlazeToolchainHeaderResolveConfigurationProvider) radlerProvider)
                .getProjectSourceLocationKind(
                    myProject, cstringHeader, /* waitForBackend= */ false))
        .isEqualTo(ProjectSourceLocationKind.PROJECT_OR_LIBRARY_SOURCE);

    final var rootAndConfiguration =
        OCResolveRootAndConfigurationProvider.inferResolveRootAndConfiguration(
            cstringHeader, myProject);
    assertThat(rootAndConfiguration).isNotNull();
    assertThat(rootAndConfiguration.getConfiguration()).isEqualTo(sourceConfiguration);
    assertThat(rootAndConfiguration.getKind()).isEqualTo(CLanguageKind.CPP);

    final OCResolveConfiguration configuration = rootAndConfiguration.getConfiguration();
    final var cstringSettings = configuration.getCompilerSettings(CLanguageKind.CPP, cstringHeader);
    final var stringHeader = resolveAngleHeader("string.h", cstringSettings);

    assertThat(stringHeader).isNotNull();
    final var stringText = VfsUtilCore.loadText(stringHeader);
    assertThat(stringText).contains("// Part of the LLVM Project");
    assertThat(stringText).doesNotContain("SHADOW_STRING_H");
  }

  private static int indexOfSwitchContaining(List<String> switches, String fragment) {
    for (int i = 0; i < switches.size(); i++) {
      if (switches.get(i).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }

  @Nullable
  private static String findClang() {
    final var configuredClang = System.getenv("CLANG");
    if (isExecutable(configuredClang)) {
      return configuredClang;
    }

    final var commonPaths = List.of(
        "/usr/bin/clang",
        "/usr/local/bin/clang",
        "/opt/homebrew/opt/llvm/bin/clang"
    );
    for (final var path : commonPaths) {
      if (isExecutable(path)) {
        return path;
      }
    }

    final var bazelManagedClang = findBazelManagedClangInUserCache();
    if (bazelManagedClang != null) {
      return bazelManagedClang;
    }

    final var pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return null;
    }
    for (final var entry : pathEnv.split(File.pathSeparator)) {
      final var candidate = new File(entry, "clang").getAbsolutePath();
      if (isExecutable(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  @Nullable
  private static String findBazelManagedClangInUserCache() {
    final var userHome = System.getProperty("user.home");
    final var userName = System.getProperty("user.name");
    if (userHome == null || userName == null) {
      return null;
    }

    final var cacheRoot = new File(userHome, ".cache/bazel/_bazel_" + userName);
    final var outputBases = cacheRoot.listFiles(File::isDirectory);
    if (outputBases == null) {
      return null;
    }

    Arrays.sort(outputBases, Comparator.comparingLong(File::lastModified).reversed());
    for (final var outputBase : outputBases) {
      for (final var relativePath : bazelManagedClangPaths()) {
        final var candidate = new File(outputBase, relativePath).getAbsolutePath();
        if (isExecutable(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static List<String> bazelManagedClangPaths() {
    final var clangPath = "+cc_toolchains_extension+clang_toolchains-clang-linux-x86_64/bin/clang";
    return List.of(
        "external/" + clangPath,
        "execroot/_main/external/" + clangPath
    );
  }

  private static boolean isExecutable(@Nullable String path) {
    return path != null && new File(path).canExecute();
  }

  @Nullable
  private static VirtualFile resolveAngleHeader(String fileName, OCCompilerSettings settings) {
    final var roots = settings.getHeadersSearchRoots().getAllRoots();

    for (final var root : roots) {
      if (root.getKind() == HeadersSearchPath.Kind.USER) {
        continue;
      }

      final var rootFile = root.getVirtualFile();
      if (rootFile == null) continue;

      final var headerFile = rootFile.findFileByRelativePath(fileName);
      if (headerFile == null) continue;

      return headerFile;
    }

    return null;
  }

  @Nullable
  private static VirtualFile resolveAngleHeader(String fileName, List<HeadersSearchPath> paths) {
    for (final var path : paths) {
      final var rootFile = LocalFileSystem.getInstance().findFileByPath(path.getPath());
      if (rootFile == null) continue;

      final var headerFile = rootFile.findFileByRelativePath(fileName);
      if (headerFile == null) continue;

      return headerFile;
    }

    return null;
  }

  @Nullable
  private static VirtualFile resolveIncludeNextHeader(
      String fileName, VirtualFile currentHeader, List<HeadersSearchPath> paths) {
    boolean afterCurrentHeader = false;
    for (final var path : paths) {
      final var rootFile = LocalFileSystem.getInstance().findFileByPath(path.getPath());
      if (rootFile == null) continue;

      final var headerFile = rootFile.findFileByRelativePath(fileName);
      if (headerFile == null) continue;

      if (!afterCurrentHeader) {
        if (samePath(headerFile, currentHeader)) {
          afterCurrentHeader = true;
        }
        continue;
      }

      if (!samePath(headerFile, currentHeader)) {
        return headerFile;
      }
    }

    return null;
  }

  private static boolean samePath(VirtualFile first, VirtualFile second) {
    return first.getPath().equals(second.getPath());
  }

  @Nullable
  private VirtualFile resolveIostream(HeadersSearchRoot root) {
    final var file = root.getVirtualFile();
    if (file == null) {
      return null;
    }

    return file.findFileByRelativePath("iostream");
  }
}
