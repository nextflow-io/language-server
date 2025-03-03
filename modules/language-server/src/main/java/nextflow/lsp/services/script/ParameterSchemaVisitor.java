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
package nextflow.lsp.services.script;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import groovy.json.JsonSlurper;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.dsl.Description;
import nextflow.script.types.ParamsMap;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * Validate params based on the JSON schema.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ParameterSchemaVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ClassNode paramsType;

    public ParameterSchemaVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn ) {
            if( sn.getEntry() != null )
                visitWorkflow(sn.getEntry());
        }
    }

    @Override
    public void visitWorkflow(WorkflowNode node) {
        try {
            declareParameters();
        }
        catch( Exception e ) {
            System.err.println("Failed to parse parameter schema (nextflow_schema.json): " + e.toString());
            addError("Failed to parse parameter schema -- check nextflow_schema.json for possible errors", node);
            return;
        }
        super.visitWorkflow(node);
    }

    private void declareParameters() {
        // load parameter schema
        var uri = sourceUnit.getSource().getURI();
        var schemaPath = Path.of(uri).getParent().resolve("nextflow_schema.json");
        if( !Files.exists(schemaPath) )
            return;
        var schemaJson = getParameterSchema(schemaPath);

        var schema = asMap(schemaJson).orElse(Collections.emptyMap());
        var defs = Optional.of(schema)
            .flatMap(json ->
                json.containsKey("$defs")
                    ? asMap(json.get("$defs")) :
                json.containsKey("defs")
                    ? asMap(json.get("defs")) :
                json.containsKey("definitions")
                    ? asMap(json.get("definitions"))
                    : Optional.empty()
            )
            .orElse(Collections.emptyMap());

        var entries = (List<Map.Entry>) Stream.concat(Stream.of(schema), defs.values().stream())
            .flatMap(defn ->
                defn instanceof Map map
                    ? Stream.of(map.get("properties"))
                    : Stream.empty()
            )
            .flatMap(props ->
                props instanceof Map map
                    ? map.entrySet().stream()
                    : Stream.empty()
            )
            .toList();

        if( entries.isEmpty() )
            return;

        // create synthetic params type
        var cn = new ClassNode(ParamsMap.class);

        for( var entry : entries ) {
            var name = (String) entry.getKey();
            var attrs = asMap(entry.getValue()).orElse(null);
            if( attrs == null )
                continue;
            var type = getTypeClassFromString(asString(attrs.get("type")));
            var description = asString(attrs.get("description"));
            var fn = new FieldNode(name, Modifier.PUBLIC, type, cn, null);
            fn.setHasNoRealSourcePosition(true);
            fn.setDeclaringClass(cn);
            fn.setSynthetic(true);
            var an = new AnnotationNode(ClassHelper.makeCached(Description.class));
            an.addMember("value", new ConstantExpression(description));
            fn.addAnnotation(an);
            cn.addField(fn);
        }

        this.paramsType = cn;
    }

    private Object getParameterSchema(Path schemaPath) {
        try {
            var schemaText = IOGroovyMethods.getText(Files.newInputStream(schemaPath));
            return new JsonSlurper().parseText(schemaText);
        }
        catch( IOException e ) {
            System.err.println("Failed to read parameter schema: " + e.toString());
            return null;
        }
    }

    private static Optional<Map> asMap(Object value) {
        return value instanceof Map
            ? Optional.of((Map) value)
            : Optional.empty();
    }

    private static String asString(Object value) {
        return value instanceof String
            ? (String) value
            : null;
    }

    private ClassNode getTypeClassFromString(String type) {
        if( "boolean".equals(type) )
            return ClassHelper.boolean_TYPE;
        if( "integer".equals(type) )
            return ClassHelper.long_TYPE;
        if( "number".equals(type) )
            return ClassHelper.double_TYPE;
        if( "string".equals(type) )
            return ClassHelper.STRING_TYPE;
        return ClassHelper.dynamicType();
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        super.visitPropertyExpression(node);

        // validate parameter against schema if applicable
        // NOTE: should be incorporated into type-checking visitor
        if( paramsType == null )
            return;
        if( !(node.getObjectExpression() instanceof VariableExpression) )
            return;
        var varX = (VariableExpression) node.getObjectExpression();
        if( !"params".equals(varX.getName()) )
            return;
        var property = node.getPropertyAsString();
        if( paramsType.getDeclaredField(property) == null ) {
            addError("Unrecognized parameter `" + property + "`", node);
            return;
        }
        var variable = varX.getAccessedVariable();
        if( variable instanceof PropertyNode pn ) {
            var mn = (MethodNode) pn.getNodeMetaData("access.method");
            mn.setReturnType(paramsType);
        }
    }

    // helpers

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new ParameterSchemaError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class ParameterSchemaError extends SyntaxException implements PhaseAware {

        public ParameterSchemaError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.NAME_RESOLUTION;
        }
    }

}
