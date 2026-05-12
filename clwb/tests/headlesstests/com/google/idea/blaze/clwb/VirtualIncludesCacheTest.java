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
import static com.google.idea.blaze.clwb.base.Assertions.assertCachedHeader;
import static com.google.idea.blaze.clwb.base.Assertions.assertContainsHeader;
import static com.google.idea.blaze.clwb.base.Assertions.assertWorkspaceHeader;
import static com.google.idea.blaze.clwb.base.Utils.setIncludesCacheEnabled;

import com.google.idea.blaze.clwb.base.ClwbHeadlessTestCase;
import com.google.idea.blaze.cpp.sync.HeaderCacheService;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.system.OS;
import com.jetbrains.cidr.lang.CLanguageKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VirtualIncludesCacheTest extends ClwbHeadlessTestCase {

  @Test
  public void testClwb() throws Exception {
    setIncludesCacheEnabled(true);

    final var errors = runSync(defaultSyncParams().build());
    errors.assertNoErrors();

    checkIncludes();
    checkImplDeps();
    checkCoptIncludes();
    checkGeneratedSourceCache();
    checkExternalSourceCache();
  }

  private void checkIncludes() {
    final var compilerSettings = findFileCompilerSettings("main/main.cc");

    assertContainsHeader("strip_absolut/strip_absolut.h", compilerSettings);
    assertCachedHeader("strip_absolut/strip_absolut.h", compilerSettings, myProject, /* symlink = */ true);

    assertContainsHeader("strip_absolut/generated.h", compilerSettings);
    assertCachedHeader("strip_absolut/generated.h", compilerSettings, myProject, /* symlink = */ true);

    assertContainsHeader("strip_relative.h", compilerSettings);
    assertCachedHeader("strip_relative.h", compilerSettings, myProject, /* symlink = */ true);

    assertContainsHeader("raw_default.h", compilerSettings);
    assertWorkspaceHeader("raw_default.h", compilerSettings, myProject);

    assertContainsHeader("raw_system.h", compilerSettings);
    assertWorkspaceHeader("raw_system.h", compilerSettings, myProject);

    assertContainsHeader("raw_quote.h", compilerSettings);
    assertWorkspaceHeader("raw_quote.h", compilerSettings, myProject);

    assertContainsHeader("external/generated.h", compilerSettings);
    assertCachedHeader("external/generated.h", compilerSettings, myProject, /* symlink = */ true);

    assertContainsHeader("lib/generated.h", compilerSettings);
    assertCachedHeader("lib/generated.h", compilerSettings, myProject, /* symlink = */ false);
  }

  private void checkCoptIncludes() {
    final var compilerSettings = findFileCompilerSettings("main/raw.cc");

    assertContainsHeader("raw_default.h", compilerSettings);
    assertWorkspaceHeader("raw_default.h", compilerSettings, myProject);

    assertContainsHeader("raw_system.h", compilerSettings);
    assertWorkspaceHeader("raw_system.h", compilerSettings, myProject);

    assertContainsHeader("raw_quote.h", compilerSettings);
    assertWorkspaceHeader("raw_quote.h", compilerSettings, myProject);
  }

  private void checkImplDeps() {
    final var compilerSettings = findFileCompilerSettings("lib/impl_deps/impl.cc");

    final var headersSearchRoots = compilerSettings.getHeadersSearchRoots().getAllRoots();
    assertThat(headersSearchRoots).isNotEmpty();

    assertContainsHeader("strip_relative.h", compilerSettings);
    assertCachedHeader("strip_relative.h", compilerSettings, myProject, /* symlink = */ true);
  }

  private void checkGeneratedSourceCache() throws IOException {
    final var service = HeaderCacheService.of(myProject);
    final var generatedSource = findCachedFile(service, Path.of("main", "generated_source.cc"));

    final var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generatedSource);
    assertThat(file).isNotNull();
    assertThat(file.toNioPath().startsWith(service.getCacheDirectory())).isTrue();

    final var configurations = getWorkspace().getConfigurationsForFile(file);
    assertThat(configurations).hasSize(1);
    assertThat(configurations.get(0).getCompilerSettings(CLanguageKind.CPP, file)).isNotNull();
  }

  private void checkExternalSourceCache() throws IOException {
    final var service = HeaderCacheService.of(myProject);
    final var externalSource = findCachedFile(service, Path.of("external_source.cc"));

    assertThat(externalSource.startsWith(service.getCacheDirectory())).isTrue();
    if (!OS.CURRENT.equals(OS.Windows)) {
      assertThat(Files.isSymbolicLink(externalSource)).isTrue();
    }

    final var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalSource);
    assertThat(file).isNotNull();
    assertThat(file.toNioPath().startsWith(service.getCacheDirectory())).isTrue();

    final var configurations = getWorkspace().getConfigurationsForFile(file);
    assertThat(configurations).hasSize(1);
    assertThat(configurations.get(0).getCompilerSettings(CLanguageKind.CPP, file)).isNotNull();
  }

  private static Path findCachedFile(HeaderCacheService service, Path suffix) throws IOException {
    try (var files = Files.walk(service.getCacheDirectory())) {
      return files
          .filter(path -> path.endsWith(suffix))
          .findFirst()
          .orElseThrow(() -> new AssertionError(suffix + " was not cached"));
    }
  }
}
