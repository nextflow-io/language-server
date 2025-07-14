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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nextflow.lsp.ast.CompletionHelper;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.InvalidDeclaration;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Description;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import static nextflow.lsp.ast.CompletionUtils.*;

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Jordi Deu-Pons <jordi@jordeu.net>
 */
public class ScriptCompletionProvider implements CompletionProvider {

    private static final List<CompletionItem> DECLARATION_SNIPPETS = scriptDeclarationSnippets();

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;
    private boolean extended;
    private URI uri;
    private CompletionHelper ch;

    public ScriptCompletionProvider(ScriptAstCache ast, int maxItems, boolean extended) {
        this.ast = ast;
        this.extended = extended;
        this.ch = new CompletionHelper(maxItems);
    }

    @Override
    public Either<List<CompletionItem>, CompletionList> completion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing completions");
            return Either.forLeft(Collections.emptyList());
        }

        this.uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Either.forLeft(Collections.emptyList());

        var nodeStack = ast.getNodesAtPosition(uri, position);
        if( nodeStack.isEmpty() )
            return Either.forLeft(DECLARATION_SNIPPETS);

        var offsetNode = nodeStack.get(0);
        var declarationNode = nodeStack.get(nodeStack.size() - 1);

        if( offsetNode instanceof VariableExpression ve ) {
            // e.g. "foo "
            //          ^
            var namePrefix = ve.getName();
            log.debug("completion variable -- '" + namePrefix + "'");
            addItemsFromScope(getVariableScope(nodeStack), namePrefix, declarationNode);
        }
        else if( offsetNode instanceof MethodCallExpression mce ) {
            var namePrefix = mce.getMethodAsString();
            log.debug("completion method call -- '" + namePrefix + "'");
            if( mce.isImplicitThis() ) {
                // e.g. "foo ()"
                //          ^
                addItemsFromScope(getVariableScope(nodeStack), namePrefix, declarationNode);
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
        else if( offsetNode instanceof InvalidDeclaration ) {
            return Either.forLeft(Collections.emptyList());
        }
        else {
            log.debug("completion " + offsetNode.getClass().getSimpleName() + " -- '" + offsetNode.getText() + "'");
            addItemsFromScope(getVariableScope(nodeStack), "", declarationNode);
        }

        return ch.isIncomplete()
            ? Either.forRight(new CompletionList(true, ch.getItems()))
            : Either.forLeft(ch.getItems());
    }

    private void addItemsFromScope(VariableScope scope, String namePrefix, ASTNode declarationNode) {
        ch.addItemsFromScope(scope, namePrefix);
        ch.addTypes(ast.getTypeNodes(uri), namePrefix);

        if( !extended ) {
            addIncludes(namePrefix);
            return;
        }
        if( declarationNode instanceof FunctionNode || declarationNode instanceof ProcessNode || declarationNode instanceof OutputNode ) {
            addExternalFunctions(namePrefix);
        }
        if( declarationNode instanceof WorkflowNode ) {
            addExternalFunctions(namePrefix);
            addExternalProcesses(namePrefix);
            addExternalWorkflows(namePrefix);
        }
    }

    private void addIncludes(String namePrefix) {
        for( var includeNode : ast.getIncludeNodes(uri) ) {
            for( var entry : includeNode.entries ) {
                var node = entry.getTarget();
                if( !(node instanceof MethodNode) || ast.getURI(node) == null )
                    continue;

                var name = entry.getNameOrAlias();
                if( !name.startsWith(namePrefix) )
                    continue;

                var labelDetails = new CompletionItemLabelDetails();
                labelDetails.setDescription(getIncludeSource(node));

                var item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Function);
                item.setLabelDetails(labelDetails);
                item.setDetail(astNodeToItemDetail(node));
                item.setDocumentation(astNodeToItemDocumentation(node));

                if( !ch.addItem(item) )
                    break;
            }
        }
    }

    private void addExternalFunctions(String namePrefix) {
        var localNodes = ast.getFunctionNodes(uri);
        var allNodes = ast.getFunctionNodes();
        addExternalMethods(namePrefix, localNodes, allNodes);
    }

    private void addExternalProcesses(String namePrefix) {
        var localNodes = ast.getProcessNodes(uri);
        var allNodes = ast.getProcessNodes();
        addExternalMethods(namePrefix, localNodes, allNodes);
    }

    private void addExternalWorkflows(String namePrefix) {
        var localNodes = ast.getWorkflowNodes(uri);
        var allNodes = ast.getWorkflowNodes();
        addExternalMethods(namePrefix, localNodes, allNodes);
    }

    private void addExternalMethods(String namePrefix, List<? extends MethodNode> localNodes, List<? extends MethodNode> allNodes) {
        var addIncludeRange = getAddIncludeRange();

        for( var node : allNodes ) {
            var name = node.getName();
            if( name == null || !name.startsWith(namePrefix) )
                continue;
            if( localNodes.contains(node) )
                continue;

            var labelDetails = new CompletionItemLabelDetails();
            labelDetails.setDescription(getIncludeSource(node));

            var item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Function);
            item.setLabelDetails(labelDetails);
            item.setDetail(astNodeToItemDetail(node));
            item.setDocumentation(astNodeToItemDocumentation(node));

            if( !isIncluded(node) ) {
                var textEdit = createAddIncludeTextEdit(addIncludeRange, name, node);
                item.setAdditionalTextEdits( List.of(textEdit) );
            }

            if( !ch.addItem(item) )
                break;
        }
    }

    private boolean isIncluded(MethodNode node) {
        for( var includeNode : ast.getIncludeNodes(uri) ) {
            for( var entry : includeNode.entries ) {
                if( entry.getTarget() == node )
                    return true;
            }
        }
        return false;
    }

    private Range getAddIncludeRange() {
        var includeNodes = ast.getIncludeNodes(uri);
        if( includeNodes.isEmpty() ) {
            var line = ast.getScriptNode(uri).getShebang() != null ? 1 : 0;
            return new Range(new Position(line, 0), new Position(line, 0));
        }
        var lastInclude = includeNodes.get(includeNodes.size() - 1);
        var lastIncludeRange = LanguageServerUtils.astNodeToRange(lastInclude);
        var includeLine = lastIncludeRange != null ? lastIncludeRange.getEnd().getLine() + 1 : 0;
        return new Range(new Position(includeLine, 0), new Position(includeLine, 0));
    }

    private TextEdit createAddIncludeTextEdit(Range range, String name, ASTNode node) {
        var newText = new StringBuilder()
            .append("include { ")
            .append(name)
            .append(" } from '")
            .append(getIncludeSource(node))
            .append("'\n")
            .toString();
        return new TextEdit(range, newText);
    }

    private String getIncludeSource(ASTNode node) {
        var source = Path.of(uri).getParent().relativize(Path.of(ast.getURI(node))).toString();
        return source.startsWith(".")
            ? source
            : "./" + source;
    }

    private static List<CompletionItem> scriptDeclarationSnippets() {
        var snippets = new ArrayList<Snippet>();

        snippets.add(new Snippet(
            "shebang",
            """
            Shebang declaration:

            ```sh
            #!/usr/bin/env nextflow
            ```
            """,
            "#!/usr/bin/env nextflow\n\n"
        ));
        snippets.add(new Snippet(
            "include",
            """
            Include declaration:

            ```nextflow
            include { HELLO } from './hello.nf'
            ```

            [Read more](https://nextflow.io/docs/latest/module.html#module-inclusion)
            """,
            "include { $1 } from './$2'"
        ));
        snippets.add(new Snippet(
            "def",
            """
            Function definition:

            ```nextflow
            def greet(greeting, name) {
                println "${greeting}, ${name}!"
            }
            ```

            [Read more](https://nextflow.io/docs/latest/script.html#functions)
            """,
            """
            def $1($2) {
                $3
            }
            """
        ));
        snippets.add(new Snippet(
            "process",
            """
            Process definition:

            ```nextflow
            process HELLO {
                input:
                val message

                output:
                stdout

                script:
                \"\"\"
                echo '$message world!'
                \"\"\"
            }
            ```

            [Read more](https://nextflow.io/docs/latest/process.html)
            """,
            """
            process $1 {
                input:
                ${2|val,path,env,stdin,tuple|} ${3:identifier}

                output:
                ${4|val,path,env,stdout,tuple|} ${5:identifier}

                script:
                \"\"\"
                $6
                \"\"\"

                stub:
                \"\"\"
                $7
                \"\"\"
            }
            """
        ));
        snippets.add(new Snippet(
            "workflow",
            """
            Workflow definition:

            ```nextflow
            workflow MY_FLOW {
                take:
                input

                main:
                input | view | set { output }

                emit:
                output
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html)
            """,
            """
            workflow $1 {
                take:
                $2

                main:
                $3

                emit:
                $4
            }
            """
        ));
        snippets.add(new Snippet(
            "workflow <entry>",
            """
            Entry workflow definition:

            ```nextflow
            workflow {
                Channel.of('Bonjour', 'Ciao', 'Hello', 'Hola') | view { v -> "$v world!" }
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html)
            """,
            """
            workflow {
                $1
            }
            """
        ));
        snippets.add(new Snippet(
            "output",
            """
            Output block for configuring publish targets:

            ```nextflow
            output {
                'fastq' {
                    path 'samples'
                    index {
                        path 'index.csv'
                    }
                }
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html#workflow-output-def)
            """,
            """
            output {
                $1
            }
            """
        ));

        for( var field : FeatureFlagDsl.class.getDeclaredFields() ) {
            var name = field.getAnnotation(FeatureFlag.class);
            var description = field.getAnnotation(Description.class);
            if( name == null || description == null )
                continue;
            snippets.add(new Snippet(
                name.value(),
                description.value(),
                name.value() + " = "
            ));
        }

        var result = new ArrayList<CompletionItem>();
        for( var snippet : snippets ) {
            var item = new CompletionItem(snippet.name());
            item.setKind(CompletionItemKind.Snippet);
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(snippet.documentation(), true).trim()));
            item.setInsertText(StringGroovyMethods.stripIndent(snippet.insertText(), true).trim());
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setInsertTextMode(InsertTextMode.AdjustIndentation);
            result.add(item);
        }
        return result;
    }

    private static record Snippet(
        String name,
        String documentation,
        String insertText
    ) {}

}
