/*
 * Copyright 2024-2025, Seqera Labs
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
package nextflow.lsp.engine;

import java.util.ArrayList;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.spec.PluginRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for plugin spec lookups and the config files that declare
 * the plugins of a script.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class PluginSpecsTest {

    static final String SCHEMA_CONFIG = "plugins { id 'nf-schema@2.7.3' }\n";

    static final String SCRIPT = """
        include { paramsHelp } from 'plugin/nf-schema'

        workflow {
        }
        """;

    static final String DOES_NOT_EXIST = "Plugin 'nf-schema' does not exist or is not specified in the configuration file";

    static TestServer start(FixtureRegistry registry, String... folders) {
        return TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry);
            o.settle = false;
            if( folders.length > 0 )
                o.folders(folders);
        });
    }

    static PluginSpecs.Lookup lookup(TestServer f, PluginRef ref) {
        return f.call(Need.FILE, null, null, (context) -> f.workspace().plugins().lookup(ref));
    }

    @Test
    void fetchSpecOnFirstLookup() {
        var registry = new FixtureRegistry();
        var f = start(registry);
        f.settle();
        var ref = new PluginRef("nf-prov", "1.6.0");
        var lookups = new ArrayList<PluginSpecs.Lookup>();
        lookups.add(lookup(f, ref));
        f.settle();
        lookups.add(lookup(f, ref));
        assertThat(lookups.get(1).pending()).isFalse();
        assertThat(lookups.get(1).spec()).hasValueSatisfying(spec -> assertThat(spec.configScopes()).containsKey("prov"));
        assertThat(registry.fetches).containsExactly("nf-prov@1.6.0");
    }

    @Test
    void cachePluginsThatAreNotFoundInRegistry() {
        var registry = new FixtureRegistry();
        var f = start(registry);
        f.settle();
        var ref = new PluginRef("nf-missing", "1.0.0");
        for( int i = 0; i < 3; i++ ) {
            lookup(f, ref);
            f.settle();
        }
        var result = lookup(f, ref);
        assertThat(result.spec()).isEmpty();
        assertThat(result.pending()).isFalse();
        assertThat(registry.fetches).containsExactly("nf-missing@1.0.0");
    }

    @Test
    void onlyMainConfigFilesDeclarePluginsForScripts() {
        var registry = new FixtureRegistry();
        var f = start(registry);
        f.write("other.config", SCHEMA_CONFIG);
        f.write("main.nf", SCRIPT);
        f.settle();
        assertThat(f.messages("main.nf")).containsExactly(DOES_NOT_EXIST);
        assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
    }

    @Test
    void ignoreConfigFilesOutsideTheProject() {
        var registry = new FixtureRegistry();
        var f = start(registry, "project");
        f.write("nextflow.config", SCHEMA_CONFIG);
        f.write("project/main.nf", SCRIPT);
        f.settle();
        assertThat(f.messages("project/main.nf")).containsExactly(DOES_NOT_EXIST);
    }

    @Test
    void usePluginsOfTheNearestParentConfig() {
        var registry = new FixtureRegistry();
        var f = start(registry);
        f.write("nextflow.config", SCHEMA_CONFIG);
        f.write("main.nf", SCRIPT);
        f.write("subdir/module.nf", SCRIPT);
        f.write("other/nextflow.config", "plugins { id 'nf-prov@1.6.0' }\n");
        f.write("other/module.nf", SCRIPT);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.messages("subdir/module.nf")).isEmpty();
        assertThat(f.messages("other/module.nf")).containsExactly(DOES_NOT_EXIST);
    }

}
