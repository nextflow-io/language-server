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
package nextflow.lsp.ast

import java.util.stream.Collectors

import groovy.lang.groovydoc.Groovydoc
import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.ast.ASTUtils
import nextflow.script.dsl.FeatureFlag
import nextflow.script.dsl.FeatureFlagDsl
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable

/**
 * Utility methods for retreiving text information for ast nodes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ASTNodeStringUtils {

    static String getLabel(ASTNode node, ASTNodeCache ast) {
        if( node instanceof ClassNode )
            return toString(node, ast)

        if( node instanceof FeatureFlagNode )
            return toString(node)

        if( node instanceof FunctionNode )
            return toString(node, ast)

        if( node instanceof ProcessNode )
            return toString(node, ast)

        if( node instanceof WorkflowNode )
            return toString(node, ast)

        if( node instanceof Variable )
            return toString(node, ast)

        return null
    }

    static String toString(ClassNode classNode, ASTNodeCache ast) {
        final builder = new StringBuilder()
        if( classNode.isEnum() )
            builder.append('enum ')
        else
            builder.append('class ')
        builder.append(classNode.toString(false))
        return builder.toString()
    }

    static String toString(FeatureFlagNode node) {
        final builder = new StringBuilder()
        builder.append('(feature flag) ')
        builder.append(node.name)
        return builder.toString()
    }

    static String toString(FunctionNode node, ASTNodeCache ast) {
        final label = (String) node.getNodeMetaData('type.label')
        if( label ) {
            final builder = new StringBuilder()
            builder.append('(')
            builder.append(label)
            builder.append(') ')
            builder.append(node.getName())
            return builder.toString()
        }

        final builder = new StringBuilder()
        builder.append('def ')
        builder.append(node.getName())
        builder.append('(')
        builder.append(toString(node.getParameters(), ast))
        builder.append(')')
        final returnType = node.getReturnType()
        if( returnType != ClassHelper.OBJECT_TYPE ) {
            builder.append(' -> ')
            builder.append(returnType.toString(false))
        }
        return builder.toString()
    }

    static String toString(Parameter[] params, ASTNodeCache ast) {
        return params.stream()
            .map(param -> toString(param, ast))
            .collect(Collectors.joining(', '))
    }

    static String toString(ProcessNode node, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append('process ')
        builder.append(node.getName())
        return builder.toString()
    }

    static String toString(WorkflowNode node, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append('workflow ')
        builder.append(node.getName() ?: '<entry>')
        return builder.toString()
    }

    static String toString(Variable variable, ASTNodeCache ast) {
        final builder = new StringBuilder()
        builder.append(variable.getName())
        final type = variable instanceof ASTNode
            ? ASTUtils.getTypeOfNode(variable, ast)
            : variable.getType()
        if( type != ClassHelper.OBJECT_TYPE ) {
            builder.append(': ')
            builder.append(type.getNameWithoutPackage())
        }
        return builder.toString()
    }

    static String getDocumentation(ASTNode node) {
        if( node instanceof FeatureFlagNode )
            return getFeatureFlagDescription(node)

        if( node instanceof FunctionNode )
            return node.documentation ?: groovydocToMarkdown(node.getGroovydoc())

        if( node instanceof ProcessNode )
            return groovydocToMarkdown(node.getGroovydoc())

        if( node instanceof WorkflowNode )
            return groovydocToMarkdown(node.getGroovydoc())

        if( node instanceof AnnotatedNode )
            return groovydocToMarkdown(node.getGroovydoc())

        return null
    }

    private static String getFeatureFlagDescription(FeatureFlagNode node) {
        final clazz = FeatureFlagDsl.class
        for( final field : clazz.getDeclaredFields() ) {
            final annot = field.getAnnotation(FeatureFlag)
            if( annot && annot.name() == node.name )
                return annot.description().stripIndent(true).trim()
        }
        throw new IllegalStateException()
    }

    private static String groovydocToMarkdown(Groovydoc groovydoc) {
        if( groovydoc == null || !groovydoc.isPresent() )
            return null
        final content = groovydoc.getContent()
        final lines = content.split('\n')
        final builder = new StringBuilder()
        final n = lines.length
        // strip end of groovydoc comment
        if( n == 1 ) {
            final c = lines[0].indexOf('*/')
            if( c != -1 )
                lines[0] = lines[0].substring(0, c)
        }
        // strip start of groovydoc coment
        final first = lines[0]
        final lengthToRemove = Math.min(first.length(), 3)
        appendLine(builder, first.substring(lengthToRemove))
        // append lines that start with an asterisk (*)
        for( int i = 1; i < n - 1; i++ ) {
            final line = lines[i]
            final star = line.indexOf('*')
            final at = line.indexOf('@')
            if( at == -1 && star > -1 )
                appendLine(builder, line.substring(star + 1))
        }
        return builder.toString().trim()
    }

    private static void appendLine(StringBuilder builder, String line) {
        line = reformatLine(line)
        if( line.length() == 0 )
            return
        builder.append(line)
        builder.append('\n')
    }

    private static String reformatLine(String line) {
        line = line.replaceAll('<(\\w+)(?:\\s+\\w+(?::\\w+)?=(\"|\')[^\"\']*\\2)*\\s*(\\/{0,1})>', '<$1$3>')
        line = line.replaceAll('<pre>', '\n\n```\n')
        line = line.replaceAll('</pre>', '\n```\n')
        line = line.replaceAll('</?(em|i)>', '_')
        line = line.replaceAll('</?(strong|b)>', '**')
        line = line.replaceAll('</?code>', '`')
        line = line.replaceAll('<hr ?\\/>', '\n\n---\n\n')
        line = line.replaceAll('<(p|ul|ol|dl|li|dt|table|tr|div|blockquote)>', '\n\n')
        line = line.replaceAll('<br\\s*/?>\\s*', '  \n')
        line = line.replaceAll('<\\/{0,1}\\w+\\/{0,1}>', '')
        return line
    }

}
