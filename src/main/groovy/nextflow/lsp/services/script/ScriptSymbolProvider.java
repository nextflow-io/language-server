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
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
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
            log.error("ast cache is empty while peoviding document symbols");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Collections.emptyList();

        var definitions = ast.getDefinitions(uri);
        var result = new ArrayList<Either<SymbolInformation, DocumentSymbol>>();
        for( var node : definitions ) {
            if( !(node instanceof MethodNode) )
                continue;
            var name = getSymbolName(node);
            var range = LanguageServerUtils.astNodeToRange(node);
            if( range == null )
                continue;
            var symbol = new DocumentSymbol(name, SymbolKind.Method, range, range);
            result.add(Either.forRight(symbol));
        }

        return result;
    }

    @Override
    public List<? extends WorkspaceSymbol> symbol(String query) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding workspace symbols");
            return Collections.emptyList();
        }

        var lowerCaseQuery = query.toLowerCase();
        var definitions = ast.getDefinitions();
        var result = new ArrayList<WorkspaceSymbol>();
        for( var node : definitions ) {
            if( !(node instanceof MethodNode) )
                continue;
            var name = getSymbolName(node);
            if( name == null || !name.toLowerCase().contains(lowerCaseQuery) )
                continue;
            var uri = ast.getURI(node);
            var location = LanguageServerUtils.astNodeToLocation(node, uri);
            if( location == null )
                continue;
            var symbol = new WorkspaceSymbol(name, SymbolKind.Method, Either.forLeft(location));
            result.add(symbol);
        }

        return result;
    }

    private static String getSymbolName(ASTNode node) {
        if( node instanceof FunctionNode fn )
            return fn.getName();
        if( node instanceof ProcessNode pn )
            return pn.getName();
        if( node instanceof WorkflowNode wn )
            return wn.isEntry() ?  "<entry>" : wn.getName();
        return null;
    }

}
