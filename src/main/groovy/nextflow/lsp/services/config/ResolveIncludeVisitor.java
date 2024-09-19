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
package nextflow.lsp.services.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import nextflow.config.v2.ConfigIncludeNode;
import nextflow.config.v2.ConfigNode;
import nextflow.config.v2.ConfigVisitorSupport;
import nextflow.lsp.compiler.PhaseAware;
import nextflow.lsp.compiler.Phases;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ResolveIncludeVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private URI uri;

    private ConfigAstCache astCache;

    private Set<URI> changedUris;

    private List<SyntaxErrorMessage> errors = new ArrayList<>();

    private boolean changed;

    ResolveIncludeVisitor(SourceUnit sourceUnit, ConfigAstCache astCache, Set<URI> changedUris) {
        this.sourceUnit = sourceUnit;
        this.uri = sourceUnit.getSource().getURI();
        this.astCache = astCache;
        this.changedUris = changedUris;
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
        var includeUri = getIncludeUri(uri, source);
        if( !isIncludeLocal(includeUri) || !isIncludeStale(includeUri) )
            return;
        changed = true;
        var includeUnit = astCache.getSourceUnit(includeUri);
        if( includeUnit == null ) {
            addError("Invalid include source: '" + includeUri + "'", node);
            return;
        }
    }

    protected static URI getIncludeUri(URI uri, String source) {
        return Path.of(uri).getParent().resolve(source).normalize().toUri();
    }

    protected static boolean isIncludeLocal(URI includeUri) {
        return "file".equals(includeUri.getScheme());
    }

    protected boolean isIncludeStale(URI includeUri) {
        return changedUris.contains(uri) || changedUris.contains(includeUri);
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new ResolveIncludeException(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        errors.add(errorMessage);
    }

    public List<SyntaxErrorMessage> getErrors() {
        return errors;
    }

    public boolean isChanged() {
        return changed;
    }

    private class ResolveIncludeException extends SyntaxException implements PhaseAware {

        public ResolveIncludeException(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.INCLUDE_RESOLUTION;
        }
    }
}
