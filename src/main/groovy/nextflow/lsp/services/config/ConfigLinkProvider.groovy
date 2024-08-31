/*
 * Copyright 2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitorSupport
import nextflow.lsp.services.LinkProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.LanguageServerUtils
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Provide the locations of links in a document.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigLinkProvider implements LinkProvider {

    private static Logger log = Logger.instance

    private ConfigAstCache ast

    ConfigLinkProvider(ConfigAstCache ast) {
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

        final sourceUnit = ast.getSourceUnit(uri)
        final visitor = new ConfigLinkVisitor(sourceUnit)
        visitor.visit()

        return visitor.getLinks()
    }

}

@CompileStatic
class ConfigLinkVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit

    private URI uri

    private List<DocumentLink> links = []

    ConfigLinkVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
        this.uri = sourceUnit.getSource().getURI()
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ConfigNode )
            return
        super.visit((ConfigNode) moduleNode)
    }

    @Override
    void visitConfigInclude(ConfigIncludeNode node) {
        if( node.source !instanceof ConstantExpression )
            return
        final source = node.source.getText()
        final range = LanguageServerUtils.astNodeToRange(node.source)
        final target = getIncludeUri(uri, source).toString()
        links.add(new DocumentLink(range, target))
    }

    protected static URI getIncludeUri(URI uri, String source) {
        return Path.of(uri).getParent().resolve(source).normalize().toUri()
    }

    List<DocumentLink> getLinks() {
        return links
    }
}
