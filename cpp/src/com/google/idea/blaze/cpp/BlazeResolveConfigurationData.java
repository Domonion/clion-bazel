/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.toprettystring.ToPrettyString;
import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.ideinfo.CIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.model.primitives.ExecutionRootPath;
import java.util.LinkedHashSet;
import java.util.function.Function;

/** Data for clustering {@link BlazeResolveConfiguration} by "equivalence". */
@AutoValue
public abstract class BlazeResolveConfigurationData {

  public abstract String configurationId();

  public abstract BlazeCompilerSettings compilerSettings();

  // Everything from CIdeInfo except for sources, headers, etc.
  // That is parts that influence the flags, but not the actual input files.
  public abstract ImmutableList<String> localCopts();
  public abstract ImmutableList<String> localConlyopts();
  public abstract ImmutableList<String> localCxxopts();

  // From the cpp compilation context provider.
  // These should all be for the entire transitive closure.
  public abstract ImmutableList<ExecutionRootPath> transitiveIncludeDirectories();
  public abstract ImmutableList<ExecutionRootPath> transitiveQuoteIncludeDirectories();

  public abstract ImmutableList<String> transitiveDefines();
  public abstract ImmutableList<ExecutionRootPath> transitiveSystemIncludeDirectories();

  @ToPrettyString
  public abstract String toPrettyString();

  static BlazeResolveConfigurationData create(
      TargetKey targetKey,
      CIdeInfo cIdeInfo,
      BlazeCompilerSettings compilerSettings
  ) {
    return create(targetKey, cIdeInfo, compilerSettings, ImmutableList.of());
  }

  static BlazeResolveConfigurationData create(
      TargetKey targetKey,
      CIdeInfo cIdeInfo,
      BlazeCompilerSettings compilerSettings,
      ImmutableList<CIdeInfo.CompilationContext> additionalCompilationContexts
  ) {
    final var ruleCtx = cIdeInfo.ruleContext();
    final var compilationCtx = cIdeInfo.compilationContext();

    return builder()
        .setConfigurationId(targetKey.configuration())
        .setCompilerSettings(compilerSettings)
        .setLocalCopts(ruleCtx.copts())
        .setLocalConlyopts(ruleCtx.conlyopts())
        .setLocalCxxopts(ruleCtx.cxxopts())
        .setTransitiveIncludeDirectories(
            mergeCompilationContextValues(
                compilationCtx.includes(),
                additionalCompilationContexts,
                CIdeInfo.CompilationContext::includes))
        .setTransitiveQuoteIncludeDirectories(
            mergeCompilationContextValues(
                compilationCtx.quoteIncludes(),
                additionalCompilationContexts,
                CIdeInfo.CompilationContext::quoteIncludes))
        .setTransitiveDefines(
            mergeCompilationContextValues(
                compilationCtx.defines(),
                additionalCompilationContexts,
                CIdeInfo.CompilationContext::defines))
        .setTransitiveSystemIncludeDirectories(
            mergeCompilationContextValues(
                compilationCtx.systemIncludes(),
                additionalCompilationContexts,
                CIdeInfo.CompilationContext::systemIncludes))
        .build();
  }

  private static <T> ImmutableList<T> mergeCompilationContextValues(
      ImmutableList<T> baseValues,
      ImmutableList<CIdeInfo.CompilationContext> additionalCompilationContexts,
      Function<CIdeInfo.CompilationContext, ImmutableList<T>> valueGetter
  ) {
    final var values = new LinkedHashSet<T>();
    values.addAll(baseValues);
    for (final var context : additionalCompilationContexts) {
      values.addAll(valueGetter.apply(context));
    }
    return ImmutableList.copyOf(values);
  }

  public static Builder builder() {
    return new AutoValue_BlazeResolveConfigurationData.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setConfigurationId(String value);

    public abstract Builder setCompilerSettings(BlazeCompilerSettings value);

    public abstract Builder setLocalCopts(ImmutableList<String> value);

    public abstract Builder setLocalConlyopts(ImmutableList<String> value);

    public abstract Builder setLocalCxxopts(ImmutableList<String> value);

    public abstract Builder setTransitiveIncludeDirectories(ImmutableList<ExecutionRootPath> value);

    public abstract Builder setTransitiveQuoteIncludeDirectories(ImmutableList<ExecutionRootPath> value);

    public abstract Builder setTransitiveDefines(ImmutableList<String> value);

    public abstract Builder setTransitiveSystemIncludeDirectories(ImmutableList<ExecutionRootPath> value);

    public abstract BlazeResolveConfigurationData build();
  }
}
