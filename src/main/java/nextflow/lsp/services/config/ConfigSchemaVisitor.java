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
import java.util.Stack;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.ast.ConfigNode;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.config.schema.SchemaNode;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSchemaVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private boolean typeChecking;

    private Stack<String> scopes = new Stack<>();

    public ConfigSchemaVisitor(SourceUnit sourceUnit, boolean typeChecking) {
        this.sourceUnit = sourceUnit;
        this.typeChecking = typeChecking;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ConfigNode cn )
            super.visit(cn);
    }

    @Override
    public void visitConfigAssign(ConfigAssignNode node) {
        var names = new ArrayList<>(scopes);
        names.addAll(node.names);
        if( "profiles".equals(names.get(0)) ) {
            if( !names.isEmpty() ) names.remove(0);
            if( !names.isEmpty() ) names.remove(0);
        }
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
        var option = SchemaNode.ROOT.getOption(names);
        if( option == null ) {
            var message = "Unrecognized config option '" + fqName + "'";
            addWarning(message, String.join(".", node.names), node.getLineNumber(), node.getColumnNumber());
            return;
        }
        // validate type
        if( typeChecking ) {
            var expectedType = option.type();
            var actualType = node.value.getType().getTypeClass();
            if( expectedType != null && !Types.isAssignableFrom(expectedType, actualType) ) {
                var message = "Type mismatch for config option '" + fqName + "' -- expected a " + Types.getName(expectedType) + " but received a " + Types.getName(actualType);
                addWarning(message, String.join(".", node.names), node.getLineNumber(), node.getColumnNumber());
            }
        }
    }

    @Override
    public void visitConfigBlock(ConfigBlockNode node) {
        var newScope = node.kind == null;
        if( newScope )
            scopes.add(node.name);
        super.visitConfigBlock(node);
        if( newScope )
            scopes.pop();
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new ConfigSchemaError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private void addWarning(String message, String tokenText, int startLine, int startColumn) {
        var token = new Token(0, tokenText, startLine, startColumn);
        sourceUnit.getErrorCollector().addWarning(WarningMessage.POSSIBLE_ERRORS, message, token, sourceUnit);
    }

    private class ConfigSchemaError extends SyntaxException implements PhaseAware {

        public ConfigSchemaError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.NAME_RESOLUTION;
        }
    }

}
