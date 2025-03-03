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
package nextflow.lsp.services.config;

import java.net.URI;
import java.util.List;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.ast.ConfigNode;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.lsp.services.SemanticTokensProvider;
import nextflow.lsp.services.SemanticTokensVisitor;
import nextflow.lsp.util.Logger;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSemanticTokensProvider implements SemanticTokensProvider {

    private static Logger log = Logger.getInstance();

    private ConfigAstCache ast;

    public ConfigSemanticTokensProvider(ConfigAstCache ast) {
        this.ast = ast;
    }

    @Override
    public SemanticTokens semanticTokensFull(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing semantic tokens");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return null;

        var sourceUnit = ast.getSourceUnit(uri);
        var visitor = new Visitor(sourceUnit);
        visitor.visit();
        return visitor.getTokens();
    }

    private static class Visitor extends ConfigVisitorSupport {

        private SourceUnit sourceUnit;

        private SemanticTokensVisitor tok;

        public Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
            this.tok = new SemanticTokensVisitor();
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

        public SemanticTokens getTokens() {
            return tok.getTokens();
        }

        // config statements

        @Override
        public void visitConfigAssign(ConfigAssignNode node) {
            tok.visit(node.value);
        }

        @Override
        public void visitConfigInclude(ConfigIncludeNode node) {
            tok.visit(node.source);
        }

    }

}
