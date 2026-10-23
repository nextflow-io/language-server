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
import java.nio.file.Path;

/**
 * Resolution of include sources.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Includes {

    private Includes() {
    }

    /**
     * Resolve the source of an `includeConfig` against the including
     * config file.
     *
     * @param uri
     * @param source
     * @return the resolved URI, or null if it cannot be resolved
     */
    public static URI configIncludeUri(URI uri, String source) {
        // return the source URI if it is already absolute (e.g. an http URL)
        try {
            var sourceUri = new URI(source);
            if( sourceUri.getScheme() != null )
                return sourceUri;
        }
        catch( Exception e ) {
            // ignore
        }
        // otherwise, resolve the source path against the including URI
        try {
            return Path.of(uri).getParent().resolve(source).normalize().toUri();
        }
        catch( Exception e ) {
            return null;
        }
    }

}
