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
import java.util.Collections;
import java.util.List;

import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.CodeActionProvider;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.ProcessNode;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import static nextflow.lsp.util.JsonUtils.asJson;

/**
 * Get the code actions available at a position, which is currently
 * only the config preview for a process invocation.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptCodeActionProvider implements CodeActionProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptCodeActionProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<CodeAction> codeAction(TextDocumentIdentifier textDocument, Range range) {
        if( ast == null ) {
            log.error("ast cache is empty while providing code actions");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        var offsetNode = ast.getNodeAtPosition(uri, range.getStart());
        if( offsetNode == null )
            return Collections.emptyList();

        // the code lens on the definition covers the unqualified preview
        if( !(offsetNode instanceof MethodCallExpression call) )
            return Collections.emptyList();
        if( !(LanguageServerASTUtils.getDefinition(call) instanceof ProcessNode pn) )
            return Collections.emptyList();

        // the process may be defined in another file, so the preview is
        // given the defining uri rather than the uri of the call site
        var defUri = ast.getURI(pn);
        if( defUri == null )
            return Collections.emptyList();

        // an invocation has more than one qualified name when the workflow
        // that contains it is itself invoked from more than one place
        var names = new CallPathVisitor(ast).namesOf(call);
        if( names.isEmpty() )
            names = List.of(pn.getName());
        return names.stream()
            .map(name -> previewAction(defUri, pn.getName(), name))
            .toList();
    }

    private static CodeAction previewAction(URI defUri, String processName, String qualifiedName) {
        var arguments = List.of(asJson(defUri.toString()), asJson(processName), asJson(qualifiedName));
        var command = new Command("Preview config", "nextflow.previewConfig", arguments);
        var action = new CodeAction("Preview config for " + qualifiedName);
        action.setKind(CodeActionKind.Empty);
        action.setCommand(command);
        return action;
    }

}
