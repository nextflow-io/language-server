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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.ReferenceProvider;
import nextflow.lsp.services.RenameProvider;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.IncludeEntryNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * Find or rename all references of a symbol.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptReferenceProvider implements ReferenceProvider, RenameProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptReferenceProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<? extends Location> references(TextDocumentIdentifier textDocument, Position position, boolean includeDeclaration) {
        if( ast == null ) {
            log.error("ast cache is empty while providing references");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        var offsetNode = ast.getNodeAtPosition(uri, position);
        if( offsetNode == null )
            return Collections.emptyList();

        var symbolName = getSymbolName(offsetNode, position);
        if( symbolName == null )
            return Collections.emptyList();

        var defNode = LanguageServerASTUtils.getDefinition(offsetNode);
        var isAlias = !symbolName.equals(getSymbolName(defNode, null));
        var references = LanguageServerASTUtils.getReferences(defNode, ast, includeDeclaration);
        var result = new ArrayList<Location>();
        references.forEachRemaining((refNode) -> {
            var refUri = ast.getURI(refNode);
            if( isAlias ) {
                if( !uri.equals(refUri) || !isSameAlias(refNode, symbolName) )
                    return;
            }
            var location = LanguageServerUtils.astNodeToLocation(refNode, refUri);
            if( location != null )
                result.add(location);
        });

        return result;
    }

    @Override
    public WorkspaceEdit rename(TextDocumentIdentifier textDocument, Position position, String newName) {
        if( ast == null ) {
            log.error("ast cache is empty while providing rename");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        var offsetNode = ast.getNodeAtPosition(uri, position);
        if( offsetNode == null )
            return null;

        var oldName = getSymbolName(offsetNode, position);
        if( oldName == null )
            return null;

        // built-in names can't be renamed
        var defNode = LanguageServerASTUtils.getDefinition(offsetNode);
        if( defNode == null || ast.getURI(defNode) == null )
            return null;

        var isAlias = !oldName.equals(getSymbolName(defNode, null));
        var references = LanguageServerASTUtils.getReferences(defNode, ast, true);
        var changes = new HashMap<String,List<TextEdit>>();
        references.forEachRemaining((refNode) -> {
            var refUri = ast.getURI(refNode);
            if( isAlias ) {
                if( !uri.equals(refUri) || !isSameAlias(refNode, oldName) )
                    return;
            }
            var key = refUri.toString();
            if( !changes.containsKey(key) )
                changes.put(key, new ArrayList<>());
            var textEdit = getTextEdit(refNode, oldName, newName);
            if( textEdit != null )
                changes.get(key).add(textEdit);
        });

        return new WorkspaceEdit(changes);
    }

    private String getSymbolName(ASTNode node, Position position) {
        if( node instanceof ClassNode cn )
            return cn.getName();

        if( node instanceof MethodNode mn )
            return mn.getName();

        if( node instanceof IncludeEntryNode entry && position != null )
            return getIncludeNameOrAlias(entry, position);

        if( node instanceof Variable v )
            return v.getName();

        if( node instanceof ClassExpression || node instanceof ConstantExpression )
            return node.getText();

        return null;
    }

    /**
     * Since IncludeEntryNode doesn't have source mappings for the
     * name and alias symbols, use the request position to determine
     * whether the name or alias was selected.
     *
     * @param node
     * @param position
     */
    private String getIncludeNameOrAlias(IncludeEntryNode node, Position position) {
        if( node.alias == null )
            return node.name;

        var offset = position.getCharacter() - (node.getColumnNumber() - 1);
        return offset <= node.name.length()
            ? node.name
            : node.alias;
    }

    private boolean isSameAlias(ASTNode node, String alias) {
        var symbolName = node instanceof IncludeEntryNode entry
            ? entry.alias
            : getSymbolName(node, null);
        return alias.equals(symbolName);
    }

    private TextEdit getTextEdit(ASTNode node, String oldName, String newName) {
        var range = LanguageServerUtils.astNodeToRange(node);

        if( node instanceof ClassNode ) {
            var firstLine = ast.getSourceText(node, false, 1);
            var oldNameStart = firstLine.indexOf(oldName);
            if( oldNameStart == -1 )
                return null;

            var start = range.getStart();
            var end = range.getEnd();
            end.setLine(start.getLine());
            end.setCharacter(start.getCharacter() + oldNameStart + oldName.length());
            start.setCharacter(start.getCharacter() + oldNameStart);

            return new TextEdit(range, newName);
        }

        if( node instanceof MethodNode ) {
            var firstLine = ast.getSourceText(node, false, 1);
            var oldNameStart = firstLine.indexOf(oldName);
            if( oldNameStart == -1 )
                return null;

            var start = range.getStart();
            var end = range.getEnd();
            end.setLine(start.getLine());
            end.setCharacter(start.getCharacter() + oldNameStart + oldName.length());
            start.setCharacter(start.getCharacter() + oldNameStart);

            return new TextEdit(range, newName);
        }

        if( node instanceof IncludeEntryNode entry ) {
            String name = entry.name;
            String alias = entry.alias;

            if( oldName.equals(name) )
                name = newName;
            else if( oldName.equals(alias) )
                alias = newName;
            else
                return null;

            var newText = alias != null
                ? name + " as " + alias
                : name;
            return new TextEdit(range, newText);
        }

        // TODO: preserve type annotation
        if( node instanceof Variable v ) {
            if( oldName.equals(v.getName()) )
                return new TextEdit(range, newName);
        }

        if( node instanceof ClassExpression || node instanceof ConstantExpression ) {
            if( oldName.equals(node.getText()) )
                return new TextEdit(range, newName);
        }

        return null;
    }

}
