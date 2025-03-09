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
package nextflow.lsp.services.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.ast.ConfigIncompleteNode;
import nextflow.config.dsl.ConfigSchema;
import nextflow.config.dsl.ConfigScope;
import nextflow.config.dsl.OptionNode;
import nextflow.config.dsl.ScopeNode;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.util.Logger;
import nextflow.script.types.Types;
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
        if( !ast.hasAST(uri) )
            return Either.forLeft(Collections.emptyList());

        var nodeStack = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( nodeStack.isEmpty() )
            return Either.forLeft(TOPLEVEL_ITEMS);

        var names = getCurrentScope(nodeStack);
        if( names == null )
            return Either.forLeft(Collections.emptyList());

        var items = names.isEmpty()
            ? TOPLEVEL_ITEMS
            : getConfigOptions(names);
        return Either.forLeft(items);
    }

    private List<String> getCurrentScope(List<ASTNode> nodeStack) {
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
        return names;
    }

    private List<CompletionItem> getConfigOptions(List<String> names) {
        var scope = ConfigSchema.ROOT.getScope(names);
        if( scope instanceof ScopeNode sn ) {
            var result = new ArrayList<CompletionItem>(sn.options().size() + sn.scopes().size());
            sn.options().forEach((name, option) -> {
                result.add(getConfigOption(name, option));
            });
            sn.scopes().forEach((name, scope1) -> {
                if( scope1 instanceof ScopeNode sn1 )
                    result.add(getConfigScopeDot(name, sn1));
            });
            return result;
        }
        return Collections.emptyList();
    }

    private static List<CompletionItem> getTopLevelItems() {
        var result = new ArrayList<CompletionItem>();
        ConfigSchema.ROOT.scopes().forEach((name, scope) -> {
            if( scope instanceof ScopeNode sn ) {
                if( !"profiles".equals(name) )
                    result.add(getConfigScopeDot(name, sn));
                result.add(getConfigScopeBlock(name, sn));
            }
        });
        ConfigSchema.ROOT.options().forEach((name, description) -> {
            result.add(getConfigOption(name, description));
        });
        return result;
    }

    private static CompletionItem getConfigScopeDot(String name, ScopeNode scope) {
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(scope.description(), true).trim()));
        return item;
    }

    private static CompletionItem getConfigScopeBlock(String name, ScopeNode scope) {
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

    private static CompletionItem getConfigOption(String name, OptionNode option) {
        var documentation = StringGroovyMethods.stripIndent(option.description(), true).trim();
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDetail(String.format("%s: %s", name, Types.getName(option.type())));
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation));
        item.setInsertText(String.format("%s = $1", name));
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertTextMode(InsertTextMode.AdjustIndentation);
        return item;
    }

}
