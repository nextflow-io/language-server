/*
 * Copyright 2024-2025, Seqera Labs
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
package nextflow.lsp.services.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.ast.ConfigNode;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.lsp.services.LinkProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.LanguageServerUtils;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Provide the locations of links in a document.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigLinkProvider implements LinkProvider {

    private static Logger log = Logger.getInstance();

    private ConfigAstCache ast;

    public ConfigLinkProvider(ConfigAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<DocumentLink> documentLink(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing document links");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Collections.emptyList();

        var sourceUnit = ast.getSourceUnit(uri);
        var visitor = new ConfigLinkVisitor(sourceUnit);
        visitor.visit();

        return visitor.getLinks();
    }

}

class ConfigLinkVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private URI uri;

    private List<DocumentLink> links = new ArrayList<>();

    public ConfigLinkVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.uri = sourceUnit.getSource().getURI();
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ConfigNode cn )
            super.visit(cn);
    }

    @Override
    public void visitConfigInclude(ConfigIncludeNode node) {
        if( !(node.source instanceof ConstantExpression) )
            return;
        var source = node.source.getText();
        var range = LanguageServerUtils.astNodeToRange(node.source);
        var target = getIncludeUri(uri, source).toString();
        links.add(new DocumentLink(range, target));
    }

    protected static URI getIncludeUri(URI uri, String source) {
        // return source URI if it is already an absolute URI (e.g. http URL)
        try {
            var sourceUri = new URI(source);
            if( sourceUri.getScheme() != null )
                return sourceUri;
        }
        catch( Exception e ) {
            // ignore
        }
        // otherwise, resolve the source path against the including URI
        return Path.of(uri).getParent().resolve(source).normalize().toUri();
    }

    public List<DocumentLink> getLinks() {
        return links;
    }
}
