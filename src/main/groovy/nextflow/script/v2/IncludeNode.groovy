/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.script.v2

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class IncludeNode extends ExpressionStatement {
    final String source
    final List<IncludeVariable> modules

    IncludeNode(String source, List<IncludeVariable> modules) {
        super(EmptyExpression.INSTANCE)
        this.source = source
        this.modules = modules
    }
}


@CompileStatic
class IncludeVariable extends ASTNode implements Variable {
    final String name
    final String alias

    IncludeVariable(String name, String alias=null) {
        this.name = name
        this.alias = alias
    }

    private MethodNode method

    void setMethod(MethodNode method) {
        this.method = method
    }

    MethodNode getMethod() { method }

    @Override
    ClassNode getType() { method.getReturnType() }

    @Override
    ClassNode getOriginType() { method.getReturnType() }

    @Override
    String getName() { alias ?: name }

    @Override
    Expression getInitialExpression() { null }

    @Override
    boolean hasInitialExpression() { false }

    @Override
    boolean isInStaticContext() { false }

    @Override
    boolean isDynamicTyped() { method.isDynamicReturnType() }

    @Override
    boolean isClosureSharedVariable() { false }

    @Override
    void setClosureSharedVariable(boolean inClosure) {}

    @Override
    int getModifiers() { method.getModifiers() }
}
