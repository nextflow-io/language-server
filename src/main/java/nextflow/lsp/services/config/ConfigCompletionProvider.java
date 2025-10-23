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
import nextflow.config.dsl.ConfigDsl;
import nextflow.config.spec.SpecNode;
import nextflow.lsp.ast.CompletionHelper;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.util.Logger;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
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

import static nextflow.lsp.ast.CompletionUtils.*;

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigCompletionProvider implements CompletionProvider {

    private static Logger log = Logger.getInstance();

    private ConfigAstCache ast;
    private CompletionHelper ch;

    public ConfigCompletionProvider(ConfigAstCache ast, int maxItems) {
        this.ast = ast;
        this.ch = new CompletionHelper(maxItems);
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

        var nodeStack = ast.getNodesAtPosition(uri, position);
        var spec = ast.getConfigNode(uri).getSpec();
        if( nodeStack.isEmpty() )
            return Either.forLeft(topLevelItems(spec));

        if( isConfigExpression(nodeStack) ) {
            addCompletionItems(nodeStack);
        }
        else {
            var names = currentConfigScope(nodeStack);
            if( names.isEmpty() )
                return Either.forLeft(topLevelItems(spec));
            addConfigOptions(names, spec);
        }

        return ch.isIncomplete()
            ? Either.forRight(new CompletionList(true, ch.getItems()))
            : Either.forLeft(ch.getItems());
    }

    private boolean isConfigExpression(List<ASTNode> nodeStack) {
        for( var node : DefaultGroovyMethods.asReversed(nodeStack) ) {
            if( node instanceof Expression )
                return true;
        }
        return false;
    }

    private void addCompletionItems(List<ASTNode> nodeStack) {
        var offsetNode = nodeStack.get(0);

        if( offsetNode instanceof VariableExpression ve ) {
            // e.g. "foo "
            //          ^
            var namePrefix = ve.getName();
            log.debug("completion variable -- '" + namePrefix + "'");
            ch.addItemsFromScope(variableScope(nodeStack), namePrefix);
        }
        else if( offsetNode instanceof MethodCallExpression mce ) {
            var namePrefix = mce.getMethodAsString();
            log.debug("completion method call -- '" + namePrefix + "'");
            if( mce.isImplicitThis() ) {
                // e.g. "foo ()"
                //          ^
                ch.addItemsFromScope(variableScope(nodeStack), namePrefix);
            }
            else {
                // e.g. "foo.bar ()"
                //              ^
                ch.addMethodsFromObjectScope(mce.getObjectExpression(), namePrefix);
            }
        }
        else if( offsetNode instanceof PropertyExpression pe ) {
            // e.g. "foo.bar "
            //              ^
            var namePrefix = pe.getPropertyAsString();
            log.debug("completion property -- '" + namePrefix + "'");
            ch.addItemsFromObjectScope(pe.getObjectExpression(), namePrefix);
        }
        else if( offsetNode instanceof ConstructorCallExpression cce ) {
            // e.g. "new Foo ()"
            //              ^
            var namePrefix = cce.getType().getNameWithoutPackage();
            log.debug("completion constructor call -- '" + namePrefix + "'");
        }
        else {
            log.debug("completion " + offsetNode.getClass().getSimpleName() + " -- '" + offsetNode.getText() + "'");
            ch.addItemsFromScope(variableScope(nodeStack), "");
        }
    }

    private static List<String> currentConfigScope(List<ASTNode> nodeStack) {
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

    private void addConfigOptions(List<String> names, SpecNode.Scope spec) {
        var scope = spec.getScope(names);
        if( scope == null )
            return;
        scope.children().forEach((name, child) -> {
            if( child instanceof SpecNode.Option option )
                ch.addItem(configOption(name, option.description(), option.type()));
            else
                ch.addItem(configScope(name, child.description()));
        });
    }

    private static List<CompletionItem> topLevelItems(SpecNode.Scope spec) {
        var result = new ArrayList<CompletionItem>();
        spec.children().forEach((name, child) -> {
            if( child instanceof SpecNode.Option option ) {
                result.add(configOption(name, option.description(), option.type()));
            }
            else {
                result.add(configScope(name, child.description()));
                result.add(configScopeBlock(name, child.description()));
            }
        });
        return result;
    }

    private static CompletionItem configScope(String name, String description) {
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(description, true).trim()));
        return item;
    }

    private static CompletionItem configScopeBlock(String name, String description) {
        var insertText = String.format(
            """
            %s {
                $1
            }
            """, name);

        var item = new CompletionItem(name + " {");
        item.setKind(CompletionItemKind.Property);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(description, true).trim()));
        item.setInsertText(StringGroovyMethods.stripIndent(insertText, true).trim());
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertTextMode(InsertTextMode.AdjustIndentation);
        return item;
    }

    private static CompletionItem configOption(String name, String description, Class type) {
        var documentation = StringGroovyMethods.stripIndent(description, true).trim();
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Property);
        item.setDetail(String.format("%s: %s", name, Types.getName(type)));
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation));
        item.setInsertText(String.format("%s = $1", name));
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertTextMode(InsertTextMode.AdjustIndentation);
        return item;
    }

    private static VariableScope variableScope(List<ASTNode> nodeStack) {
        var scope = getVariableScope(nodeStack);
        if( scope != null )
            return scope;
        scope = new VariableScope();
        scope.setClassScope(new ClassNode(ConfigDsl.class));
        return scope;
    }

}
