package nextflow.lsp.util

import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable

/**
 * Utility methods for converting ast nodes to text.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
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

    static String constructorToString(ConstructorNode constructorNode, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append(constructorNode.getDeclaringClass().getName())
        builder.append('(')
        builder.append(parametersToString(constructorNode.getParameters(), ast))
        builder.append(')')
        return builder.toString()
    }

    static String methodToString(MethodNode methodNode, ASTNodeCache ast) {
        if( methodNode instanceof ConstructorNode )
            return constructorToString(methodNode, ast)

        final builder = new StringBuilder()
        final returnType = methodNode.getReturnType()
        builder.append('def ')
        // builder.append(returnType.getNameWithoutPackage())
        // builder.append(' ')
        builder.append(methodNode.getName())
        builder.append('(')
        builder.append(parametersToString(methodNode.getParameters(), ast))
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
