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
package nextflow.lsp.spec;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nextflow.lsp.engine.HttpPluginRegistryClient;
import nextflow.lsp.engine.PluginRegistryClient;
import nextflow.lsp.util.Logger;

/**
 * Cache plugin specs that are fetched from the plugin registry.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class PluginSpecCache {

    private static Logger log = Logger.getInstance();

    private final PluginRegistryClient client;

    private Map<PluginRef, PluginSpec> cache = new HashMap<>();

    private Map<Path, List<PluginRef>> pluginsMap = new HashMap<>();

    public PluginSpecCache(String registryUrl) {
        this.client = new HttpPluginRegistryClient(registryUrl);
    }

    /**
     * Get the plugin spec for a given plugin release.
     *
     * If the version is not specified, the latest version is used instead.
     *
     * Results are cached to minimize registry API calls, including
     * plugins that are not found in the registry.
     *
     * @param name
     * @param version
     */
    public PluginSpec get(String name, String version) {
        var ref = new PluginRef(name, version);
        if( !cache.containsKey(ref) )
            updateCache(ref);
        return cache.get(ref);
    }

    private void updateCache(PluginRef ref) {
        try {
            var result = client.fetch(ref.name(), ref.version());
            if( result instanceof PluginRegistryClient.Found found )
                cache.put(ref, found.spec());
            else if( result instanceof PluginRegistryClient.NotFound )
                cache.put(ref, null);
            else if( result instanceof PluginRegistryClient.Failed failed )
                log.error("Failed to fetch plugin " + ref.name() + " from registry: " + failed.reason());
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt();
            log.error("Failed to fetch plugin " + ref.name() + " from registry: " + e.toString());
        }
    }

    /**
     * Set the plugin versions currently specified for a given config file.
     *
     * Only "main" config files, i.e. files named `nextflow.config`, are recorded.
     *
     * @param uri
     * @param refs
     */
    public void setCurrentVersions(URI uri, List<PluginRef> refs) {
        if( uri.getPath() == null || !uri.getPath().endsWith("nextflow.config") )
            return;
        if( refs.isEmpty() )
            return;
        var parent = Path.of(uri).getParent();
        this.pluginsMap.put(parent, refs);
    }

    /**
     * Get the currently loaded spec for a plugin.
     *
     * Given the URI of the including file, "./nextflow.config" is
     * used if present, otherwise "../nextflow.config" is used if
     * present, and so on.
     *
     * @param uri
     * @param name
     */
    public PluginSpec getCurrent(URI uri, String name) {
        var parent = Path.of(uri).getParent();
        var key = pluginsMap.keySet().stream()
            .filter(p -> parent.startsWith(p))
            .sorted((a, b) -> b.compareTo(a))
            .findFirst().orElse(null);
        if( key == null )
            return null;
        var ref = pluginsMap.get(key).stream()
            .filter(r -> r.name().equals(name))
            .findFirst().orElse(null);
        if( ref == null )
            return null;
        return cache.get(ref);
    }

}
