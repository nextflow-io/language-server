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
package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.ReferenceProvider
import nextflow.lsp.services.RenameProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.script.v2.IncludeVariable
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Find or rename all references of a symbol.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptReferenceProvider implements ReferenceProvider, RenameProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptReferenceProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends Location> references(TextDocumentIdentifier textDocument, Position position, boolean includeDeclaration) {
        if( ast == null ) {
            log.error("ast cache is empty while providing references")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Collections.emptyList()

        final symbolName = getSymbolName(offsetNode, position)
        if( !symbolName )
            return Collections.emptyList()

        final defNode = ASTUtils.getDefinition(offsetNode, false, ast)
        final isAlias = symbolName != getSymbolName(defNode, null)
        final references = ASTUtils.getReferences(defNode, ast, includeDeclaration)
        final List<Location> result = []
        for( final refNode : references ) {
            final refUri = ast.getURI(refNode)
            if( isAlias ) {
                if( refUri != uri || !isSameAlias(refNode, symbolName) )
                    continue
            }
            final location = LanguageServerUtils.astNodeToLocation(refNode, refUri)
            if( location )
                result.add(location)
        }

        return result
    }

    @Override
    WorkspaceEdit rename(TextDocumentIdentifier textDocument, Position position, String newName) {
        if( ast == null ) {
            log.error("ast cache is empty while providing rename")
            return null
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return null

        final oldName = getSymbolName(offsetNode, position)
        if( !oldName )
            return null

        // built-in names can't be renamed
        final defNode = ASTUtils.getDefinition(offsetNode, false, ast)
        if( !defNode || !ast.getURI(defNode) )
            return null

        final isAlias = oldName != getSymbolName(defNode, null)
        final references = ASTUtils.getReferences(defNode, ast, true)
        final Map<String,List<TextEdit>> changes = [:]
        for( final refNode : references ) {
            final refUri = ast.getURI(refNode)
            if( isAlias ) {
                if( refUri != uri || !isSameAlias(refNode, oldName) )
                    continue
            }
            final key = refUri.toString()
            if( !changes.containsKey(key) )
                changes.put(key, [])
            final textEdit = getTextEdit(refNode, oldName, newName)
            if( textEdit )
                changes.get(key).add(textEdit)
        }

        return new WorkspaceEdit(changes)
    }

    private String getSymbolName(ASTNode node, Position position) {
        if( node instanceof MethodNode )
            return node.getName()

        if( node instanceof IncludeVariable && position != null )
            return getIncludeNameOrAlias(node, position)

        if( node instanceof Variable )
            return node.getName()

        if( node instanceof ConstantExpression || node instanceof VariableExpression )
            return node.getText()

        return null
    }

    /**
     * Since IncludeVariable doesn't have source mappings for the
     * name and alias symbols, use the request position to determine
     * whether the name or alias was selected.
     *
     * @param node
     * @param position
     */
    private String getIncludeNameOrAlias(IncludeVariable node, Position position) {
        if( !node.alias )
            return node.name

        final offset = position.getCharacter() - (node.getColumnNumber() - 1)
        return offset <= node.@name.length()
            ? node.@name
            : node.alias
    }

    private boolean isSameAlias(ASTNode node, String alias) {
        final symbolName = node instanceof IncludeVariable
            ? node.alias
            : getSymbolName(node, null)
        return symbolName == alias
    }

    private TextEdit getTextEdit(ASTNode node, String oldName, String newName) {
        final range = LanguageServerUtils.astNodeToRange(node)

        if( node instanceof MethodNode ) {
            final firstLine = ast.getSourceText(node, false, 1)
            final oldNameStart = firstLine.indexOf(oldName)
            if( oldNameStart == -1 )
                return null

            final start = range.getStart()
            final end = range.getEnd()
            end.setLine(start.getLine())
            end.setCharacter(start.getCharacter() + oldNameStart + oldName.length())
            start.setCharacter(start.getCharacter() + oldNameStart)

            return new TextEdit(range, newName)
        }

        if( node instanceof IncludeVariable ) {
            String name = node.@name
            String alias = node.alias

            if( name == oldName )
                name = newName
            else if( alias == oldName )
                alias = newName
            else
                return null

            final newText = alias
                ? name + " as " + alias
                : name
            return new TextEdit(range, newText)
        }

        if( node instanceof ConstantExpression || node instanceof VariableExpression ) {
            if( node.getText() == oldName )
                return new TextEdit(range, newName)
        }

        return null
    }

}
