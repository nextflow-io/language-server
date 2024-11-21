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
package nextflow.lsp.services.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.dsl.ConfigSchema;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.HoverProvider;
import nextflow.lsp.util.Logger;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Provide hints for an expression or statement when hovered
 * based on available definitions.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigHoverProvider implements HoverProvider {

    private static Logger log = Logger.getInstance();

    private ASTNodeCache ast;

    public ConfigHoverProvider(ASTNodeCache ast) {
        this.ast = ast;
    }

    @Override
    public Hover hover(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        var nodeStack = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( nodeStack.isEmpty() )
            return null;

        var content = getHoverContent(nodeStack);

        var builder = new StringBuilder();

        if( content != null ) {
            builder.append(content);
            builder.append('\n');
        }

        if( Logger.isDebugEnabled() ) {
            builder.append("\n\n---\n\n");
            builder.append("```\n");
            for( int i = 0; i < nodeStack.size(); i++ ) {
                var node = nodeStack.get(nodeStack.size() - 1 - i);
                builder.append("  ".repeat(i));
                builder.append(node.getClass().getSimpleName());
                builder.append(String.format("(%d:%d-%d:%d)", node.getLineNumber(), node.getColumnNumber(), node.getLastLineNumber(), node.getLastColumnNumber() - 1));
                if( node instanceof ConfigBlockNode block ) {
                    var scope = ConfigSchema.SCOPES.get(block.name);
                    if( scope != null ) {
                        builder.append(" [");
                        builder.append(scope.getClass().getSimpleName());
                        builder.append(']');
                    }
                }
                builder.append('\n');
            }
            builder.append("\n```");
        }

        var value = builder.toString();
        if( value.isEmpty() )
            return null;
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value));
    }

    protected String getHoverContent(List<ASTNode> nodeStack) {
        var offsetNode = nodeStack.get(0);
        if( offsetNode instanceof ConfigAssignNode assign ) {
            var names = getCurrentScope(nodeStack);
            names.addAll(assign.names);

            var fqName = String.join(".", names);
            var option = ConfigSchema.OPTIONS.get(fqName);
            if( option != null ) {
                var description = StringGroovyMethods.stripIndent(option, true).trim();
                var builder = new StringBuilder();
                builder.append("`" + fqName + "`");
                builder.append("\n\n");
                builder.append(description);
                return builder.toString();
            }
            else if( fqName != null && Logger.isDebugEnabled() ) {
                return "`" + fqName + "`";
            }
        }

        if( offsetNode instanceof ConfigBlockNode block ) {
            var names = getCurrentScope(nodeStack);
            if( names.isEmpty() )
                return null;

            var fqName = String.join(".", names);
            var scope = ConfigSchema.SCOPES.get(fqName);
            if( scope != null ) {
                return StringGroovyMethods.stripIndent(scope.description(), true).trim();
            }
            else if( fqName != null && Logger.isDebugEnabled() ) {
                return "`" + fqName + "`";
            }
        }

        return null;
    }

    protected List<String> getCurrentScope(List<ASTNode> nodeStack) {
        var names = new ArrayList<String>();
        for( var node : DefaultGroovyMethods.asReversed(nodeStack) ) {
            if( node instanceof ConfigBlockNode block && block.kind == null )
                names.add(block.name);
        }
        if( names.size() >= 2 && "profiles".equals(names.get(0)) ) {
            names.remove(0);
            names.remove(0);
        }
        return names;
    }

}
