// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.genquery;

import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;

/** {@link Fragment} for {@link GenQuery}. */
@RequiresOptions(options = {GenQueryConfiguration.GenQueryOptions.class})
public class GenQueryConfiguration extends Fragment {

  /** GenQuery-specific options. */
  public static class GenQueryOptions extends FragmentOptions {
    @Option(
        name = "experimental_skip_ttvs_for_genquery",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help =
            "If true, genquery loads its scope's transitive closure directly instead of by using "
                + "'TransitiveTargetValue' Skyframe work.")
    public boolean skipTtvs;

    @Override
    public FragmentOptions getExec() {
      GenQueryOptions exec = (GenQueryOptions) getDefault();
      exec.skipTtvs = skipTtvs;
      return exec;
    }
  }

  private final boolean skipTtvs;

  public GenQueryConfiguration(BuildOptions buildOptions) {
    this.skipTtvs = buildOptions.get(GenQueryOptions.class).skipTtvs;
  }

  /** Returns whether genquery should load its scope's transitive closure directly. */
  boolean skipTtvs() {
    return skipTtvs;
  }
}
