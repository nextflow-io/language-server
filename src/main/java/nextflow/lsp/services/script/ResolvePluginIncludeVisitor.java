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
package nextflow.lsp.services.script;

import java.util.List;

import nextflow.lsp.spec.PluginSpecCache;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * Resolve plugin includes against plugin specs.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ResolvePluginIncludeVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private PluginSpecCache pluginSpecCache;

    public ResolvePluginIncludeVisitor(SourceUnit sourceUnit, PluginSpecCache pluginSpecCache) {
        this.sourceUnit = sourceUnit;
        this.pluginSpecCache = pluginSpecCache;
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
        if( !source.startsWith("plugin/") )
            return;
        var pluginName = source.split("/")[1];
        var spec = pluginSpecCache.getCurrent(pluginName);
        if( spec == null ) {
            addError("Plugin '" + pluginName + "' does not exist or is not specified in the configuration file", node);
            return;
        }
        for( var entry : node.entries ) {
            var entryName = entry.name;
            var mn = findMethod(spec.functions(), entryName);
            if( mn != null ) {
                entry.setTarget(mn);
                continue;
            }
            if( findMethod(spec.factories(), entryName) != null )
                continue;
            if( findMethod(spec.operators(), entryName) != null )
                continue;
            addError("Included name '" + entryName + "' is not defined in plugin '" + pluginName + "'", node);
        }
    }

    private static MethodNode findMethod(List<MethodNode> methods, String name) {
        return methods.stream()
            .filter(mn -> mn.getName().equals(name))
            .findFirst().orElse(null);
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new ResolveIncludeError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class ResolveIncludeError extends SyntaxException implements PhaseAware {

        public ResolveIncludeError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.INCLUDE_RESOLUTION;
        }
    }
}
