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
package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.dsl.ConfigSchema
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.util.Logger
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.InsertTextMode
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigCompletionProvider implements CompletionProvider {

    private static final List<CompletionItem> SCOPES

    static {
        SCOPES = ConfigSchema.SCOPES.collect { name, scope ->
            final item = new CompletionItem(name + ' {')
            item.setKind(CompletionItemKind.Property)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, scope.description().stripIndent(true).trim()))
            item.setInsertText(
                """
                ${name} {
                  \$1
                }
                """.stripIndent(true).trim()
            )
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    ConfigCompletionProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    Either<List<CompletionItem>, CompletionList> completion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding completions")
            return Either.forLeft(Collections.emptyList())
        }

        final uri = URI.create(textDocument.getUri())
        final nodeTree = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !nodeTree )
            return Either.forLeft(SCOPES)

        final scope = getCurrentScope(nodeTree)
        final items = scope ? getConfigOptions(scope + '.') : SCOPES
        return Either.forLeft(items)
    }

    String getCurrentScope(List<ASTNode> nodeTree) {
        final names = []
        for( final node : nodeTree.asReversed() ) {
            if( node instanceof ConfigBlockNode )
                names << node.name
        }
        return names.join('.')
    }

    @Memoized(maxCacheSize = 10)
    List<CompletionItem> getConfigOptions(String prefix) {
        ConfigSchema.OPTIONS
            .findAll { name, documentation -> name.startsWith(prefix) }
            .collect { name, documentation ->
                final relativeName = name.replace(prefix, '')
                final item = new CompletionItem(relativeName)
                item.setKind(CompletionItemKind.Property)
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
                item.setInsertText("${relativeName} = \$1")
                item.setInsertTextFormat(InsertTextFormat.Snippet)
                item.setInsertTextMode(InsertTextMode.AdjustIndentation)
                return item
            }
    }

}
