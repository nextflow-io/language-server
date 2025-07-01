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

import java.util.List;

import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

/**
 * Utility methods for retreiving completion information for ast nodes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class CompletionUtils {

    public static VariableScope getVariableScope(List<ASTNode> nodeStack) {
        for( var node : nodeStack ) {
            if( node instanceof BlockStatement block )
                return block.getVariableScope();
        }
        return null;
    }

    public static String astNodeToItemDetail(ASTNode node) {
        return ASTNodeStringUtils.getLabel(node);
    }

    public static MarkupContent astNodeToItemDocumentation(ASTNode node) {
        var documentation = ASTNodeStringUtils.getDocumentation(node);
        return documentation != null
            ? new MarkupContent(MarkupKind.MARKDOWN, documentation)
            : null;
    }

    public static CompletionItemKind astNodeToItemKind(ASTNode node) {
        if( node instanceof ClassNode cn ) {
            return cn.isEnum()
                ? CompletionItemKind.Enum
                : CompletionItemKind.Class;
        }
        if( node instanceof MethodNode ) {
            return CompletionItemKind.Method;
        }
        if( node instanceof Variable ) {
            return node instanceof FieldNode
                ? CompletionItemKind.Field
                : CompletionItemKind.Variable;
        }
        return CompletionItemKind.Property;
    }

    public static CompletionItemLabelDetails astNodeToItemLabelDetails(Object node) {
        var result = new CompletionItemLabelDetails();
        if( node instanceof ProcessNode pn ) {
            result.setDescription("process");
        }
        else if( node instanceof WorkflowNode pn ) {
            result.setDescription("workflow");
        }
        else if( node instanceof MethodNode mn && Types.isNamespace(mn) ) {
            result.setDescription("namespace");
        }
        else if( node instanceof MethodNode mn ) {
            result.setDetail("(" + ASTNodeStringUtils.parametersToLabel(mn.getParameters()) + ")");
            if( Types.hasReturnType(mn) )
                result.setDescription(Types.getName(mn.getReturnType()));
        }
        else if( node instanceof Variable variable ) {
            result.setDescription(Types.getName(variable.getType()));
        }
        return result;
    }

}
