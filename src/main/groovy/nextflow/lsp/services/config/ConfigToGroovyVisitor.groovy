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
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.control.SourceUnit

import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigToGroovyVisitor extends ClassCodeVisitorSupport {

    private SourceUnit sourceUnit

    ConfigToGroovyVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode == null )
            return
        super.visitBlockStatement(moduleNode.getStatementBlock())

        if( moduleNode.isEmpty() )
            moduleNode.addStatement(ReturnStatement.RETURN_NULL_OR_VOID)
    }

    @Override
    void visitExpressionStatement(ExpressionStatement node) {
        if( node instanceof ConfigAssignNode )
            visitConfigAssign(node)
        else if( node instanceof ConfigBlockNode )
            visitConfigBlock(node)
        else if( node instanceof ConfigIncludeNode )
            visitConfigInclude(node)
        else
            super.visitExpressionStatement(node)
    }

    protected void visitConfigAssign(ConfigAssignNode node) {
        final methodName = node instanceof ConfigAppendNode ? 'append' : 'assign'
        final names = listX( node.names.collect(name -> constX(name)) as List<Expression> )
        node.expression = callThisX(methodName, args(names, node.value))
    }

    protected void visitConfigBlock(ConfigBlockNode node) {
        node.expression = callThisX(node.kind ?: 'block', args(constX(node.name), closureX(node.block)))
    }

    protected void visitConfigInclude(ConfigIncludeNode node) {
        node.expression = callThisX('includeConfig', args(node.source))
    }
}
