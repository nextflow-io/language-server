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

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitor
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ResolveIncludeVisitor extends ClassCodeVisitorSupport implements ConfigVisitor {

    private SourceUnit sourceUnit

    private URI uri

    private ConfigAstCache astCache

    private Set<URI> changedUris

    private List<SyntaxException> errors = []

    ResolveIncludeVisitor(SourceUnit sourceUnit, ConfigAstCache astCache, Set<URI> changedUris) {
        this.sourceUnit = sourceUnit
        this.uri = sourceUnit.getSource().getURI()
        this.astCache = astCache
        this.changedUris = changedUris
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
        final includeUri = getIncludeUri(uri, source)
        if( !isIncludeLocal(includeUri) || !isIncludeStale(includeUri) )
            return
        final includeUnit = astCache.getSourceUnit(includeUri)
        if( !includeUnit ) {
            addError("Invalid include source: '${includeUri}'", node)
            return
        }
    }

    protected static URI getIncludeUri(URI uri, String source) {
        return Path.of(uri).getParent().resolve(source).normalize().toUri()
    }

    protected static boolean isIncludeLocal(URI includeUri) {
        return includeUri.getScheme() == 'file'
    }

    protected boolean isIncludeStale(URI includeUri) {
        return uri in changedUris || includeUri in changedUris
    }

    List<SyntaxException> getErrors() {
        return errors
    }

    @Override
    void addError(String message, ASTNode node) {
        errors.add(new SyntaxException(message, node))
    }
}
