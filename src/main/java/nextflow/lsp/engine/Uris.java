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
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Canonical form of file URIs, used as keys for all engine state.
 *
 * The canonical form is {@code Path.of(uri).normalize().toUri()}, so
 * that different spellings of the same file (percent-encoding,
 * "..", a drive letter as "c%3A") map to the same key.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Uris {

    private Uris() {
    }

    /**
     * Get the canonical URI of a client URI string, or empty if it
     * is malformed or not a {@code file:} URI.
     *
     * @param uri
     */
    public static Optional<URI> canonical(String uri) {
        if( uri == null )
            return Optional.empty();
        try {
            return canonical(new URI(uri));
        }
        catch( Exception e ) {
            return Optional.empty();
        }
    }

    /**
     * Get the canonical form of a URI, or empty if it is not a
     * {@code file:} URI.
     *
     * @param uri
     */
    public static Optional<URI> canonical(URI uri) {
        if( uri == null || !"file".equalsIgnoreCase(uri.getScheme()) )
            return Optional.empty();
        try {
            return Optional.of(Path.of(uri).normalize().toUri());
        }
        catch( IllegalArgumentException | FileSystemNotFoundException | SecurityException e ) {
            return Optional.empty();
        }
    }

}
