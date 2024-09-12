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
package nextflow.lsp.services.script;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import nextflow.lsp.ast.ASTNodeStringUtils;
import nextflow.lsp.ast.ASTUtils;
import nextflow.lsp.services.SignatureHelpProvider;
import nextflow.lsp.services.util.CustomFormattingOptions;
import nextflow.lsp.services.util.Formatter;
import nextflow.lsp.util.Logger;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpContext;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import static nextflow.script.v2.ASTHelpers.*;

public class ScriptSignatureHelpProvider implements SignatureHelpProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptSignatureHelpProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public SignatureHelp signatureHelp(TextDocumentIdentifier textDocument, Position position, SignatureHelpContext context) {
        if( ast == null ) {
            log.error("ast cache is empty while providing signature help");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( offsetNode == null )
            return null;

        var call = getMethodCall(offsetNode);
        if( call == null )
            return null;

        var result = context.getActiveSignatureHelp();
        if( result == null )
            result = astNodeToSignatureHelp(call);

        var activeParameter = getActiveParameter(offsetNode, call);
        var activeSignature = getActiveSignature(call, activeParameter);
        result.setActiveSignature(activeSignature);
        result.setActiveParameter(activeParameter);
        return result;
    }

    private MethodCall getMethodCall(ASTNode node) {
        if( node instanceof MethodCall call ) {
            return call;
        }

        if( node instanceof ArgumentListExpression ) {
            return getMethodCall(ast.getParent(node));
        }

        ASTNode current = node;
        while( current != null ) {
            if( current instanceof MethodCall call )
                return call;
            current = ast.getParent(current);
        }
        return null;
    }

    private SignatureHelp astNodeToSignatureHelp(MethodCall call) {
        var methods = ASTUtils.getMethodOverloadsFromCallExpression(call, ast);
        var signatures = methods.stream()
            .map((mn) -> {
                var documentation = ASTNodeStringUtils.getDocumentation(mn);
                var si = new SignatureInformation(
                    ASTNodeStringUtils.getLabel(mn, ast),
                    documentation != null ? new MarkupContent(MarkupKind.MARKDOWN, documentation) : null,
                    getParameterInfo(mn)
                );
                return si;
            })
            .collect(Collectors.toList());
        return new SignatureHelp(signatures, 0, 0);
    }

    private List<ParameterInformation> getParameterInfo(MethodNode mn) {
        if( mn instanceof ProcessNode pn ) {
            return asDirectives(pn.inputs)
                .map((call) -> {
                    var fmt = new Formatter(new CustomFormattingOptions(0, false, false));
                    fmt.append(call.getMethodAsString());
                    fmt.append(' ');
                    fmt.visitArguments(asMethodCallArguments(call), hasNamedArgs(call), false);
                    var label = fmt.toString();
                    var documentation = new MarkupContent(MarkupKind.MARKDOWN, "```groovy\n" + label + "\n```");
                    return new ParameterInformation(label, documentation);
                })
                .collect(Collectors.toList());
        }

        if( mn instanceof WorkflowNode wn ) {
            return asBlockStatements(wn.takes).stream()
                .map((take) -> {
                    var varX = asVarX(take);
                    return new ParameterInformation(varX.getName(), varX.getName());
                })
                .collect(Collectors.toList());
        }

        return Arrays.stream(mn.getParameters())
            .map(p -> new ParameterInformation(ASTNodeStringUtils.toString(p, ast)))
            .collect(Collectors.toList());
    }

    private int getActiveParameter(ASTNode node, MethodCall call) {
        var args = asMethodCallArguments(call);
        if( node == call || node == call.getArguments() ) {
            return args.size();
        }
        for( int i = 0; i < args.size(); i++ ) {
            if( contains(args.get(i), node) )
                return i;
        }
        return -1;
    }

    private static boolean contains(ASTNode a, ASTNode b) {
        return a.getLineNumber() <= b.getLineNumber()
            && a.getColumnNumber() <= b.getColumnNumber()
            && b.getLastLineNumber() <= a.getLastLineNumber()
            && b.getLastColumnNumber() <= a.getLastColumnNumber();
    }

    private int getActiveSignature(MethodCall call, int activeParameter) {
        var methods = ASTUtils.getMethodOverloadsFromCallExpression(call, ast);
        if( methods.size() == 1 )
            return 0;
        var best = ASTUtils.getMethodFromCallExpression(call, ast, activeParameter);
        return methods.indexOf(best);
    }

}
