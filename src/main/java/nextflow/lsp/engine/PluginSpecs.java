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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import nextflow.lsp.spec.PluginRef;
import nextflow.lsp.spec.PluginSpec;
import org.eclipse.lsp4j.MessageType;

/**
 * Plugin specs fetched from a plugin registry.
 *
 * Specs are fetched asynchronously on the IO pool and cached per
 * release, including releases that don't exist. A failed fetch is
 * retried at most once per {@link #RETRY_INTERVAL}. When a fetch
 * completes, the workspace bumps the files that looked it up.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class PluginSpecs {

    static final Duration RETRY_INTERVAL = Duration.ofMinutes(5);

    enum State { PENDING, READY, MISSING, FAILED }

    /**
     * The result of a lookup.
     *
     * @param spec the spec, if available
     * @param pending whether the spec is not known yet, because it is
     *   being fetched or its last fetch failed
     */
    record Lookup(Optional<PluginSpec> spec, boolean pending) {}

    private static final class Entry {
        State state = State.PENDING;
        PluginSpec spec;
        Instant retryAt;
    }

    private final Engine engine;

    private final String registryUrl;

    private final PluginRegistryClient client;

    private final Map<PluginRef, Entry> cache = new HashMap<>();

    PluginSpecs(Engine engine, String registryUrl) {
        this.engine = engine;
        this.registryUrl = registryUrl;
        this.client = engine.environment().registry(registryUrl);
    }

    /**
     * Look up the spec of a plugin release, and start fetching it if
     * it has not been fetched yet. Failed fetches are retried by
     * {@link #retryFailed}, which runs before every build.
     *
     * @param ref
     */
    Lookup lookup(PluginRef ref) {
        var entry = cache.get(ref);
        if( entry == null ) {
            entry = new Entry();
            cache.put(ref, entry);
            fetch(ref);
        }
        return switch( entry.state ) {
            case READY -> new Lookup(Optional.of(entry.spec), false);
            case PENDING, FAILED -> new Lookup(Optional.empty(), true);
            case MISSING -> new Lookup(Optional.empty(), false);
        };
    }

    /**
     * Fetch again every spec whose last fetch failed long enough ago.
     * The files that looked it up are rebuilt when the fetch completes.
     */
    void retryFailed() {
        var now = engine.environment().clock().instant();
        for( var e : cache.entrySet() ) {
            var entry = e.getValue();
            if( entry.state == State.FAILED && !now.isBefore(entry.retryAt) ) {
                entry.state = State.PENDING;
                fetch(e.getKey());
            }
        }
    }

    /**
     * Apply the result of a fetch.
     *
     * @param event
     * @return true if the event applies to this registry
     */
    boolean onFetched(Event.PluginFetched event) {
        if( !registryUrl.equals(event.registryUrl()) )
            return false;
        var entry = cache.get(event.ref());
        if( entry == null || entry.state != State.PENDING )
            return false;
        var result = event.result();
        if( result instanceof PluginRegistryClient.Found found ) {
            entry.state = State.READY;
            entry.spec = found.spec();
        }
        else if( result instanceof PluginRegistryClient.NotFound ) {
            entry.state = State.MISSING;
        }
        else if( result instanceof PluginRegistryClient.Failed failed ) {
            entry.state = State.FAILED;
            entry.retryAt = engine.environment().clock().instant().plus(RETRY_INTERVAL);
            engine.log(MessageType.Warning, "Failed to fetch plugin " + refString(event.ref()) + " from registry: " + failed.reason());
        }
        return true;
    }

    private void fetch(PluginRef ref) {
        engine.submitFetch("plugin fetch " + refString(ref), () -> {
            PluginRegistryClient.Result result;
            try {
                result = client.fetch(ref.name(), ref.version());
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt();
                result = new PluginRegistryClient.Failed("interrupted");
            }
            catch( RuntimeException e ) {
                result = new PluginRegistryClient.Failed(e.toString());
            }
            return new Event.PluginFetched(registryUrl, ref, result);
        });
    }

    private static String refString(PluginRef ref) {
        return ref.version() != null ? ref.name() + "@" + ref.version() : ref.name();
    }

}
