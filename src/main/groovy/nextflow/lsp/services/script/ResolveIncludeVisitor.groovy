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
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.ScriptVisitor
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ResolveIncludeVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private URI uri

    private ScriptAstCache astCache

    private Set<URI> changedUris

    private List<SyntaxException> errors = []

    private boolean changed

    ResolveIncludeVisitor(SourceUnit sourceUnit, ScriptAstCache astCache, Set<URI> changedUris) {
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
        if( moduleNode !instanceof ScriptNode )
            return
        final scriptNode = (ScriptNode) moduleNode
        for( final node : scriptNode.getIncludes() )
            visitInclude(node)
    }

    @Override
    void visitInclude(IncludeNode node) {
        final source = node.source.getText()
        if( source.startsWith('plugin/') )
            return
        final includeUri = getIncludeUri(uri, source)
        if( !isIncludeStale(node, includeUri) )
            return
        changed = true
        for( final module : node.modules )
            module.setMethod(null)
        final includeUnit = astCache.getSourceUnit(includeUri)
        if( !includeUnit ) {
            addError("Invalid include source: '${includeUri}'", node)
            return
        }
        if( !includeUnit.getAST() ) {
            addError("Module could not be parsed: '${includeUri}'", node)
            return
        }
        final definitions = astCache.getDefinitions(includeUri)
        for( final module : node.modules ) {
            final includedName = module.@name
            final includedNode = definitions.stream()
                .filter(defNode -> defNode.name == includedName)
                .findFirst()
            if( !includedNode.isPresent() ) {
                addError("Included name '${includedName}' is not defined in module '${includeUri}'", node)
                continue
            }
            module.setMethod(includedNode.get())
        }
    }

    protected static URI getIncludeUri(URI uri, String source) {
        Path includePath = Path.of(uri).getParent().resolve(source)
        if( includePath.isDirectory() )
            includePath = includePath.resolve('main.nf')
        else if( !source.endsWith('.nf') )
            includePath = Path.of(includePath.toString() + '.nf')
        return includePath.normalize().toUri()
    }

    protected boolean isIncludeStale(IncludeNode node, URI includeUri) {
        if( uri in changedUris || includeUri in changedUris )
            return true
        for( final module : node.modules ) {
            if( !module.getMethod() )
                return true
        }
        return false
    }

    @Override
    void addError(String message, ASTNode node) {
        errors.add(new IncludeException(message, node))
    }

    List<SyntaxException> getErrors() {
        return errors
    }

    boolean isChanged() {
        return changed
    }
}
