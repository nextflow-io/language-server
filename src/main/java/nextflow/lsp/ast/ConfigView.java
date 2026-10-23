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
package nextflow.lsp.ast;

import java.net.URI;

import nextflow.config.ast.ConfigNode;
import nextflow.lsp.engine.Includes;

/**
 * Read-only view of the built config files of a project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface ConfigView extends AstView {

    /**
     * Check whether a file has syntax errors.
     *
     * @param uri
     */
    boolean hasSyntaxErrors(URI uri);

    /**
     * Check whether a file has any unresolved includes.
     *
     * @param uri
     */
    boolean hasIncludeErrors(URI uri);

    /**
     * Get the AST of a config file, or null if the file is unknown.
     *
     * @param uri
     */
    ConfigNode getConfigNode(URI uri);

    /**
     * Resolve the URI of an include source against the including file.
     *
     * @param uri
     * @param source
     * @return the resolved URI, or null if it cannot be resolved
     */
    static URI resolveIncludeUri(URI uri, String source) {
        return Includes.configIncludeUri(uri, source);
    }

}
