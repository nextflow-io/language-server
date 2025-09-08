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
import java.util.Map;

import nextflow.lsp.services.CodeLensProvider;
import nextflow.lsp.services.script.dag.DataflowVisitor;
import nextflow.lsp.services.script.dag.MermaidRenderer;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.ParamBlockNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ScriptNode;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;
import nextflow.script.formatter.FormattingOptions;
import nextflow.script.formatter.ScriptFormattingVisitor;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import static nextflow.lsp.util.JsonUtils.asJson;
import static nextflow.script.ast.ASTUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptCodeLensProvider implements CodeLensProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptCodeLensProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<CodeLens> codeLens(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing code lens");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Collections.emptyList();

        var result = new ArrayList<CodeLens>();

        for( var wn : ast.getWorkflowNodes(uri) ) {
            var range = LanguageServerUtils.astNodeToRange(wn);
            if( range == null )
                continue;
            var arguments = List.of(asJson(uri.toString()), asJson(wn.getName()));
            var command = new Command("Preview DAG", "nextflow.previewDag", arguments);
            result.add(new CodeLens(range, command, null));
        }

        for( var pn : ast.getProcessNodes(uri) ) {
            if( !(pn instanceof ProcessNodeV1) )
                continue;
            var range = LanguageServerUtils.astNodeToRange(pn);
            var arguments = List.of(asJson(uri.toString()), asJson(pn.getName()));
            var command = new Command("Convert to static types", "nextflow.convertProcessToTyped", arguments);
            result.add(new CodeLens(range, command, null));
        }

        return result;
    }

    public Map<String,String> previewDag(String documentUri, String name, String direction, boolean verbose) {
        var uri = URI.create(documentUri);
        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return Map.of("error", "DAG preview cannot be shown because the script has errors.");

        var sourceUnit = ast.getSourceUnit(uri);
        return ast.getWorkflowNodes(uri).stream()
            .filter(wn -> wn.isEntry() ? name == null : wn.getName().equals(name))
            .findFirst()
            .map((wn) -> {
                var visitor = new DataflowVisitor(sourceUnit, ast, verbose);
                visitor.visit();

                var graph = visitor.getGraph(wn.isEntry() ? "<entry>" : wn.getName());
                var result = new MermaidRenderer(direction, verbose).render(wn.getName(), graph);
                log.debug(result);
                return Map.of("result", result);
            })
            .orElse(null);
    }

    public Map<String,Object> convertPipelineToTyped(FormattingOptions options) {
        for( var uri : ast.getUris() ) {
            if( !ast.hasAST(uri) || ast.hasErrors(uri) )
                return Map.of("error", "Pipeline cannot be converted due to script errors.");
        }

        var textEdits = new HashMap<String,List<TextEdit>>();

        // convert legacy parameters to params definition
        for( var sn : ast.getScriptNodes() ) {
            convertParamsToTyped(sn, options, textEdits);
        }

        // convert legacy processes to static types
        for( var uri : ast.getUris() ) {
            for( var pn : ast.getProcessNodes(uri) ) {
                if( !(pn instanceof ProcessNodeV1) )
                    continue;
                convertProcessToTyped((ProcessNodeV1) pn, options, textEdits);
            }
        }
            
        return Map.of("applyEdit", (Object) new WorkspaceEdit(textEdits));
    }

    public void convertParamsToTyped(ScriptNode sn, FormattingOptions options, Map<String,List<TextEdit>> textEdits) {
        // construct params block from schema, legacy parameters
        var entry = sn.getEntry();
        if( entry == null || sn.getParams() != null )
            return;
        var classScope = entry.getVariableScope().getClassScope();
        var type = classScope.getMethods().stream()
            .filter((mn) -> {
                var an = findAnnotation(mn, Constant.class);
                return an.isPresent() && "params".equals(an.get().getMember("value").getText());
            })
            .map(mn -> mn.getReturnType())
            .findFirst().orElse(null);
        if( type == null )
            return;
        var legacyDefaults = Map.ofEntries(
            sn.getParamsV1().stream()
                .map((param) -> {
                    var name = param.target instanceof PropertyExpression pe ? pe.getPropertyAsString() : "";
                    return Map.entry(name, param.value);
                })
                .toArray(Map.Entry[]::new)
        );
        var declarations = type.getFields().stream()
            .map((fn) -> {
                var name = fn.getName();
                var defaultValue = legacyDefaults.getOrDefault(name, fn.getInitialExpression());
                var param = new Parameter(fn.getType(), name, (Expression) defaultValue);
                var comments = findAnnotation(fn, Description.class)
                    .map(an -> an.getMember("value").getText())
                    .map(description -> List.of("// " + description + "\n", "\n"))
                    .orElse(List.of("\n"));
                param.putNodeMetaData(ASTNodeMarker.LEADING_COMMENTS, comments);
                return param;
            })
            .toArray(Parameter[]::new);
        if( declarations.length == 0 )
            return;
        var newParams = new ParamBlockNode(declarations);

        // insert params block before entry workflow
        var sourceUnit = sn.getContext();
        var uri = sourceUnit.getSource().getURI();
        var edits = textEdits.computeIfAbsent(uri.toString(), (k) -> new ArrayList<>());

        var entryStart = LanguageServerUtils.astNodeToRange(entry).getStart();
        var range = new Range(entryStart, entryStart);
        var formatter = new ScriptFormattingVisitor(sourceUnit, options);
        formatter.visitParams(newParams);
        var newText = formatter.toString() + "\n";
        edits.add(new TextEdit(range, newText));

        // delete legacy parameter declarations
        for( var param : sn.getParamsV1() ) {
            var deletion = new TextEdit(LanguageServerUtils.astNodeToRange(param), "");
            edits.add(deletion);
        }
    }

    public Map<String,Object> convertProcessToTyped(String documentUri, String name, FormattingOptions options) {
        var uri = URI.create(documentUri);
        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return Map.of("error", "Legacy process cannot be converted because the script has errors.");

        return ast.getProcessNodes(uri).stream()
            .filter(pn -> pn instanceof ProcessNodeV1 && pn.getName().equals(name))
            .findFirst()
            .map((pn) -> {
                var textEdits = new HashMap<String,List<TextEdit>>();
                convertProcessToTyped((ProcessNodeV1) pn, options, textEdits);
                return Map.of("applyEdit", (Object) new WorkspaceEdit(textEdits));
            })
            .orElse(null);
    }

    private void convertProcessToTyped(ProcessNodeV1 pn, FormattingOptions options, Map<String,List<TextEdit>> textEdits) {
        var uri = ast.getURI(pn);
        var sourceUnit = ast.getSourceUnit(uri);
        var range = LanguageServerUtils.astNodeToRange(pn);
        var newPn = new ProcessConverter(uri).apply(pn);
        var formatter = new ScriptFormattingVisitor(sourceUnit, options);
        formatter.visitProcess(newPn);
        var newText = formatter.toString().trim();
        textEdits
            .computeIfAbsent(uri.toString(), (k) -> new ArrayList<>())
            .add(new TextEdit(range, newText));
    }

}
