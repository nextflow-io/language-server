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
package nextflow.lsp.ast;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import groovy.lang.groovydoc.Groovydoc;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.ast.ASTUtils;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.runtime.StringGroovyMethods;

/**
 * Utility methods for retreiving text information for ast nodes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTNodeStringUtils {

    public static String getLabel(ASTNode node, ASTNodeCache ast) {
        if( node instanceof ClassNode cn )
            return toString(cn, ast);

        if( node instanceof FeatureFlagNode ffn )
            return toString(ffn);

        if( node instanceof FunctionNode fn )
            return toString(fn, ast);

        if( node instanceof ProcessNode pn )
            return toString(pn, ast);

        if( node instanceof WorkflowNode wn )
            return toString(wn, ast);

        if( node instanceof Variable var )
            return toString(var, ast);

        return null;
    }

    public static String toString(ClassNode classNode, ASTNodeCache ast) {
        var builder = new StringBuilder();
        if( classNode.isEnum() )
            builder.append("enum ");
        else
            builder.append("class ");
        builder.append(classNode.toString(false));
        return builder.toString();
    }

    public static String toString(FeatureFlagNode node) {
        var builder = new StringBuilder();
        builder.append("(feature flag) ");
        builder.append(node.name);
        return builder.toString();
    }

    public static String toString(FunctionNode node, ASTNodeCache ast) {
        var label = (String) node.getNodeMetaData("type.label");
        if( label != null ) {
            var builder = new StringBuilder();
            builder.append("(");
            builder.append(label);
            builder.append(") ");
            builder.append(node.getName());
            return builder.toString();
        }

        var builder = new StringBuilder();
        builder.append("def ");
        builder.append(node.getName());
        builder.append("(");
        builder.append(toString(node.getParameters(), ast));
        builder.append(")");
        var returnType = node.getReturnType();
        if( !ClassHelper.OBJECT_TYPE.equals(returnType) ) {
            builder.append(" -> ");
            builder.append(returnType.toString(false));
        }
        return builder.toString();
    }

    public static String toString(Parameter[] params, ASTNodeCache ast) {
        return Stream.of(params)
            .map(param -> toString(param, ast))
            .collect(Collectors.joining(", "));
    }

    public static String toString(ProcessNode node, ASTNodeCache ast) {
        var builder = new StringBuilder();
        builder.append("process ");
        builder.append(node.getName());
        return builder.toString();
    }

    public static String toString(WorkflowNode node, ASTNodeCache ast) {
        var builder = new StringBuilder();
        builder.append("workflow ");
        builder.append(node.getName() != null ? node.getName() : "<entry>");
        return builder.toString();
    }

    public static String toString(Variable variable, ASTNodeCache ast) {
        var builder = new StringBuilder();
        builder.append(variable.getName());
        var type = variable instanceof ASTNode
            ? ASTUtils.getTypeOfNode((ASTNode) variable, ast)
            : variable.getType();
        if( !ClassHelper.OBJECT_TYPE.equals(type) ) {
            builder.append(": ");
            builder.append(type.getNameWithoutPackage());
        }
        return builder.toString();
    }

    public static String getDocumentation(ASTNode node) {
        if( node instanceof FeatureFlagNode ffn )
            return getFeatureFlagDescription(ffn);

        if( node instanceof FunctionNode fn )
            return fn.documentation != null
                ? fn.documentation
                : groovydocToMarkdown(fn.getGroovydoc());

        if( node instanceof ProcessNode pn )
            return groovydocToMarkdown(pn.getGroovydoc());

        if( node instanceof WorkflowNode wn )
            return groovydocToMarkdown(wn.getGroovydoc());

        if( node instanceof AnnotatedNode an )
            return groovydocToMarkdown(an.getGroovydoc());

        return null;
    }

    private static String getFeatureFlagDescription(FeatureFlagNode node) {
        var clazz = FeatureFlagDsl.class;
        for( var field : clazz.getDeclaredFields() ) {
            var annot = field.getAnnotation(FeatureFlag.class);
            if( annot != null && annot.name().equals(node.name) )
                return StringGroovyMethods.stripIndent(annot.description(), true).trim();
        }
        throw new IllegalStateException();
    }

    private static String groovydocToMarkdown(Groovydoc groovydoc) {
        if( groovydoc == null || !groovydoc.isPresent() )
            return null;
        var content = groovydoc.getContent();
        var lines = content.split("\n");
        var builder = new StringBuilder();
        var n = lines.length;
        // strip end of groovydoc comment
        if( n == 1 ) {
            var c = lines[0].indexOf("*/");
            if( c != -1 )
                lines[0] = lines[0].substring(0, c);
        }
        // strip start of groovydoc coment
        var first = lines[0];
        var lengthToRemove = Math.min(first.length(), 3);
        appendLine(builder, first.substring(lengthToRemove));
        // append lines that start with an asterisk (*)
        for( int i = 1; i < n - 1; i++ ) {
            var line = lines[i];
            var star = line.indexOf('*');
            var at = line.indexOf('@');
            if( at == -1 && star > -1 )
                appendLine(builder, line.substring(star + 1));
        }
        return builder.toString().trim();
    }

    private static void appendLine(StringBuilder builder, String line) {
        line = reformatLine(line);
        if( line.length() == 0 )
            return;
        builder.append(line);
        builder.append("\n");
    }

    private static String reformatLine(String line) {
        line = line.replaceAll("<(\\w+)(?:\\s+\\w+(?::\\w+)?=(\"|\')[^\"\']*\\2)*\\s*(\\/{0,1})>", "<$1$3>");
        line = line.replaceAll("<pre>", "\n\n```\n");
        line = line.replaceAll("</pre>", "\n```\n");
        line = line.replaceAll("</?(em|i)>", "_");
        line = line.replaceAll("</?(strong|b)>", "**");
        line = line.replaceAll("</?code>", "`");
        line = line.replaceAll("<hr ?\\/>", "\n\n---\n\n");
        line = line.replaceAll("<(p|ul|ol|dl|li|dt|table|tr|div|blockquote)>", "\n\n");
        line = line.replaceAll("<br\\s*/?>\\s*", "  \n");
        line = line.replaceAll("<\\/{0,1}\\w+\\/{0,1}>", "");
        return line;
    }

}