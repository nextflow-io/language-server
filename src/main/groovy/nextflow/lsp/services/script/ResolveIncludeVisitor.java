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
package nextflow.lsp.services.script;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptVisitorSupport;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ResolveIncludeVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private URI uri;

    private ScriptAstCache astCache;

    private Set<URI> changedUris;

    private List<SyntaxException> errors = new ArrayList<>();

    private boolean changed;

    ResolveIncludeVisitor(SourceUnit sourceUnit, ScriptAstCache astCache, Set<URI> changedUris) {
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
        if( moduleNode instanceof ScriptNode sn )
            super.visit(sn);
    }

    @Override
    public void visitInclude(IncludeNode node) {
        var source = node.source.getText();
        if( source.startsWith("plugin/") )
            return;
        var includeUri = getIncludeUri(uri, source);
        if( !isIncludeStale(node, includeUri) )
            return;
        changed = true;
        for( var module : node.modules )
            module.setMethod(null);
        var includeUnit = astCache.getSourceUnit(includeUri);
        if( includeUnit == null ) {
            addError("Invalid include source: '" + includeUri + "'", node);
            return;
        }
        if( includeUnit.getAST() == null ) {
            addError("Module could not be parsed: '" + includeUri + "'", node);
            return;
        }
        var definitions = astCache.getDefinitions(includeUri);
        for( var module : node.modules ) {
            var includedName = module.name;
            var includedNode = definitions.stream()
                .filter(defNode -> includedName.equals(defNode.getName()))
                .findFirst();
            if( !includedNode.isPresent() ) {
                addError("Included name '" + includedName + "' is not defined in module '" + includeUri + "'", node);
                continue;
            }
            module.setMethod(includedNode.get());
        }
    }

    protected static URI getIncludeUri(URI uri, String source) {
        Path includePath = Path.of(uri).getParent().resolve(source);
        if( Files.isDirectory(includePath) )
            includePath = includePath.resolve("main.nf");
        else if( !source.endsWith(".nf") )
            includePath = Path.of(includePath.toString() + ".nf");
        return includePath.normalize().toUri();
    }

    protected boolean isIncludeStale(IncludeNode node, URI includeUri) {
        if( changedUris.contains(uri) || changedUris.contains(includeUri) )
            return true;
        for( var module : node.modules ) {
            if( module.getMethod() == null )
                return true;
        }
        return false;
    }

    @Override
    public void addError(String message, ASTNode node) {
        errors.add(new IncludeException(message, node));
    }

    public List<SyntaxException> getErrors() {
        return errors;
    }

    public boolean isChanged() {
        return changed;
    }
}