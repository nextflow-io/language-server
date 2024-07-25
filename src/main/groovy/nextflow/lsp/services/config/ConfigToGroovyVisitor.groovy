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
import nextflow.config.v2.ConfigAppendNode
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitor
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit

import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigToGroovyVisitor extends ClassCodeVisitorSupport implements ConfigVisitor {

    private SourceUnit sourceUnit

    private ConfigNode moduleNode

    ConfigToGroovyVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
        this.moduleNode = (ConfigNode) sourceUnit.getAST()
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        if( moduleNode == null )
            return
        ConfigVisitor.super.visit(moduleNode)
        if( moduleNode.isEmpty() )
            moduleNode.addStatement(ReturnStatement.RETURN_NULL_OR_VOID)
    }

    @Override
    void visitConfigAssign(ConfigAssignNode node) {
        moduleNode.addStatement(transformConfigAssign(node))
    }

    protected Statement transformConfigAssign(ConfigAssignNode node) {
        final methodName = node instanceof ConfigAppendNode ? 'append' : 'assign'
        final names = listX( node.names.collect(name -> constX(name)) as List<Expression> )
        return stmt(callThisX(methodName, args(names, node.value)))
    }

    @Override
    void visitConfigBlock(ConfigBlockNode node) {
        moduleNode.addStatement(transformConfigBlock(node))
    }

    protected Statement transformConfigBlock(ConfigBlockNode node) {
        final List<Statement> statements = []
        for( final stmt : node.statements ) {
            if( stmt instanceof ConfigAssignNode )
                statements.add(transformConfigAssign(stmt))
            else if( stmt instanceof ConfigBlockNode )
                statements.add(transformConfigBlock(stmt))
            else if( stmt instanceof ConfigIncludeNode )
                statements.add(transformConfigInclude(stmt))
        }
        final code = block(new VariableScope(), statements)
        return stmt(callThisX(node.kind ?: 'block', args(constX(node.name), closureX(code))))
    }

    @Override
    void visitConfigInclude(ConfigIncludeNode node) {
        moduleNode.addStatement(transformConfigInclude(node))
    }

    protected Statement transformConfigInclude(ConfigIncludeNode node) {
        return stmt(callThisX('includeConfig', args(node.source)))
    }
}
