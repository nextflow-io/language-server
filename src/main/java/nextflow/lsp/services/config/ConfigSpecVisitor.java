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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.stream.Collectors;

import nextflow.config.ast.ConfigApplyBlockNode;
import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.ast.ConfigNode;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.config.spec.SpecNode;
import nextflow.lsp.spec.ConfigSpecFactory;
import nextflow.lsp.spec.PluginRef;
import nextflow.lsp.spec.PluginSpecCache;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.control.ReturnStatementVisitor;
import nextflow.script.control.TypeCheckingVisitorEx;
import nextflow.script.types.TypeCheckingUtils;
import nextflow.script.types.TypesEx;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;

import static nextflow.script.ast.ASTUtils.*;

/**
 * Validate config options against the config spec.
 *
 * Config scopes from third-party plugins are inferred
 * from the `plugins` block, if specified.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSpecVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private PluginSpecCache pluginSpecCache;

    private boolean typeChecking;

    private Stack<String> scopes = new Stack<>();

    public ConfigSpecVisitor(SourceUnit sourceUnit, PluginSpecCache pluginSpecCache, boolean typeChecking) {
        this.sourceUnit = sourceUnit;
        this.pluginSpecCache = pluginSpecCache;
        this.typeChecking = typeChecking;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    private SpecNode.Scope spec;

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ConfigNode cn ) {
            this.spec = getPluginScopes(cn);
            cn.setSpec(spec);
            super.visit(cn);
            this.spec = null;
        }
    }

    private SpecNode.Scope getPluginScopes(ConfigNode cn) {
        var defaultScopes = ConfigSpecFactory.defaultScopes();
        var pluginScopes = pluginConfigScopes(cn);
        var children = new HashMap<String, SpecNode>();
        children.putAll(defaultScopes);
        children.putAll(pluginScopes);
        return new SpecNode.Scope("", children);
    }

    private Map<String, SpecNode> pluginConfigScopes(ConfigNode cn) {
        // get plugin refs from `plugins` block
        var refs = cn.getConfigStatements().stream()
            .map(stmt ->
                stmt instanceof ConfigApplyBlockNode node && "plugins".equals(node.name) ? node : null
            )
            .filter(node -> node != null)
            .flatMap(node -> node.statements.stream())
            .map((call) -> {
                var arguments = asMethodCallArguments(call);
                var firstArg = arguments.get(0);
                return firstArg instanceof ConstantExpression ce ? ce.getText() : null;
            })
            .filter(ref -> ref != null)
            .map((ref) -> {
                var tokens = ref.split("@");
                var name = tokens[0];
                var version = tokens.length == 2 ? tokens[1] : null;
                return new PluginRef(name, version);
            })
            .toList();

        // fetch plugin specs from plugin registry
        var entries = refs.stream()
            .map((ref) -> pluginSpecCache.get(ref.name(), ref.version()))
            .filter(spec -> spec != null)
            .map(spec -> spec.configScopes())
            .toList();

        // set current versions in plugin spec cache
        pluginSpecCache.setCurrentVersions(refs);

        // collect config scopes from plugin specs
        var result = new HashMap<String, SpecNode>();
        for( var entry : entries )
            result.putAll(entry);
        return result;
    }

    @Override
    public void visitConfigAssign(ConfigAssignNode node) {
        var names = new ArrayList<>(scopes);
        names.addAll(node.names);

        // validate dynamic scopes (env, params, etc)
        var scope = names.get(0);
        if( "env".equals(scope) ) {
            var envName = String.join(".", DefaultGroovyMethods.tail(names));
            if( envName.contains(".") )
                addError("Invalid environment variable name '" + envName + "'", node);
            return;
        }
        if( "params".equals(scope) ) {
            return;
        }

        // validate config option
        var fqName = String.join(".", names);
        if( fqName.startsWith("process.ext.") )
            return;
        var option = spec.getOption(names);
        if( option == null ) {
            var message = "Unrecognized config option '" + fqName + "'";
            addWarning(message, String.join(".", node.names), node.getLineNumber(), node.getColumnNumber());
            return;
        }
        // validate type
        if( !typeChecking )
            return;
        var expectedTypes = option.types().stream()
            .map(t -> ClassHelper.makeCached(t).getPlainNodeReference())
            .toList();
        var actualType = inferredType(node.value, names);
        if( !isAssignableFromAny(expectedTypes, actualType) ) {
            var validTypes = expectedTypes.stream()
                .map(cn -> TypesEx.getName(cn))
                .collect(Collectors.joining(", "));
            var message = expectedTypes.size() == 1
                ? "Config option '" + fqName + "' with type " + TypesEx.getName(expectedTypes.get(0)) + " cannot be assigned to value with type " + TypesEx.getName(actualType)
                : "Config option '" + fqName + "' cannot be assigned to value with type " + TypesEx.getName(actualType) + " -- valid types are: " + validTypes;
            addWarning(message, String.join(".", node.names), node.getLineNumber(), node.getColumnNumber());
        }
    }

    private ClassNode inferredType(Expression node, List<String> scopes) {
        new TypeCheckingVisitorEx(sourceUnit, true).visit(node);
        var type = TypeCheckingUtils.getType(node);
        if( node instanceof ClosureExpression ce && "process".equals(scopes.get(0)) ) {
            var visitor = new ReturnStatementVisitor(sourceUnit);
            visitor.visit(ClassHelper.dynamicType(), ce.getCode());
            var inferredReturnType = visitor.getInferredReturnType();
            return inferredReturnType != null ? inferredReturnType : ClassHelper.dynamicType();
        }
        return type;
    }

    private boolean isAssignableFromAny(List<ClassNode> targetTypes, ClassNode sourceType) {
        if( targetTypes.isEmpty() )
            return true;
        for( var targetType : targetTypes ) {
            if( TypesEx.isAssignableFrom(targetType, sourceType) )
                return true;
        }
        return false;
    }

    @Override
    public void visitConfigBlock(ConfigBlockNode node) {
        // exclude selector name from scope stack
        if( node.kind != null ) {
            super.visitConfigBlock(node);
            return;
        }
        // exclude profiles.<profile> from scope stack
        if( scopes.size() == 1 && "profiles".equals(scopes.get(0)) ) {
            scopes.clear();
            super.visitConfigBlock(node);
            scopes.push("profiles");
            return;
        }
        // otherwise visit block statements normally
        scopes.push(node.name);
        super.visitConfigBlock(node);
        scopes.pop();
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new ConfigSpecError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private void addWarning(String message, String tokenText, int startLine, int startColumn) {
        var token = new Token(0, tokenText, startLine, startColumn);
        sourceUnit.getErrorCollector().addWarning(WarningMessage.POSSIBLE_ERRORS, message, token, sourceUnit);
    }

    private class ConfigSpecError extends SyntaxException implements PhaseAware {

        public ConfigSpecError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.NAME_RESOLUTION;
        }
    }

}
