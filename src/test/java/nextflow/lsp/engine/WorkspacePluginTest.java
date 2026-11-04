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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import nextflow.lsp.TestServer;
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

    static TestServer start(FixtureRegistry registry, String... folders) {
        return TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry);
            o.settle = false;
            if( folders.length > 0 )
                o.folders(folders);
        });
    }

    @Test
    void resolvePluginIncludesAgainstTheGoverningConfig() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.write("nextflow.config", SCHEMA_CONFIG);
        server.write("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
        assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
    }

    @Test
    void resolvePluginIncludesWhenConfigAndScriptAreOpenedTogether() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.open("nextflow.config", SCHEMA_CONFIG);
        server.open("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void suppressMissingPluginErrorWhileTheSpecIsPending() {
        var registry = new FixtureRegistry();
        var io = new QueuedExecutor();
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry).withIoExecutor(io);
            o.settle = false;
        });
        server.open("nextflow.config", SCHEMA_CONFIG);
        server.open("main.nf", SCRIPT);
        // build the open files while the fetch is held
        var built = server.call(Need.FILE, "main.nf", List.<URI>of(), (context) -> List.copyOf(context.project().scriptView().getUris()));
        assertThat(built).contains(URI.create(server.uri("main.nf")));
        assertThat(registry.fetches).isEmpty();
        server.advance(Duration.ofSeconds(1));
        assertThat(server.messages("main.nf")).isEmpty();

        server.settle(io);
        assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void suppressUnknownScopeWarningsWhileTheSpecIsPending() {
        var registry = new FixtureRegistry();
        var io = new QueuedExecutor();
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> registry).withIoExecutor(io);
            o.settle = false;
        });
        server.open("nextflow.config", """
            plugins { id 'nf-schema@2.7.3' }
            validation { parametersSchema = 'schema.json' }
            validation.bogus = true
            manifest.bogus = true
            """);
        server.call(Need.FILE, "nextflow.config", null, (context) -> null);
        server.advance(Duration.ofSeconds(1));
        assertThat(registry.fetches).isEmpty();
        assertThat(server.messages("nextflow.config")).containsExactly("Unrecognized config option 'manifest.bogus'");

        // check again once the spec is known
        server.settle(io);
        assertThat(server.messages("nextflow.config")).containsExactly(
            "Unrecognized config option 'validation.bogus'",
            "Unrecognized config option 'manifest.bogus'");
    }

    @Test
    void retryFailedFetchAtMostOncePerInterval() {
        var registry = new FixtureRegistry();
        registry.overrides.put("nf-schema@2.7.3", new PluginRegistryClient.Failed("connection refused"));
        var server = start(registry);
        server.open("nextflow.config", SCHEMA_CONFIG);
        server.open("main.nf", SCRIPT);
        server.settle();
        assertThat(registry.fetches).hasSize(1);
        // a failed fetch doesn't mean that the plugin doesn't exist
        assertThat(server.messages("main.nf")).isEmpty();

        // rebuilding within the retry interval doesn't fetch again
        server.change("nextflow.config", SCHEMA_CONFIG + "// edit 1\n");
        server.settle();
        server.advance(PluginSpecs.RETRY_INTERVAL.minusMinutes(1));
        server.change("nextflow.config", SCHEMA_CONFIG + "// edit 2\n");
        server.settle();
        assertThat(registry.fetches).hasSize(1);

        // after the interval, the next rebuild retries
        registry.overrides.clear();
        server.advance(Duration.ofMinutes(1));
        server.change("nextflow.config", SCHEMA_CONFIG + "// edit 3\n");
        server.settle();
        assertThat(registry.fetches).hasSize(2);
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void retryFailedFetchAfterTheIntervalWithoutAConfigEdit() {
        var registry = new FixtureRegistry();
        registry.overrides.put("nf-schema@2.7.3", new PluginRegistryClient.Failed("connection refused"));
        var server = start(registry);
        server.write("nextflow.config", SCHEMA_CONFIG);
        server.write("main.nf", SCRIPT);
        server.settle();
        server.advance(PluginSpecs.RETRY_INTERVAL.minusMinutes(1));
        server.call(Need.FILE, "main.nf", null, (context) -> null);
        server.settle();
        assertThat(registry.fetches).hasSize(1);

        // the next background build retries
        server.advance(Duration.ofMinutes(1));
        server.open("other.nf", "workflow {}\n");
        server.settle();
        assertThat(registry.fetches).hasSize(2);
        assertThat(server.messages("main.nf")).isEmpty();

        // the next request retries
        registry.overrides.clear();
        server.advance(PluginSpecs.RETRY_INTERVAL);
        server.call(Need.FILE, "main.nf", null, (context) -> null);
        server.settle();
        assertThat(registry.fetches).hasSize(3);
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void doNotRefetchMissingPluginWhenConfigIsEdited() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.open("nextflow.config", "plugins { id 'nf-missing@1.0.0' }\n");
        server.open("main.nf", """
            include { foo } from 'plugin/nf-missing'
            workflow {
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(doesNotExist("nf-missing"));
        server.change("nextflow.config", "plugins { id 'nf-missing@1.0.0' }\n// edit\n");
        server.settle();
        assertThat(registry.fetches).containsExactly("nf-missing@1.0.0");
    }

    @Test
    void updateScriptWhenConfigPluginsChange() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.write("nextflow.config", SCHEMA_CONFIG);
        server.write("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);

        server.open("nextflow.config", "plugins { id 'nf-prov@1.6.0' }\n");
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

        server.change("nextflow.config", SCHEMA_CONFIG);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void useNearestConfigThatDeclaresPlugins() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.write("nextflow.config", SCHEMA_CONFIG);
        // declares no plugins, so the root config applies
        server.write("a/nextflow.config", "params.foo = 1\n");
        server.write("a/main.nf", SCRIPT);
        // declares other plugins, so nf-schema is not specified
        server.write("b/nextflow.config", "plugins { id 'nf-prov@1.6.0' }\n");
        server.write("b/main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("a/main.nf")).containsExactly(NOT_DEFINED);
        assertThat(server.messages("b/main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));
    }

    @Test
    void onlyMainConfigFilesDeclarePluginsForScripts() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.write("other.config", SCHEMA_CONFIG);
        server.write("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));
        assertThat(registry.fetches).containsExactly("nf-schema@2.7.3");
    }

    @Test
    void ignoreConfigFilesOutsideTheProject() {
        var registry = new FixtureRegistry();
        var server = start(registry, "project");
        server.write("nextflow.config", SCHEMA_CONFIG);
        server.write("project/main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("project/main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));
    }

    @Test
    void createGoverningConfigLater() {
        var registry = new FixtureRegistry();
        var server = start(registry);
        server.write("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

        server.writeAndNotify("nextflow.config", SCHEMA_CONFIG);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

    @Test
    void refetchWhenRegistryChanges() {
        var first = new FixtureRegistry();
        first.overrides.put("nf-schema@2.7.3", new PluginRegistryClient.NotFound());
        var second = new FixtureRegistry();
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry((url) -> url.contains("second") ? second : first);
            o.settle = false;
        });
        server.write("nextflow.config", SCHEMA_CONFIG);
        server.write("main.nf", SCRIPT);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(doesNotExist("nf-schema"), doesNotExist("nf-schema"));

        server.configure(Map.of("nextflow.pluginRegistryUrl", "https://second.example.com/api/"));
        server.settle();
        assertThat(second.fetches).containsExactly("nf-schema@2.7.3");
        assertThat(server.messages("main.nf")).containsExactly(NOT_DEFINED);
    }

}
