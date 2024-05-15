package nextflow.lsp.util

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable

/**
 * Utility methods for converting ast nodes to text.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ASTNodeToStringUtils {

    static String classToString(ClassNode classNode, ASTNodeCache ast) {
        final builder = new StringBuilder()
        if( classNode.isEnum() )
            builder.append('enum ')
        else
            builder.append('class ')
        builder.append(classNode.getNameWithoutPackage())
        return builder.toString()
    }

    static String functionToString(FunctionNode node, ASTNodeCache ast) {
        final builder = new StringBuilder()
        final returnType = node.getReturnType()
        builder.append('def ')
        // builder.append(returnType.getNameWithoutPackage())
        // builder.append(' ')
        builder.append(node.getName())
        builder.append('(')
        builder.append(parametersToString(node.getParameters(), ast))
        builder.append(')')
        return builder.toString()
    }

    static String parametersToString(Parameter[] params, ASTNodeCache ast) {
        final builder = new StringBuilder()
        for( int i = 0; i < params.length; i++ ) {
            if( i > 0 )
                builder.append(', ')
            builder.append(variableToString(params[i], ast))
        }
        return builder.toString()
    }

    static String processToString(ProcessNode node, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append('process ')
        builder.append(node.getName())
        return builder.toString()
    }

    static String workflowToString(WorkflowNode node, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append('workflow ')
        builder.append(node.getName() ?: '<entry>')
        return builder.toString()
    }

    static String variableToString(Variable variable, ASTNodeCache ast) {
        final builder = new StringBuilder()
        final varType = variable instanceof ASTNode
            ? ASTUtils.getTypeOfNode(variable, ast)
            : variable.getType()
        builder.append(varType.getNameWithoutPackage())
        builder.append(' ')
        builder.append(variable.getName())
        return builder.toString()
    }

}
