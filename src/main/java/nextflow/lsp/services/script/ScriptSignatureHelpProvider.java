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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import groovy.transform.NamedParams;
import nextflow.lsp.ast.ASTNodeStringUtils;
import nextflow.lsp.ast.ScriptView;
import nextflow.lsp.services.SignatureHelpProvider;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.TupleParameter;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Types;
import nextflow.script.formatter.Comment;
import nextflow.script.formatter.Comments;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpContext;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 * Provide the signature of the innermost call around the cursor,
 * with the parameter of the argument at the cursor.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSignatureHelpProvider implements SignatureHelpProvider {

    private static Logger log = Logger.getInstance();

    private ScriptView ast;

    public ScriptSignatureHelpProvider(ScriptView ast) {
        this.ast = ast;
    }

    @Override
    public SignatureHelp signatureHelp(TextDocumentIdentifier textDocument, Position position, SignatureHelpContext context) {
        if( ast == null ) {
            log.error("ast cache is empty while providing signature help");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        var cursor = Comment.position(position.getLine() + 1, position.getCharacter() + 1);
        var call = enclosingCall(ast.getNodesAtPosition(uri, position), cursor);
        if( call == null )
            return null;

        var signatures = signatures(call);
        if( signatures.isEmpty() )
            return null;

        var argument = activeArgument(call, cursor);
        var infos = signatures.stream()
            .map(s -> s.toInformation(argument))
            .toList();
        var activeSignature = activeSignature(call, signatures, argument, context);
        return new SignatureHelp(infos, activeSignature, infos.get(activeSignature).getActiveParameter());
    }

    /**
     * Get the innermost call whose parentheses contain the cursor,
     * unless the cursor is in a closure within that call.
     *
     * @param nodeStack
     * @param cursor
     */
    private static MethodCallExpression enclosingCall(List<ASTNode> nodeStack, long cursor) {
        for( var node : nodeStack ) {
            if( node instanceof ClosureExpression )
                return null;
            if( node instanceof MethodCallExpression mce && isInsideArguments(mce, cursor) )
                return mce;
        }
        return null;
    }

    private static boolean isInsideArguments(MethodCallExpression call, long cursor) {
        var method = call.getMethod();
        var start = method.getLineNumber() != -1
            ? Comment.end(method)
            : Comment.end(call.getObjectExpression());
        return start < cursor && cursor < Comment.end(call);
    }

    /**
     * The argument at the cursor: either a named argument, or the
     * positional argument with the given index.
     */
    private record Argument(int index, boolean named, boolean hasNamedArgs) {}

    private static Argument activeArgument(MethodCallExpression call, long cursor) {
        // named args are collected into the first argument, so restore the source order
        var elements = new ArrayList<Expression>();
        var hasNamedArgs = false;
        for( var arg : asMethodCallArguments(call) ) {
            if( arg instanceof NamedArgumentListExpression nale ) {
                elements.addAll(nale.getMapEntryExpressions());
                hasNamedArgs = true;
            }
            else {
                elements.add(arg);
            }
        }
        elements.sort(Comparator.comparingLong(Comment::start));

        int positional = 0;
        for( var el : elements ) {
            var named = el instanceof MapEntryExpression;
            if( cursor <= Comment.end(el) )
                return new Argument(positional, named, hasNamedArgs);
            if( !named )
                positional++;
        }

        if( elements.isEmpty() || call.getArguments().getNodeMetaData(ASTNodeMarker.TRAILING_COMMA) != null )
            return new Argument(positional, false, hasNamedArgs);
        var last = elements.get(elements.size() - 1);
        return last instanceof MapEntryExpression
            ? new Argument(positional, true, hasNamedArgs)
            : new Argument(positional - 1, false, hasNamedArgs);
    }

    private static int activeSignature(MethodCallExpression call, List<Signature> signatures, Argument argument, SignatureHelpContext context) {
        // keep the signature that the user selected
        var previous = context != null ? context.getActiveSignatureHelp() : null;
        if( previous != null && previous.getActiveSignature() != null && isSameSignatures(previous.getSignatures(), signatures) )
            return previous.getActiveSignature();

        // select the overload resolved by the type checker
        if( signatures.size() > 1 && call.getNodeMetaData(ASTNodeMarker.METHOD_TARGET) instanceof MethodNode target ) {
            for( int i = 0; i < signatures.size(); i++ ) {
                if( isSameMethod(signatures.get(i).method(), target) )
                    return i;
            }
        }

        for( int i = 0; i < signatures.size(); i++ ) {
            if( signatures.get(i).accepts(argument) )
                return i;
        }
        return 0;
    }

    private static boolean isSameSignatures(List<SignatureInformation> infos, List<Signature> signatures) {
        if( infos == null || infos.size() != signatures.size() )
            return false;
        for( int i = 0; i < infos.size(); i++ ) {
            if( !infos.get(i).getLabel().equals(signatures.get(i).label()) )
                return false;
        }
        return true;
    }

    /**
     * Determine whether a resolved method target was derived from
     * a given method, since the type checker replaces the target
     * with a copy whose generic types are resolved.
     *
     * @param original
     * @param resolved
     */
    private static boolean isSameMethod(MethodNode original, MethodNode resolved) {
        if( original == resolved )
            return true;
        var params1 = original.getParameters();
        var params2 = resolved.getParameters();
        if( !original.getName().equals(resolved.getName()) || params1.length != params2.length )
            return false;
        for( int i = 0; i < params1.length; i++ ) {
            var type = params1[i].getType();
            if( !type.isGenericsPlaceHolder() && !type.getName().equals(params2[i].getType().getName()) )
                return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Signature> signatures(MethodCallExpression call) {
        // resolve the candidates of a call that was not type-checked
        if( call.getNodeMetaData(ASTNodeMarker.METHOD_TARGET) == null && call.getNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS) == null )
            resolveMethodCall(call);

        var target = (MethodNode) call.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
        var overloads = (List<MethodNode>) call.getNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS);
        var methods =
            overloads != null ? overloads :
            target != null ? List.of(declaredMethod(call, target)) :
            List.<MethodNode>of();

        var name = call.getMethodAsString();
        var result = new ArrayList<Signature>();
        for( var mn : methods ) {
            // legacy processes and plugin functions have no known signature
            if( mn instanceof ProcessNodeV1 )
                continue;
            if( mn instanceof FunctionNode && mn.isSynthetic() )
                continue;
            result.add(signature(name, mn));
        }
        return result;
    }

    /**
     * Get the declared function of a call target, since the type checker
     * replaces the target of a function call that does not match the
     * function signature with a copy that has no Groovydoc.
     *
     * @param call
     * @param target
     */
    private MethodNode declaredMethod(MethodCallExpression call, MethodNode target) {
        if( target instanceof FunctionNode || !call.isImplicitThis() || target.getCode() == null )
            return target;
        return ast.getFunctionNodes().stream()
            .filter(fn -> fn.getCode() == target.getCode())
            .findFirst()
            .map(fn -> (MethodNode) fn)
            .orElse(target);
    }

    private Signature signature(String name, MethodNode mn) {
        var builder = new SignatureBuilder(name, mn);
        if( mn instanceof WorkflowNode wn && wn.isEntry() ) {
            var params = ScriptNode.getPipeline(wn).getParams();
            if( params != null )
                builder.parameter("params: Record", paramsBlockToMarkdown(params.declarations));
            return builder.build();
        }

        if( mn instanceof ProcessNodeV2 pn ) {
            var paramDocs = ASTNodeStringUtils.getParameterDocumentation(pn);
            for( var input : pn.inputs )
                builder.parameter(processInputToLabel(input), parameterDocumentation(mn, input, paramDocs));
            return builder.build();
        }

        if( mn instanceof WorkflowNode ) {
            var paramDocs = ASTNodeStringUtils.getParameterDocumentation(mn);
            for( var take : mn.getParameters() )
                builder.parameter(ASTNodeStringUtils.parameterToLabel(take), parameterDocumentation(mn, take, paramDocs));
            return builder.build();
        }

        var params = mn.getParameters();
        var namedParams = params.length > 0 && findAnnotation(params[0], NamedParams.class).isPresent();
        var positional = namedParams ? Arrays.copyOfRange(params, 1, params.length) : params;
        var paramDocs = ASTNodeStringUtils.getParameterDocumentation(mn);
        for( var param : positional )
            builder.parameter(ASTNodeStringUtils.parameterToLabel(param), parameterDocumentation(mn, param, paramDocs));
        if( namedParams )
            builder.namedParams(ASTNodeStringUtils.namedParams(params));
        builder.varargs(positional.length > 0 && positional[positional.length - 1].getType().isArray());
        if( Types.hasReturnType(mn) )
            builder.returnType(Types.getName(getReturnType(mn)));
        return builder.build();
    }

    private static String processInputToLabel(Parameter input) {
        if( !(input instanceof TupleParameter tp) )
            return ASTNodeStringUtils.parameterToLabel(input);
        var prefix = "Record".equals(tp.getType().getNameWithoutPackage()) ? "record" : "tuple";
        var components = Arrays.stream(tp.components)
            .map(ASTNodeStringUtils::parameterToLabel)
            .collect(Collectors.joining(", "));
        return prefix + "(" + components + ")";
    }

    private static String paramsBlockToMarkdown(Parameter[] params) {
        var builder = new StringBuilder();
        builder.append("```nextflow\n");
        for( var param : params ) {
            builder.append(ASTNodeStringUtils.parameterToLabel(param));
            builder.append('\n');
        }
        builder.append("```");
        return builder.toString();
    }

    /**
     * Get the documentation of a parameter from the `@param` tags
     * of its method, or else from a comment at the end of its line.
     *
     * @param mn
     * @param param
     * @param paramDocs
     */
    private String parameterDocumentation(MethodNode mn, Parameter param, Map<String,String> paramDocs) {
        var result = paramDocs.get(param.getName());
        if( result != null )
            return result;
        if( param.getLineNumber() == -1 )
            return null;
        var uri = ast.getURI(mn);
        var sourceUnit = uri != null ? ast.getSourceUnit(uri) : null;
        var moduleNode = sourceUnit != null ? sourceUnit.getAST() : null;
        var comments = moduleNode != null ? (Comments) moduleNode.getNodeMetaData(ASTNodeMarker.COMMENTS) : null;
        if( comments == null )
            return null;
        return comments.getComments().stream()
            .filter(c -> !c.ownLine && c.line == param.getLastLineNumber() && c.column >= param.getLastColumnNumber())
            .findFirst()
            .map(c -> commentToText(c.text))
            .orElse(null);
    }

    private static String commentToText(String text) {
        if( text.startsWith("//") )
            return text.substring(2).trim();
        if( text.startsWith("/*") && text.endsWith("*/") )
            return text.substring(2, text.length() - 2).trim();
        return text.trim();
    }

    private record Signature(
        MethodNode method,
        String label,
        List<ParameterInformation> parameters,
        int positionalCount,
        boolean namedParams,
        boolean varargs,
        String documentation
    ) {

        /**
         * Get the index of the parameter for an argument, or the
         * number of parameters if no parameter matches.
         *
         * @param argument
         */
        int activeParameter(Argument argument) {
            if( argument.named() )
                return namedParams ? positionalCount : parameters.size();
            if( argument.index() < positionalCount )
                return argument.index();
            if( varargs )
                return positionalCount - 1;
            return parameters.size();
        }

        boolean accepts(Argument argument) {
            if( argument.hasNamedArgs() && !namedParams )
                return false;
            return argument.named() || argument.index() < positionalCount || varargs;
        }

        SignatureInformation toInformation(Argument argument) {
            var result = new SignatureInformation(label);
            if( documentation != null )
                result.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation));
            result.setParameters(parameters);
            result.setActiveParameter(activeParameter(argument));
            return result;
        }
    }

    private static class SignatureBuilder {
        private final MethodNode method;
        private final StringBuilder label = new StringBuilder();
        private final List<ParameterInformation> parameters = new ArrayList<>();
        private int positionalCount = 0;
        private boolean namedParams = false;
        private String namedParamsDocumentation;
        private boolean varargs = false;
        private String returnType;

        SignatureBuilder(String name, MethodNode method) {
            this.method = method;
            label.append(name).append('(');
        }

        void parameter(String text, String documentation) {
            append(text, documentation);
            positionalCount++;
        }

        void namedParams(String documentation) {
            append("[options]", documentation != null ? documentation.trim() : null);
            namedParams = true;
            namedParamsDocumentation = documentation;
        }

        void varargs(boolean value) {
            varargs = value;
        }

        void returnType(String value) {
            returnType = value;
        }

        private void append(String text, String documentation) {
            if( !parameters.isEmpty() )
                label.append(", ");
            var start = label.length();
            label.append(text);
            var result = new ParameterInformation();
            result.setLabel(Either.forRight(new Tuple.Two<>(start, label.length())));
            if( documentation != null )
                result.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation));
            parameters.add(result);
        }

        Signature build() {
            label.append(')');
            if( returnType != null )
                label.append(" -> ").append(returnType);
            // named params are documented by the options parameter
            var documentation = ASTNodeStringUtils.getDocumentation(method);
            if( documentation != null && namedParamsDocumentation != null && documentation.endsWith(namedParamsDocumentation) )
                documentation = documentation.substring(0, documentation.length() - namedParamsDocumentation.length());
            if( documentation != null && documentation.isEmpty() )
                documentation = null;
            return new Signature(method, label.toString(), parameters, positionalCount, namedParams, varargs, documentation);
        }
    }

}
