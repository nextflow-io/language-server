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
import nextflow.lsp.services.DefinitionProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Get the location of the definition of a symbol.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptDefinitionProvider implements DefinitionProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptDefinitionProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    Either<List<? extends Location>, List<? extends LocationLink>> definition(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint")
            return Either.forLeft(Collections.emptyList())
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Either.forLeft(Collections.emptyList())

        final definitionNode = ASTUtils.getDefinition(offsetNode, true, ast)
        if( !definitionNode )
            return Either.forLeft(Collections.emptyList())

        final definitionUri = ast.getURI(definitionNode) ?: uri
        final location = LanguageServerUtils.astNodeToLocation(definitionNode, definitionUri)
        if( !location )
            return Either.forLeft(Collections.emptyList())

        return Either.forLeft(Collections.singletonList(location))
    }

}
