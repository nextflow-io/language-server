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

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import groovy.lang.groovydoc.Groovydoc;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.ast.ASTUtils;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.DslType;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.Function;
import nextflow.script.dsl.Operator;
import nextflow.script.dsl.OutputDsl;
import nextflow.script.dsl.ProcessDirectiveDsl;
import nextflow.script.dsl.ProcessInputDsl;
import nextflow.script.dsl.ProcessOutputDsl;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.runtime.StringGroovyMethods;

import static nextflow.script.v2.ASTHelpers.findAnnotation;

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

        if( node instanceof MethodNode mn )
            return toString(mn, ast);

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

    public static String toString(MethodNode node, ASTNodeCache ast) {
        if( node instanceof WorkflowNode wn ) {
            var builder = new StringBuilder();
            builder.append("workflow ");
            builder.append(wn.isEntry() ? "<entry>" : wn.getName());
            return builder.toString();
        }

        if( node instanceof ProcessNode pn ) {
            var builder = new StringBuilder();
            builder.append("process ");
            builder.append(pn.getName());
            return builder.toString();
        }

        var label = getMethodTypeLabel(node);
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

    private static String getMethodTypeLabel(MethodNode mn) {
        if( mn instanceof FunctionNode )
            return null;
        if( findAnnotation(mn, Operator.class).isPresent() )
            return "operator";
        var type = mn.getDeclaringClass().getTypeClass();
        if( type == ProcessDirectiveDsl.class )
            return "process directive";
        if( type == ProcessInputDsl.class )
            return "process input";
        if( type == ProcessOutputDsl.class )
            return "process output";
        if( type == OutputDsl.class )
            return "output directive";
        return null;
    }

    public static String toString(Parameter[] params, ASTNodeCache ast) {
        return Stream.of(params)
            .map(param -> toString(param, ast))
            .collect(Collectors.joining(", "));
    }

    public static String toString(Variable variable, ASTNodeCache ast) {
        var builder = new StringBuilder();
        builder.append(variable.getName());
        var type = variable instanceof ASTNode node
            ? ASTUtils.getTypeOfNode(node, ast)
            : variable.getType();
        if( !ClassHelper.OBJECT_TYPE.equals(type) ) {
            builder.append(": ");
            builder.append(type.getNameWithoutPackage());
        }
        return builder.toString();
    }

    public static String getDocumentation(ASTNode node) {
        if( node instanceof FeatureFlagNode ffn ) {
            if( ffn.accessedVariable instanceof AnnotatedNode an )
                return annotationValueToMarkdown(an, FeatureFlag.class, "description");
        }

        if( node instanceof FunctionNode fn )
            return groovydocToMarkdown(fn.getGroovydoc());

        if( node instanceof ProcessNode pn )
            return groovydocToMarkdown(pn.getGroovydoc());

        if( node instanceof WorkflowNode wn )
            return groovydocToMarkdown(wn.getGroovydoc());

        if( node instanceof ClassNode cn )
            return annotationValueToMarkdown(cn, DslType.class);

        if( node instanceof FieldNode fn )
            return annotationValueToMarkdown(fn, Constant.class);

        if( node instanceof MethodNode mn )
            return annotationValueToMarkdown(mn, Function.class);

        return null;
    }

    private static String annotationValueToMarkdown(AnnotatedNode node, Class type, String member) {
        return findAnnotation(node, type)
            .map((an) -> {
                var description = an.getMember(member).getText();
                return StringGroovyMethods.stripIndent(description, true).trim();
            })
            .orElse(null);
    }

    private static String annotationValueToMarkdown(AnnotatedNode node, Class type) {
        return annotationValueToMarkdown(node, type, "value");
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
