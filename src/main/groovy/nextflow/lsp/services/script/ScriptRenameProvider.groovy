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
import nextflow.lsp.services.RenameProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.script.v2.IncludeVariable
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptRenameProvider implements RenameProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptRenameProvider(ScriptAstCache ast) {
        this.ast = ast
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

        // built-in names can't be renamed
        final defNode = ASTUtils.getDefinition(offsetNode, false, ast)
        if( !defNode || !ast.getURI(defNode) )
            return null

        final oldName = getOldName(defNode)
        if( !oldName )
            return null

        final Map<String,List<TextEdit>> changes = [:]
        final references = ASTUtils.getReferences(defNode, ast, true, true)
        for( final refNode : references ) {
            final refUri = ast.getURI(refNode).toString()
            if( !changes.containsKey(refUri) )
                changes.put(refUri, [])
            final textEdit = getTextEdit(refNode, oldName, newName)
            if( textEdit )
                changes.get(refUri).add(textEdit)
        }

        return new WorkspaceEdit(changes)
    }

    private String getOldName(ASTNode node) {
        if( node instanceof MethodNode )
            return node.getName()

        if( node instanceof Variable )
            return node.getName()

        return null
    }

    private TextEdit getTextEdit(ASTNode node, String oldName, String newName) {
        final range = LanguageServerUtils.astNodeToRange(node)

        if( node instanceof MethodNode ) {
            // TODO: refactor Ranges.getSubstring() to use SourceUnit?
            final sourceUnit = ast.getSourceUnit(ast.getURI(node))
            final firstLine = sourceUnit.getSource().getLine(node.getLineNumber(), null)

            // select the method name (guaranteed to be on the first line)
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
            // TODO: what about aliases?
            final newText = node.getText().replaceAll(oldName, newName)
            return new TextEdit(range, newName)
        }

        if( node instanceof ConstantExpression || node instanceof VariableExpression ) {
            // TODO: what about aliases?
            return new TextEdit(range, newName)
        }

        return null
    }

}
