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

package com.google.idea.blaze.cpp;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.sync.workspace.ExecutionRootPathResolver;
import com.google.idea.blaze.cpp.copts.CoptsIncludeProcessor;
import com.google.idea.blaze.cpp.copts.CoptsProcessor;
import com.jetbrains.cidr.lang.CLanguageKind;
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches.Format;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchPath;
import java.io.File;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BlazeCWorkspaceTest extends BlazeTestCase {

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    final var coptsProcessors = registerExtensionPoint(CoptsProcessor.EP_NAME, CoptsProcessor.class);
    coptsProcessors.registerExtension(new CoptsIncludeProcessor.Default(), testDisposable);
    coptsProcessors.registerExtension(new CoptsIncludeProcessor.Quote(), testDisposable);
    coptsProcessors.registerExtension(new CoptsIncludeProcessor.System(), testDisposable);
  }

  @Test
  public void buildSwitchBuilder_addsToolchainIncludesBeforeTargetIncludes() {
    final var compilerSettings =
        BlazeCompilerSettings.builder()
            .setCCompiler(new File("/toolchain/bin/clang"))
            .setCppCompiler(new File("/toolchain/bin/clang"))
            .setCSwitches(ImmutableList.of())
            .setCppSwitches(
                ImmutableList.of(
                    "-I/output_base/external/+cc_toolchains_extension+clang_toolchains-llvm_libcxx/include",
                    "-I/output_base/external/+cc_toolchains_extension+clang_toolchains-llvm_libcxxabi/include"))
            .setVersion("clang version 18.0.0")
            .setName("clang")
            .setEnvironment(ImmutableMap.of())
            .setBuiltInIncludes(ImmutableList.of())
            .setSysroot(null)
            .build();

    final var targetSwitches = compilerSettings.createSwitchBuilder();
    targetSwitches.withIncludePath("/workspace/main/shadow");
    targetSwitches.withQuoteIncludePath("/workspace");

    final var switches =
        BlazeCWorkspace.buildSwitchBuilder(
                compilerSettings,
                targetSwitches,
                mock(ExecutionRootPathResolver.class),
                CLanguageKind.CPP,
                ImmutableList.of("-DLOCAL_CXXOPT"))
            .getList(Format.BASH_SHELL);

    final var libcxxIndex =
        indexOfSwitchContaining(
            switches, "+cc_toolchains_extension+clang_toolchains-llvm_libcxx/include");
    final var targetIncludeIndex = indexOfSwitchContaining(switches, "/workspace/main/shadow");
    final var localCxxoptIndex = indexOfSwitchContaining(switches, "-DLOCAL_CXXOPT");

    assertThat(libcxxIndex).isAtLeast(0);
    assertThat(targetIncludeIndex).isAtLeast(0);
    assertThat(localCxxoptIndex).isAtLeast(0);
    assertThat(libcxxIndex).isLessThan(targetIncludeIndex);
    assertThat(targetIncludeIndex).isLessThan(localCxxoptIndex);
  }

  @Test
  public void prioritizeCppStdlibHeaderSearchPaths_movesLibCppBeforeOtherIncludes() {
    final var libcxxPath =
        "/output_base/external/+cc_toolchains_extension+clang_toolchains-llvm_libcxx/include";
    final var libcxxAbiPath =
        "/output_base/external/+cc_toolchains_extension+clang_toolchains-llvm_libcxxabi/include";
    final var paths =
        ImmutableList.of(
            HeadersSearchPath.includes("/workspace"),
            HeadersSearchPath.includes("/workspace/main/shadow"),
            HeadersSearchPath.builtInIncludes(libcxxPath),
            HeadersSearchPath.builtInIncludes(libcxxAbiPath),
            HeadersSearchPath.builtInIncludes("/sysroot/usr/include"));

    final var normalized =
        BlazeCWorkspace.prioritizeCppStdlibHeaderSearchPaths(paths);

    assertThat(normalized.get(0).getPath()).isEqualTo(libcxxPath);
    assertThat(normalized.get(0).isBuiltInHeaders()).isFalse();
    assertThat(normalized.get(1).getPath()).isEqualTo(libcxxAbiPath);
    assertThat(normalized.get(1).isBuiltInHeaders()).isFalse();
    assertThat(normalized.subList(2, normalized.size()))
        .containsExactly(
            HeadersSearchPath.includes("/workspace"),
            HeadersSearchPath.includes("/workspace/main/shadow"),
            HeadersSearchPath.includes("/sysroot/usr/include"))
        .inOrder();
  }

  @Test
  public void prioritizeCppStdlibHeaderSearchPaths_movesSystemCppStdlibBeforeQuoteIncludes() {
    final var libcxxPath = "/usr/lib/llvm-18/include/c++/v1";
    final var clangResourcePath = "/usr/lib/llvm-18/lib/clang/18/include";
    final var multiarchPath = "/sysroot/usr/include/x86_64-linux-gnu";
    final var cIncludePath = "/sysroot/usr/include";
    final var paths =
        ImmutableList.of(
            HeadersSearchPath.userIncludes("/workspace/main/shadow"),
            HeadersSearchPath.includes("/workspace"),
            HeadersSearchPath.builtInIncludes(libcxxPath),
            HeadersSearchPath.builtInIncludes(clangResourcePath),
            HeadersSearchPath.builtInIncludes(multiarchPath),
            HeadersSearchPath.builtInIncludes(cIncludePath),
            HeadersSearchPath.builtInIncludes("/other/builtin/include"));

    final var normalized = BlazeCWorkspace.prioritizeCppStdlibHeaderSearchPaths(paths);

    assertThat(normalized.get(0)).isEqualTo(HeadersSearchPath.includes(libcxxPath));
    assertThat(normalized.subList(1, normalized.size()))
        .containsExactly(
            HeadersSearchPath.includes("/workspace"),
            HeadersSearchPath.includes(clangResourcePath),
            HeadersSearchPath.includes(multiarchPath),
            HeadersSearchPath.includes(cIncludePath),
            HeadersSearchPath.userIncludes("/workspace/main/shadow"),
            HeadersSearchPath.builtInIncludes("/other/builtin/include"))
        .inOrder();
  }

  private static int indexOfSwitchContaining(List<String> switches, String fragment) {
    for (int i = 0; i < switches.size(); i++) {
      if (switches.get(i).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }
}
