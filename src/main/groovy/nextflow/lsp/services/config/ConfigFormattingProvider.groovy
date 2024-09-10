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
package nextflow.lsp.services.config

import java.util.regex.Pattern

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAppendNode
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitorSupport
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.util.CustomFormattingOptions
import nextflow.lsp.services.util.Formatter
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Provide formatting for a config file.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigFormattingProvider implements FormattingProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    ConfigFormattingProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends TextEdit> formatting(URI uri, CustomFormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting")
            return Collections.emptyList()
        }

        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return Collections.emptyList()

        final sourceUnit = ast.getSourceUnit(uri)
        final oldText = sourceUnit.getSource().getReader().getText()
        final range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.size()))
        final visitor = new FormattingVisitor(sourceUnit, options)
        visitor.visit()
        final newText = visitor.toString()

        return List.of( new TextEdit(range, newText) )
    }

}

@CompileStatic
class FormattingVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit

    private CustomFormattingOptions options

    private Formatter fmt

    FormattingVisitor(SourceUnit sourceUnit, CustomFormattingOptions options) {
        this.sourceUnit = sourceUnit
        this.options = options
        this.fmt = new Formatter(options)
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ConfigNode )
            return
        super.visit((ConfigNode) moduleNode)
    }

    String toString() {
        return fmt.toString()
    }

    // config statements

    @Override
    void visitConfigAssign(ConfigAssignNode node) {
        fmt.appendComments(node)
        fmt.appendIndent()
        final name = node.names.join('.')
        fmt.append(name)
        if( currentAlignmentWidth > 0 ) {
            final padding = currentAlignmentWidth - name.length()
            fmt.append(' ' * padding)
        }
        fmt.append(node instanceof ConfigAppendNode ? ' ' : ' = ')
        fmt.visit(node.value)
        fmt.appendNewLine()
    }

    private static final Pattern IDENTIFIER = ~/[a-zA-Z_]+[a-zA-Z0-9_]*/

    private int currentAlignmentWidth = 0

    @Override
    void visitConfigBlock(ConfigBlockNode node) {
        fmt.appendComments(node)
        fmt.appendIndent()
        if( node.kind != null ) {
            fmt.append(node.kind)
            fmt.append(': ')
        }
        final name = node.name
        if( IDENTIFIER.matcher(name).matches() ) {
            fmt.append(name)
        }
        else {
            fmt.append("'")
            fmt.append(name)
            fmt.append("'")
        }
        fmt.append(' {')
        fmt.appendNewLine()

        int caw
        if( options.harshilAlignment() ) {
            int maxWidth = 0
            for( final stmt : node.statements ) {
                if( stmt !instanceof ConfigAssignNode )
                    continue
                final width = ((ConfigAssignNode) stmt).names.join('.').length()
                if( maxWidth < width )
                    maxWidth = width
            }
            caw = currentAlignmentWidth
            currentAlignmentWidth = maxWidth
        }

        fmt.incIndent()
        super.visitConfigBlock(node)
        fmt.decIndent()

        if( options.harshilAlignment() )
            currentAlignmentWidth = caw

        fmt.appendIndent()
        fmt.append('}')
        fmt.appendNewLine()
    }

    @Override
    void visitConfigInclude(ConfigIncludeNode node) {
        fmt.appendComments(node)
        fmt.appendIndent()
        fmt.append('includeConfig ')
        fmt.visit(node.source)
        fmt.appendNewLine()
    }

}
