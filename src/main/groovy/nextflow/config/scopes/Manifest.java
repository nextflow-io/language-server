/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.config.scopes;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;

public class Manifest implements ConfigScope {

    public Manifest() {}

    @Override
    public String name() {
        return "manifest";
    }

    @Override
    public String description() {
        return """
            The `manifest` scope allows you to define some metadata that is useful when publishing or running your pipeline.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-manifest)
            """;
    }

    @ConfigOption("""
        Project author name (use a comma to separate multiple names).
    """)
    public String author;

    @ConfigOption("""
        Git repository default branch (default: `master`).
    """)
    public String defaultBranch;

    @ConfigOption("""
        Free text describing the workflow project.
    """)
    public String description;

    @ConfigOption("""
        Project related publication DOI identifier.
    """)
    public String doi;

    @ConfigOption("""
        Project home page URL.
    """)
    public String homePage;

    @ConfigOption("""
        Project main script (default: `main.nf`).
    """)
    public String mainScript;

    @ConfigOption("""
        Project short name.
    """)
    public String name;

    @ConfigOption("""
        Minimum required Nextflow version.
    """)
    public String nextflowVersion;

    @ConfigOption("""
        Pull submodules recursively from the Git repository.
    """)
    public boolean recurseSubmodules;

    @ConfigOption("""
        Project version number.
    """)
    public String version;

}
