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

import java.nio.file.Path;
import java.util.Optional;

/**
 * The kinds of file managed by the language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public enum FileKind {
    SCRIPT,
    CONFIG;

    /**
     * Get the kind of a file from its name: `*.nf` is a script, and
     * `*.config` (except `nf-test.config`) is a config file.
     *
     * @param path
     */
    public static Optional<FileKind> of(Path path) {
        var fileName = path.getFileName();
        if( fileName == null )
            return Optional.empty();
        var name = fileName.toString();
        if( name.endsWith(".nf") )
            return Optional.of(SCRIPT);
        if( name.endsWith(".config") && !name.equals("nf-test.config") )
            return Optional.of(CONFIG);
        return Optional.empty();
    }

    public static boolean isManaged(Path path) {
        return of(path).isPresent();
    }
}
