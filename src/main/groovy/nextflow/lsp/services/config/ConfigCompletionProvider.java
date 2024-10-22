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
import java.util.Collections;
import java.util.List;

import nextflow.config.v2.ConfigAssignNode;
import nextflow.config.v2.ConfigBlockNode;
import nextflow.config.v2.ConfigIncompleteNode;
import nextflow.config.dsl.ConfigSchema;
import nextflow.config.dsl.ConfigScope;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.util.Logger;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigCompletionProvider implements CompletionProvider {

    private static final List<CompletionItem> TOPLEVEL_ITEMS = getTopLevelItems();

    private static Logger log = Logger.getInstance();

    private ASTNodeCache ast;

    public ConfigCompletionProvider(ASTNodeCache ast) {
        this.ast = ast;
    }

    @Override
    public Either<List<CompletionItem>, CompletionList> completion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing completions");
            return Either.forLeft(Collections.emptyList());
        }

        var uri = URI.create(textDocument.getUri());
        var nodeStack = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( nodeStack.isEmpty() )
            return Either.forLeft(TOPLEVEL_ITEMS);

        var scope = getCurrentScope(nodeStack);
        if( scope == null )
            return Either.forLeft(Collections.emptyList());

        var items = scope.isEmpty()
            ? TOPLEVEL_ITEMS
            : getConfigOptions(scope + ".");
        return Either.forLeft(items);
    }

    private String getCurrentScope(List<ASTNode> nodeStack) {
        var names = new ArrayList<String>();
        for( var node : DefaultGroovyMethods.asReversed(nodeStack) ) {
            if( node instanceof Expression )
                return null;
            if( node instanceof ConfigBlockNode block && block.kind == null )
                names.add(block.name);
        }
        if( names.size() >= 2 && "profiles".equals(names.get(0)) ) {
            names.remove(0);
            names.remove(0);
        }
        var offsetNode = nodeStack.get(0);
        if( offsetNode instanceof ConfigAssignNode assign ) {
            names.addAll(DefaultGroovyMethods.init(assign.names));
        }
        if( offsetNode instanceof ConfigIncompleteNode cin ) {
            names.addAll(StringGroovyMethods.tokenize(cin.text, "."));
            if( !cin.text.endsWith(".") )
                names.remove(names.size() - 1);
        }
        return String.join(".", names);
    }

    private List<CompletionItem> getConfigOptions(String prefix) {
        var result = new ArrayList<CompletionItem>();
        ConfigSchema.OPTIONS.forEach((name, documentation) -> {
            if( !name.startsWith(prefix) )
                return;
            var relativeName = name.replace(prefix, "");
            result.add(getConfigOption(relativeName, documentation));
        });
        return result;
    }

    private static List<CompletionItem> getTopLevelItems() {
        var result = new ArrayList<CompletionItem>();
        ConfigSchema.SCOPES.forEach((name, scope) -> {
            if( name.isEmpty() )
                return;
            if( !"profiles".equals(name) )
                result.add(getConfigScopeDot(name, scope));
            if( !name.contains(".") )
                result.add(getConfigScopeBlock(name, scope));
        });
        ConfigSchema.OPTIONS.forEach((name, documentation) -> {
            if( name.contains(".") )
                return;
            result.add(getConfigOption(name, documentation));
        });
        return result;
    }

    private static CompletionItem getConfigScopeDot(String name, ConfigScope scope) {
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(scope.description(), true).trim()));
        return item;
    }

    private static CompletionItem getConfigScopeBlock(String name, ConfigScope scope) {
        var insertText = String.format(
            """
            %s {
                $1
            }
            """, name);

        var item = new CompletionItem(name + " {");
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(scope.description(), true).trim()));
        item.setInsertText(StringGroovyMethods.stripIndent(insertText, true).trim());
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertTextMode(InsertTextMode.AdjustIndentation);
        return item;
    }

    private static CompletionItem getConfigOption(String name, String documentation) {
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(documentation, true).trim()));
        item.setInsertText(String.format("%s = $1", name));
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertTextMode(InsertTextMode.AdjustIndentation);
        return item;
    }

}
