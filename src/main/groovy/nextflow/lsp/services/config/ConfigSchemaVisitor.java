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

import java.util.ArrayList;
import java.util.List;

import nextflow.config.dsl.ConfigSchema;
import nextflow.config.v2.ConfigAssignNode;
import nextflow.config.v2.ConfigBlockNode;
import nextflow.config.v2.ConfigIncludeNode;
import nextflow.config.v2.ConfigNode;
import nextflow.config.v2.ConfigVisitorSupport;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSchemaVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private List<String> scopes = new ArrayList<>();

    public ConfigSchemaVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
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
        var scope = names.get(0);
        if( "profiles".equals(scope) ) {
            if( !names.isEmpty() ) names.remove(0);
            if( !names.isEmpty() ) names.remove(0);
        }
        if( "env".equals(scope) ) {
            var envName = String.join(".", DefaultGroovyMethods.tail(names));
            if( envName.contains(".") )
                addError("Invalid environment variable name '" + envName + "'", node);
            return;
        }
        if( "params".equals(scope) ) {
            // TODO: validate params against schema
            return;
        }

        var fqName = String.join(".", names);
        if( fqName.startsWith("process.ext.") )
            return;
        var option = ConfigSchema.OPTIONS.get(fqName);
        if( option == null )
            sourceUnit.addWarning("Unrecognized config option '" + fqName + "'", node);
    }

    @Override
    public void visitConfigBlock(ConfigBlockNode node) {
        var newScope = node.kind == null;
        if( newScope )
            scopes.add(node.name);
        super.visitConfigBlock(node);
        if( newScope )
            scopes.remove(scopes.size() - 1);
    }

    @Override
    public void visitConfigInclude(ConfigIncludeNode node) {
        // TODO: validate embedded config settings in this context?
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new SyntaxException(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

}
