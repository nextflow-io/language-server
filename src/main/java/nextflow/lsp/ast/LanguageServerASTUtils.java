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
package nextflow.lsp.ast;

import java.util.Collections;
import java.util.Iterator;

import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.IncludeEntryNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 * Utility methods for querying an AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerASTUtils {

    /**
     * Get the ast node corresponding to the definition of a given
     * class, method, or variable.
     *
     * @param node
     */
    public static ASTNode getDefinition(ASTNode node) {
        if( node instanceof VariableExpression ve )
            return getDefinitionFromVariable(ve.getAccessedVariable());

        if( node instanceof MethodCallExpression mce ) {
            var mn = (MethodNode) mce.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
            if( mn != null )
                return mn;
            return resolveMethodCall(mce);
        }

        if( node instanceof PropertyExpression pe ) {
            var fn = (FieldNode) pe.getNodeMetaData(ASTNodeMarker.PROPERTY_TARGET);
            if( fn != null )
                return fn;
            return resolveProperty(pe);
        }

        if( node instanceof ClassExpression ce )
            return ce.getType().redirect();

        if( node instanceof ConstructorCallExpression cce )
            return cce.getType().redirect();

        if( node instanceof MapEntryExpression ) {
            var namedParam = (Parameter) node.getNodeMetaData("_NAMED_PARAM");
            if( namedParam != null )
                return namedParam;
        }

        if( node instanceof FeatureFlagNode ffn )
            return ffn.target != null ? ffn : null;

        if( node instanceof IncludeEntryNode entry )
            return entry.getTarget();

        if( node instanceof ClassNode cn )
            return node;

        if( node instanceof MethodNode )
            return node;

        if( node instanceof Variable )
            return node;

        return null;
    }

    private static ASTNode getDefinitionFromVariable(Variable variable) {
        // built-in variable or workflow/process as variable
        var mn = asMethodVariable(variable);
        if( mn != null )
            return mn;
        // local variable
        if( variable instanceof ASTNode node )
            return node;
        return null;
    }

    /**
     * Get the ast nodes corresponding to references of a node.
     *
     * @param node
     * @param ast
     * @param includeDeclaration
     */
    public static Iterator<ASTNode> getReferences(ASTNode node, ASTNodeCache ast, boolean includeDeclaration) {
        var defNode = getDefinition(node);
        if( defNode == null )
            return Collections.emptyIterator();
        return ast.getNodes().stream()
            .filter((otherNode) -> {
                if( otherNode.getLineNumber() == -1 || otherNode.getColumnNumber() == -1 )
                    return false;
                if( defNode == otherNode )
                    return includeDeclaration;
                return defNode == getDefinition(otherNode);
            })
            .iterator();
    }

}
