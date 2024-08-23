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
package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigSchema
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitor
import nextflow.lsp.compiler.SyntaxWarning
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigSchemaVisitor extends ClassCodeVisitorSupport implements ConfigVisitor {

    private SourceUnit sourceUnit

    private List<String> scopes = []

    ConfigSchemaVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ConfigNode )
            return
        super.visit((ConfigNode) moduleNode)
    }

    @Override
    void visitConfigAssign(ConfigAssignNode node) {
        final names = scopes + node.names
        if( names.first() == 'profiles' ) {
            if( names ) names.pop()
            if( names ) names.pop()
        }
        if( names.first() == 'env' ) {
            final envName = names.tail().join('.')
            if( envName.contains('.') )
                addWarning("Invalid environment variable name '${envName}'", node)
            return
        }
        if( names.first() == 'params' ) {
            // TODO: validate params against schema
            return
        }

        final fqName = names.join('.')
        if( fqName.startsWith('process.ext.') )
            return
        final option = ConfigSchema.OPTIONS[fqName]
        if( !option )
            addWarning("Unrecognized config option '${fqName}'", node)
    }

    @Override
    void visitConfigBlock(ConfigBlockNode node) {
        final newScope = node.kind == null
        if( newScope )
            scopes.add(node.name)
        super.visitConfigBlock(node)
        if( newScope )
            scopes.removeLast()
    }

    @Override
    void visitConfigInclude(ConfigIncludeNode node) {
        // TODO: validate embedded config settings in this context?
    }

    void addWarning(String message, ASTNode node) {
        final cause = new SyntaxWarning(message, node)
        final errorMessage = new SyntaxErrorMessage(cause, sourceUnit)
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage)
    }

}
