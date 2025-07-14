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

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import groovy.lang.groovydoc.Groovydoc;
import groovy.transform.NamedParams;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.RecordNode;
import nextflow.script.ast.TupleParameter;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;
import nextflow.script.dsl.DslScope;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.Namespace;
import nextflow.script.dsl.ProcessDsl;
import nextflow.script.formatter.FormattingOptions;
import nextflow.script.formatter.Formatter;
import nextflow.script.types.TypesEx;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.runtime.StringGroovyMethods;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 * Utility methods for retreiving text information for ast nodes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTNodeStringUtils {

    public static String getLabel(ASTNode node) {
        if( node instanceof ClassNode cn && cn.isResolved() )
            return classToLabel(cn);

        if( node instanceof FeatureFlagNode ffn )
            return featureFlagToLabel(ffn);

        if( node instanceof WorkflowNode wn )
            return workflowToLabel(wn);

        if( node instanceof ProcessNodeV2 pn )
            return processToLabel(pn);

        if( node instanceof ProcessNodeV1 pn )
            return processToLabel(pn);

        if( node instanceof MethodNode mn )
            return methodToLabel(mn);

        if( node instanceof Parameter param )
            return parameterToLabel(param);

        if( node instanceof Variable var )
            return variableToLabel(var);

        return null;
    }

    private static String classToLabel(ClassNode node) {
        var builder = new StringBuilder();
        if( node instanceof RecordNode )
            builder.append("record ");
        else if( node.isEnum() )
            builder.append("enum ");
        else
            builder.append("class ");
        builder.append(node.getNameWithoutPackage());
        return builder.toString();
    }

    private static String featureFlagToLabel(FeatureFlagNode node) {
        var builder = new StringBuilder();
        builder.append("(feature flag) ");
        builder.append(node.name);
        return builder.toString();
    }

    private static String workflowToLabel(WorkflowNode node) {
        if( node.isEntry() )
            return "workflow <entry>";
        var fmt = new Formatter(new FormattingOptions(2, true));
        fmt.append("workflow ");
        fmt.append(node.getName());
        fmt.append(" {\n");
        fmt.incIndent();
        fmt.appendIndent();
        fmt.append("take:\n");
        var takes = node.getParameters();
        if( takes.length == 0 ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        for( var take : takes ) {
            fmt.appendIndent();
            fmt.append(take.getName());
            if( fmt.hasType(take) ) {
                fmt.append(": ");
                fmt.visitTypeAnnotation(take.getType());
            }
            fmt.appendNewLine();
        }
        fmt.appendNewLine();
        fmt.appendIndent();
        fmt.append("emit:\n");
        var emits = asBlockStatements(node.emits);
        if( emits.isEmpty() ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        emits.stream().forEach((stmt) -> {
            var emit = ((ExpressionStatement) stmt).getExpression();
            fmt.appendIndent();
            typedOutput(emit, fmt);
            fmt.appendNewLine();
        });
        fmt.decIndent();
        fmt.append('}');
        return fmt.toString();
    }

    private static void typedOutput(Expression output, Formatter fmt) {
        if( output instanceof AssignmentExpression assign ) {
            var target = assign.getLeftExpression();
            fmt.visit(target);
            var type = getType(target);
            if( fmt.hasType(type) ) {
                fmt.append(": ");
                fmt.append(TypesEx.getName(type));
            }
        }
        else {
            fmt.append(TypesEx.getName(getType(output)));
        }
    }

    private static String processToLabel(ProcessNodeV2 node) {
        var fmt = new Formatter(new FormattingOptions(2, true));
        fmt.append("process ");
        fmt.append(node.getName());
        fmt.append(" {\n");
        fmt.incIndent();
        fmt.appendIndent();
        fmt.append("input:\n");
        if( node.inputs.length == 0 ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        for( var input : node.inputs ) {
            fmt.appendIndent();
            if( input instanceof TupleParameter tp ) {
                if( tp.isRecord() ) {
                    fmt.append('(');
                    fmt.appendNewLine();
                    fmt.incIndent();
                    for( var p : tp.components ) {
                        fmt.appendIndent();
                        fmt.append(p.getName());
                        if( fmt.hasType(p) ) {
                            fmt.append(": ");
                            fmt.append(TypesEx.getName(p.getType()));
                        }
                        fmt.appendNewLine();
                    }
                    fmt.decIndent();
                    fmt.appendIndent();
                    fmt.append(')');
                }
                else {
                    var components = Arrays.stream(tp.components)
                        .map(p -> p.getName())
                        .collect(Collectors.joining(", "));
                    fmt.append('(');
                    fmt.append(components);
                    fmt.append(')');
                }
            }
            else {
                fmt.append(input.getName());
            }
            if( fmt.hasType(input) ) {
                fmt.append(": ");
                fmt.visitTypeAnnotation(input.getType());
            }
            fmt.appendNewLine();
        }
        fmt.appendNewLine();
        fmt.appendIndent();
        fmt.append("output:\n");
        var outputs = asBlockStatements(node.outputs);
        if( outputs.isEmpty() ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        outputs.stream().forEach((stmt) -> {
            var output = ((ExpressionStatement) stmt).getExpression();
            fmt.appendIndent();
            typedOutput(output, fmt);
            fmt.appendNewLine();
        });
        fmt.decIndent();
        fmt.append('}');
        return fmt.toString();
    }

    private static String processToLabel(ProcessNodeV1 node) {
        var fmt = new Formatter(new FormattingOptions(2, true));
        fmt.append("process ");
        fmt.append(node.getName());
        fmt.append(" {\n");
        fmt.incIndent();
        fmt.appendIndent();
        fmt.append("input:\n");
        if( asDirectives(node.inputs).count() == 0 ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        asDirectives(node.inputs).forEach((call) -> {
            fmt.appendIndent();
            fmt.append(call.getMethodAsString());
            fmt.append(' ');
            fmt.visitArguments(asMethodCallArguments(call), false);
            fmt.appendNewLine();
        });
        fmt.appendNewLine();
        fmt.appendIndent();
        fmt.append("output:\n");
        if( asDirectives(node.outputs).count() == 0 ) {
            fmt.appendIndent();
            fmt.append("<none>\n");
        }
        asDirectives(node.outputs).forEach((call) -> {
            fmt.appendIndent();
            fmt.append(call.getMethodAsString());
            fmt.append(' ');
            fmt.visitArguments(asMethodCallArguments(call), false);
            fmt.appendNewLine();
        });
        fmt.decIndent();
        fmt.append('}');
        return fmt.toString();
    }

    private static String methodToLabel(MethodNode node) {
        var an = findAnnotation(node, Constant.class);
        if( an.isPresent() ) {
            var name = an.get().getMember("value").getText();
            if( TypesEx.isNamespace(node) )
                return "(namespace) " + name;
            var fn = new FieldNode(name, 0xF, node.getReturnType(), node.getDeclaringClass(), null);
            return parameterToLabel(fn);
        }

        var label = methodTypeLabel(node);
        if( label != null ) {
            var builder = new StringBuilder();
            builder.append('(');
            builder.append(label);
            builder.append(") ");
            builder.append(node.getName());
            return builder.toString();
        }

        var declaringType = node.getDeclaringClass();
        var builder = new StringBuilder();
        if( node instanceof FunctionNode ) {
            builder.append("def ");
        }
        else if( isDeclaringTypeVisible(declaringType) ) {
            builder.append(TypesEx.getName(declaringType));
            builder.append(' ');
        }
        else if( Logger.isDebugEnabled() ) {
            builder.append('[');
            builder.append(TypesEx.getName(declaringType));
            builder.append("] ");
        }
        builder.append(node.getName());
        builder.append('(');
        builder.append(parametersToLabel(node.getParameters()));
        builder.append(')');
        if( TypesEx.hasReturnType(node) ) {
            builder.append(" -> ");
            builder.append(TypesEx.getName(node.getReturnType()));
        }
        return builder.toString();
    }

    private static boolean isDeclaringTypeVisible(ClassNode declaringType) {
        if( declaringType.implementsInterface(ClassHelper.makeCached(DslScope.class)) )
            return false;
        if( declaringType.implementsInterface(ClassHelper.makeCached(Namespace.class)) )
            return false;
        return true;
    }

    private static String methodTypeLabel(MethodNode mn) {
        if( mn instanceof FunctionNode )
            return null;
        var cn = mn.getDeclaringClass();
        if( cn.isPrimaryClassNode() )
            return null;
        var type = cn.getTypeClass();
        if( type == ProcessDsl.InputDslV1.class )
            return "process input";
        if( type == ProcessDsl.OutputDslV1.class )
            return "process output";
        return null;
    }

    public static String parametersToLabel(Parameter[] params) {
        var hasNamedParams = params.length > 0 && findAnnotation(params[0], NamedParams.class).isPresent();
        var builder = new StringBuilder();
        for( int i = hasNamedParams ? 1 : 0; i < params.length; i++ ) {
            builder.append(parameterToLabel(params[i]));
            if( i < params.length - 1 || hasNamedParams )
                builder.append(", ");
        }
        if( hasNamedParams )
            builder.append("[options]");
        return builder.toString();
    }

    private static boolean isNamedParams(Parameter parameter) {
        return findAnnotation(parameter, NamedParams.class).isPresent();
    }

    private static String parameterToLabel(Variable parameter) {
        var builder = new StringBuilder();
        builder.append(parameter.getName());
        var type = parameter.getType();
        if( type.isArray() )
            builder.append("...");
        if( !ClassHelper.isObjectType(type) || type.isGenericsPlaceHolder() ) {
            builder.append(": ");
            builder.append(TypesEx.getName(type));
        }
        return builder.toString();
    }

    private static String variableToLabel(Variable variable) {
        var builder = new StringBuilder();
        builder.append(variable.getName());
        var type = getType(variable);
        if( !ClassHelper.isObjectType(type) || type.isGenericsPlaceHolder() ) {
            builder.append(": ");
            builder.append(TypesEx.getName(type));
        }
        return builder.toString();
    }

    public static String getDocumentation(ASTNode node) {
        if( node instanceof FeatureFlagNode ffn ) {
            if( ffn.target instanceof AnnotatedNode an )
                return annotationValueToMarkdown(an);
        }

        if( node instanceof WorkflowNode wn )
            return groovydocToMarkdown(wn.getGroovydoc());

        if( node instanceof FunctionNode fn )
            return groovydocToMarkdown(fn.getGroovydoc());

        if( node instanceof ProcessNode pn )
            return groovydocToMarkdown(pn.getGroovydoc());

        if( node instanceof ClassNode cn ) {
            var result = groovydocToMarkdown(cn.getGroovydoc());
            if( result == null )
                result = annotationValueToMarkdown(cn);
            return result;
        }

        if( node instanceof FieldNode fn ) {
            var result = groovydocToMarkdown(fn.getGroovydoc());
            if( result == null )
                result = annotationValueToMarkdown(fn);
            return result;
        }

        if( node instanceof MethodNode mn ) {
            var result = groovydocToMarkdown(mn.getGroovydoc());
            if( result == null )
                result = annotationValueToMarkdown(mn);
            var namedParams = namedParams(mn.getParameters());
            if( namedParams != null )
                result = (result != null ? result : "") + namedParams;
            return result;
        }

        if( node instanceof Parameter param )
            return groovydocToMarkdown(param.getGroovydoc());

        return null;
    }

    private static String annotationValueToMarkdown(AnnotatedNode node) {
        return findAnnotation(node, Description.class)
            .map((an) -> {
                var description = an.getMember("value").getText();
                return StringGroovyMethods.stripIndent(description, true).trim();
            })
            .orElse(null);
    }

    private static String namedParams(Parameter[] parameters) {
        if( parameters.length == 0 )
            return null;
        var param = parameters[0];
        if( !TypesEx.isEqual(param.getType(), ClassHelper.MAP_TYPE) )
            return null;
        var namedParams = asNamedParams(param);
        if( namedParams.isEmpty() )
            return null;
        var builder = new StringBuilder();
        builder.append("\n\nAvailable options:\n");
        namedParams.forEach((name, an) -> {
            var namedParam = asNamedParam(an);
            builder.append("\n`");
            builder.append(name);
            if( !ClassHelper.isObjectType(namedParam.getType()) ) {
                builder.append(": ");
                builder.append(TypesEx.getName(namedParam.getType()));
            }
            builder.append("`\n");
        });
        return builder.toString();
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
        builder.append('\n');
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
