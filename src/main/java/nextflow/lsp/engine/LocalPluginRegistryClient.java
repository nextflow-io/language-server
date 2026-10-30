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
import java.util.Map;
import java.util.Optional;

import nextflow.lsp.spec.PluginSpec;
import nextflow.lsp.util.Logger;

/**
 * Resolve plugin specs from the locally installed plugins, and
 * otherwise from a plugin registry.
 *
 * A release that is installed is read from its `spec.json`, without
 * querying the registry. A plugin without a version is resolved by the
 * registry, or by the latest installed release if the registry doesn't
 * have it or can't be reached. An installed spec that can't be read is
 * logged and treated as empty, so that the plugin's includes are still
 * reported.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class LocalPluginRegistryClient implements PluginRegistryClient {

    private static Logger log = Logger.getInstance();

    private final Path pluginsDir;

    private final PluginRegistryClient registry;

    LocalPluginRegistryClient(Path pluginsDir, PluginRegistryClient registry) {
        this.pluginsDir = pluginsDir;
        this.registry = registry;
    }

    /**
     * Get the directory of installed plugins, as resolved by Nextflow.
     *
     * @param env
     */
    static Path pluginsDir(Map<String,String> env) {
        if( env.containsKey("NXF_PLUGINS_DIR") )
            return Path.of(env.get("NXF_PLUGINS_DIR"));
        if( env.containsKey("NXF_HOME") )
            return Path.of(env.get("NXF_HOME"), "plugins");
        return Path.of(System.getProperty("user.home"), ".nextflow", "plugins");
    }

    @Override
    public Result fetch(String name, String version) throws InterruptedException {
        if( version != null ) {
            var local = readSpec(name, version);
            return local != null ? local : registry.fetch(name, version);
        }
        var result = registry.fetch(name, null);
        if( result instanceof Found )
            return result;
        var local = latestVersion(name)
            .map(v -> readSpec(name, v))
            .orElse(null);
        return local != null ? local : result;
    }

    private Result readSpec(String name, String version) {
        var specFile = pluginsDir.resolve(name + "-" + version).resolve("classes/META-INF/spec.json");
        if( !Files.isRegularFile(specFile) )
            return null;
        log.debug("read plugin " + specFile);
        try {
            return new Found(PluginSpec.fromJson(Files.readString(specFile)));
        }
        catch( IOException | RuntimeException e ) {
            // a broken spec won't fix itself on retry, so report the plugin
            // as having no definitions rather than leaving it pending
            log.error("Failed to read plugin spec " + specFile + ": " + e);
            return new Found(PluginSpec.fromJson(null));
        }
    }

    private Optional<String> latestVersion(String name) {
        var prefix = name + "-";
        try( var dirs = Files.list(pluginsDir) ) {
            return dirs
                .filter(Files::isDirectory)
                .map(dir -> dir.getFileName().toString())
                .filter(dirName -> dirName.startsWith(prefix) && dirName.length() > prefix.length() && Character.isDigit(dirName.charAt(prefix.length())))
                .map(dirName -> dirName.substring(prefix.length()))
                .max(LocalPluginRegistryClient::compareVersions);
        }
        catch( IOException e ) {
            return Optional.empty();
        }
    }

    /**
     * Compare two versions by their numeric parts, so that 1.10.0
     * is later than 1.9.0.
     *
     * @param a
     * @param b
     */
    static int compareVersions(String a, String b) {
        var as = a.split("[.-]");
        var bs = b.split("[.-]");
        for( int i = 0; i < Math.min(as.length, bs.length); i++ ) {
            var cmp = comparePart(as[i], bs[i]);
            if( cmp != 0 )
                return cmp;
        }
        // a pre-release is earlier than its release (1.0.0-beta < 1.0.0)
        return Integer.compare(bs.length, as.length);
    }

    private static int comparePart(String a, String b) {
        if( a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit) )
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        return a.compareTo(b);
    }

}
