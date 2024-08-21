/*
 * Copyright 2024, Seqera Labs
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
package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.ReferenceProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Get the locations of all references of a symbol.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptReferenceProvider implements ReferenceProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptReferenceProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends Location> references(TextDocumentIdentifier textDocument, Position position, boolean includeDeclaration) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Collections.emptyList()

        final references = ASTUtils.getReferences(offsetNode, ast, includeDeclaration, true)
        final List<Location> result = []
        for( final refNode : references ) {
            final refUri = ast.getURI(refNode)
            final location = LanguageServerUtils.astNodeToLocation(refNode, refUri)
            if( location )
                result.add(location)
        }

        return result
    }

}
