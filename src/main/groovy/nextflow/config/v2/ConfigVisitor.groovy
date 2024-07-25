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
package nextflow.config.v2

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigIncompleteNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigStatement
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.Statement

@CompileStatic
interface ConfigVisitor {

    default void visit(ConfigNode moduleNode) {
        for( final stmt : moduleNode.getConfigStatements() )
            visit(stmt)
    }

    default void visit(ConfigStatement node) {
        node.visit(this)
    }

    default void visitConfigAssign(ConfigAssignNode node) {
        visit(node.value)
    }

    default void visitConfigBlock(ConfigBlockNode node) {
        for( final stmt : node.statements )
            visit(stmt)
    }

    default void visitConfigInclude(ConfigIncludeNode node) {
        visit(node.source)
    }

    default void visitConfigIncomplete(ConfigIncompleteNode node) {
    }

    void visit(Expression node)

}
