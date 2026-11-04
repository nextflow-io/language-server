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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nextflow.lsp.spec.PluginSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies resolving plugin specs from locally installed plugins
 * before the plugin registry.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class LocalPluginRegistryClientTest {

    @TempDir
    Path pluginsDir;

    private final List<String> fetches = new ArrayList<>();

    private PluginRegistryClient.Result registryResult = new PluginRegistryClient.NotFound();

    private final PluginRegistryClient registry = (name, version) -> {
        fetches.add(version != null ? name + "@" + version : name);
        return registryResult;
    };

    private void install(String name, String version, String scope) throws IOException {
        var metaInf = pluginsDir.resolve(name + "-" + version).resolve("classes/META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("spec.json"), """
            {"definitions":[{"type":"ConfigScope","spec":{"name":"%s","description":"","children":[]}}]}
            """.formatted(scope));
    }

    private static PluginSpec spec(PluginRegistryClient.Result result) {
        assertThat(result).isInstanceOf(PluginRegistryClient.Found.class);
        return ((PluginRegistryClient.Found) result).spec();
    }

    @Test
    void readInstalledReleaseWithoutQueryingRegistry() throws Exception {
        install("nf-hello", "1.0.0", "hello");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", "1.0.0")).configScopes()).containsKey("hello");
        assertThat(fetches).isEmpty();
    }

    @Test
    void readEmptySpecAsEmpty() throws Exception {
        var metaInf = pluginsDir.resolve("nf-hello-1.0.0/classes/META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("spec.json"), "");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", "1.0.0")).functions()).isEmpty();
        assertThat(fetches).isEmpty();
    }

    @Test
    void readInvalidSpecAsEmpty() throws Exception {
        var metaInf = pluginsDir.resolve("nf-hello-1.0.0/classes/META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("spec.json"), "{\"definitions\": [");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", "1.0.0")).functions()).isEmpty();
        assertThat(fetches).isEmpty();
    }

    @Test
    void fetchReleaseThatIsNotInstalledFromRegistry() throws Exception {
        install("nf-hello", "1.0.0", "hello");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(client.fetch("nf-hello", "2.0.0")).isInstanceOf(PluginRegistryClient.NotFound.class);
        assertThat(fetches).containsExactly("nf-hello@2.0.0");
    }

    @Test
    void preferRegistryForPluginWithoutVersion() throws Exception {
        install("nf-hello", "1.0.0", "local");
        registryResult = new PluginRegistryClient.Found(PluginSpec.fromJson(null));
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", null)).configScopes()).isEmpty();
    }

    @Test
    void useLatestInstalledReleaseForUnpublishedPluginWithoutVersion() throws Exception {
        install("nf-hello", "1.9.0", "old");
        install("nf-hello", "1.10.0", "latest");
        install("nf-hello", "1.10.0-beta", "beta");
        install("nf-hello-world", "2.0.0", "other");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", null)).configScopes()).containsOnlyKeys("latest");
    }

    @Test
    void useLatestInstalledReleaseWhenRegistryFails() throws Exception {
        install("nf-hello", "1.0.0", "hello");
        registryResult = new PluginRegistryClient.Failed("offline");
        var client = new LocalPluginRegistryClient(pluginsDir, registry);

        assertThat(spec(client.fetch("nf-hello", null)).configScopes()).containsKey("hello");
    }

    @Test
    void reportRegistryResultWhenPluginIsNotInstalled() throws Exception {
        registryResult = new PluginRegistryClient.Failed("offline");
        var client = new LocalPluginRegistryClient(pluginsDir.resolve("missing"), registry);

        assertThat(client.fetch("nf-hello", null)).isEqualTo(registryResult);
    }

    @Test
    void resolvePluginsDirLikeNextflow() {
        assertThat(LocalPluginRegistryClient.pluginsDir(Map.of("NXF_PLUGINS_DIR", "/a", "NXF_HOME", "/b"))).isEqualTo(Path.of("/a"));
        assertThat(LocalPluginRegistryClient.pluginsDir(Map.of("NXF_HOME", "/b"))).isEqualTo(Path.of("/b/plugins"));
        assertThat(LocalPluginRegistryClient.pluginsDir(Map.of())).isEqualTo(Path.of(System.getProperty("user.home"), ".nextflow/plugins"));
    }

}
