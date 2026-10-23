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

import java.util.List;

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Registers file watchers with the client, for the files whose
 * changes on disk affect the build.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class WatcherRegistration {

    static final String ID = "nextflow/watchedFiles";

    static final String METHOD = "workspace/didChangeWatchedFiles";

    static final List<String> PATTERNS = List.of(
        "**/*.nf",
        "**/*.config",
        "**/nextflow_schema.json",
        "**/lib/**/*.groovy",
        "**/lib/*.jar"
    );

    private WatcherRegistration() {
    }

    /**
     * Register the watchers. The response is only logged if the
     * registration fails.
     *
     * @param engine
     */
    static void register(Engine engine) {
        var client = engine.client();
        if( client == null )
            return;
        var watchers = PATTERNS.stream()
            .map(pattern -> new FileSystemWatcher(Either.forLeft(pattern)))
            .toList();
        var options = new DidChangeWatchedFilesRegistrationOptions(watchers);
        var registration = new Registration(ID, METHOD, options);
        var response = client.registerCapability(new RegistrationParams(List.of(registration)));
        if( response == null )
            return;
        response.whenComplete((result, error) -> {
            if( error != null )
                System.err.println("Failed to register file watchers: " + error);
        });
    }

}
