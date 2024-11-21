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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nextflow.lsp.services.SymbolProvider;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Provide the set of document symbols for a source file,
 * which can be used for efficient lookup and traversal.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSymbolProvider implements SymbolProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptSymbolProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing document symbols");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Collections.emptyList();

        var result = new ArrayList<Either<SymbolInformation, DocumentSymbol>>();
        for( var node : ast.getEnumNodes(uri) )
            addDocumentSymbol(node, result);
        for( var node : ast.getDefinitions(uri) )
            addDocumentSymbol(node, result);

        return result;
    }

    private void addDocumentSymbol(ASTNode node, List<Either<SymbolInformation, DocumentSymbol>> result) {
        var name = getSymbolName(node);
        var range = LanguageServerUtils.astNodeToRange(node);
        if( range == null )
            return;
        result.add(Either.forRight(new DocumentSymbol(name, getSymbolKind(node), range, range)));
    }

    @Override
    public List<? extends WorkspaceSymbol> symbol(String query) {
        if( ast == null ) {
            log.error("ast cache is empty while providing workspace symbols");
            return Collections.emptyList();
        }

        var result = new ArrayList<WorkspaceSymbol>();
        for( var node : ast.getEnumNodes() )
            addWorkspaceSymbol(node, query, result);
        for( var node : ast.getDefinitions() )
            addWorkspaceSymbol(node, query, result);

        return result;
    }

    private void addWorkspaceSymbol(ASTNode node, String query, List<WorkspaceSymbol> result) {
        var name = getSymbolName(node);
        if( name == null || !name.toLowerCase().contains(query.toLowerCase()) )
            return;
        var uri = ast.getURI(node);
        var location = LanguageServerUtils.astNodeToLocation(node, uri);
        if( location == null )
            return;
        result.add(new WorkspaceSymbol(name, getSymbolKind(node), Either.forLeft(location)));
    }

    private static String getSymbolName(ASTNode node) {
        if( node instanceof ClassNode cn && cn.isEnum() )
            return "enum " + cn.getName();
        if( node instanceof FunctionNode fn )
            return "function " + fn.getName();
        if( node instanceof ProcessNode pn )
            return "process " + pn.getName();
        if( node instanceof WorkflowNode wn )
            return wn.isEntry()
                ? "workflow <entry>"
                : "workflow " + wn.getName();
        return null;
    }

    private static SymbolKind getSymbolKind(ASTNode node) {
        if( node instanceof ClassNode cn && cn.isEnum() )
            return SymbolKind.Enum;
        if( node instanceof MethodNode mn )
            return SymbolKind.Function;
        return null;
    }

}
