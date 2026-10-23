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

import java.time.Duration;
import java.util.Map;

import nextflow.lsp.TestWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for plugin specs: asynchronous fetch, failure backoff,
 * nearest `nextflow.config` semantics and invalidation.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspacePluginTest {

    static final String SCHEMA_CONFIG = "plugins { id 'nf-schema@2.7.3' }\n";

    static final String SCRIPT = """
        include { paramsHelp } from 'plugin/nf-schema'
        include { notAFunction } from 'plugin/nf-schema'

        workflow {
        }
        """;

    static final String NOT_DEFINED = "Included name 'notAFunction' is not defined in plugin 'nf-schema'";

    static String doesNotExist(String plugin) {
        return "Plugin '" + plugin + "' does not exist or is not specified in the configuration file";
    }

    static WorkspaceFixture start(FixtureRegistry registry) {
        return WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry);
            o.settle = false;
        });
    }

    @Test
    void resolvePluginIncludesAgainstTheGoverningConfig() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.write("nextflow.config", SCHEMA_CONFIG);
            f.write("main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
            assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
        }
    }

    @Test
    void resolvePluginIncludesWhenConfigAndScriptAreOpenedTogether() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.open("nextflow.config", SCHEMA_CONFIG);
            f.open("main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

    @Test
    void suppressMissingPluginErrorWhileTheSpecIsPending() {
        var registry = new FixtureRegistry();
        var io = new QueuedExecutor();
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry).withIoExecutor(io);
            o.settle = false;
        }) ) {
            f.open("nextflow.config", SCHEMA_CONFIG);
            f.open("main.nf", SCRIPT);
            // build the open files while the fetch is held
            var built = f.call(Need.FILE, "main.nf", false, (context) -> WorkspaceFixture.requestEntry(context).isBuilt());
            assertThat(built).isTrue();
            assertThat(registry.fetches).isEmpty();
            f.advance(Duration.ofSeconds(1));
            assertThat(f.messages("main.nf")).isEmpty();

            f.settle(io);
            assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

    @Test
    void retryFailedFetchAtMostOncePerInterval() {
        var registry = new FixtureRegistry();
        registry.overrides.put("nf-schema@2.7.3", new PluginRegistryClient.Failed("connection refused"));
        try( var f = start(registry) ) {
            f.open("nextflow.config", SCHEMA_CONFIG);
            f.open("main.nf", SCRIPT);
            f.settle();
            assertThat(registry.fetches).hasSize(1);
            assertThat(f.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

            // rebuilding within the retry interval doesn't fetch again
            f.change("nextflow.config", SCHEMA_CONFIG + "// edit 1\n");
            f.settle();
            f.advance(Duration.ofMinutes(4));
            f.change("nextflow.config", SCHEMA_CONFIG + "// edit 2\n");
            f.settle();
            assertThat(registry.fetches).hasSize(1);

            // after the interval, the next rebuild retries
            registry.overrides.clear();
            f.advance(Duration.ofMinutes(1));
            f.change("nextflow.config", SCHEMA_CONFIG + "// edit 3\n");
            f.settle();
            assertThat(registry.fetches).hasSize(2);
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

    @Test
    void cacheMissingPlugins() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.open("nextflow.config", "plugins { id 'nf-missing@1.0.0' }\n");
            f.open("main.nf", """
                include { foo } from 'plugin/nf-missing'
                workflow {
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(doesNotExist("nf-missing"));
            f.change("nextflow.config", "plugins { id 'nf-missing@1.0.0' }\n// edit\n");
            f.settle();
            assertThat(registry.fetches).containsExactly("nf-missing@1.0.0");
        }
    }

    @Test
    void updateScriptWhenConfigPluginsChange() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.write("nextflow.config", SCHEMA_CONFIG);
            f.write("main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);

            f.open("nextflow.config", "plugins { id 'nf-prov@1.6.0' }\n");
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

            f.change("nextflow.config", SCHEMA_CONFIG);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

    @Test
    void useNearestConfigThatDeclaresPlugins() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.write("nextflow.config", SCHEMA_CONFIG);
            // declares no plugins, so the root config applies
            f.write("a/nextflow.config", "params.foo = 1\n");
            f.write("a/main.nf", SCRIPT);
            // declares other plugins, so nf-schema is not specified
            f.write("b/nextflow.config", "plugins { id 'nf-prov@1.6.0' }\n");
            f.write("b/main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("a/main.nf")).containsExactly(NOT_DEFINED);
            assertThat(f.messages("b/main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));
        }
    }

    @Test
    void createGoverningConfigLater() {
        var registry = new FixtureRegistry();
        try( var f = start(registry) ) {
            f.write("main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

            f.writeAndNotify("nextflow.config", SCHEMA_CONFIG);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

    @Test
    void refetchWhenRegistryChanges() {
        var first = new FixtureRegistry();
        first.overrides.put("nf-schema@2.7.3", new PluginRegistryClient.NotFound());
        var second = new FixtureRegistry();
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> url.contains("second") ? second : first);
            o.settle = false;
        }) ) {
            f.write("nextflow.config", SCHEMA_CONFIG);
            f.write("main.nf", SCRIPT);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

            f.configure(Map.of("nextflow.pluginRegistryUrl", "https://second.example.com/api/"));
            f.settle();
            assertThat(second.fetches).containsExactly("nf-schema@2.7.3");
            assertThat(f.messages("main.nf")).containsExactly(NOT_DEFINED);
        }
    }

}
