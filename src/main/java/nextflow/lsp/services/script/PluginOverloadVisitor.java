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

import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Expose the overloads of an included plugin function to
 * each call, so that the type checker can select the
 * overload that matches the call arguments.
 *
 * @see ResolvePluginIncludeVisitor
 */
public class PluginOverloadVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    public PluginOverloadVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn && sn.isTypingEnabled() )
            super.visit(sn);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        if( node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET) instanceof MethodNode mn ) {
            var overloads = mn.getNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS);
            if( overloads != null ) {
                node.removeNodeMetaData(ASTNodeMarker.METHOD_TARGET);
                node.putNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS, overloads);
            }
        }
        super.visitMethodCallExpression(node);
    }
}
