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

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.services.LinkProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.LanguageServerUtils
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Provide the locations of links in a document.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptLinkProvider implements LinkProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptLinkProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    List<DocumentLink> documentLink(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding document links")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        if( !ast.hasAST(uri) )
            return Collections.emptyList()

        final List<DocumentLink> result = []
        for( final node : ast.getIncludeNodes(uri) ) {
            final source = node.source.getText()
            if( source.startsWith('plugin/') )
                continue
            final range = LanguageServerUtils.astNodeToRange(node.source)
            final target = getIncludeUri(uri, source).toString()
            result.add(new DocumentLink(range, target))
        }

        return result
    }

    protected static URI getIncludeUri(URI uri, String source) {
        Path includePath = Path.of(uri).getParent().resolve(source)
        if( includePath.isDirectory() )
            includePath = includePath.resolve('main.nf')
        else if( !source.endsWith('.nf') )
            includePath = Path.of(includePath.toString() + '.nf')
        return includePath.normalize().toUri()
    }

}
