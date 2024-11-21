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
package nextflow.script.ast;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.Expression;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class IncludeVariable extends ASTNode implements Variable {
    public final String name;
    public final String alias;

    public IncludeVariable(String name, String alias) {
        this.name = name;
        this.alias = alias;
    }

    public IncludeVariable(String name) {
        this(name, null);
    }

    private MethodNode method;

    public void setMethod(MethodNode method) {
        this.method = method;
    }

    public MethodNode getMethod() {
        return method;
    }

    @Override
    public ClassNode getType() {
        return method != null ? method.getReturnType() : ClassHelper.dynamicType();
    }

    @Override
    public ClassNode getOriginType() {
        return method != null ? method.getReturnType() : ClassHelper.dynamicType();
    }

    @Override
    public String getName() {
        return alias != null ? alias : name;
    }

    @Override
    public Expression getInitialExpression() {
        return null;
    }

    @Override
    public boolean hasInitialExpression() {
        return false;
    }

    @Override
    public boolean isInStaticContext() {
        return false;
    }

    @Override
    public boolean isDynamicTyped() {
        return method == null || method.isDynamicReturnType();
    }

    @Override
    public boolean isClosureSharedVariable() {
        return false;
    }

    @Override
    public void setClosureSharedVariable(boolean inClosure) {}

    @Override
    public int getModifiers() {
        return method != null ? method.getModifiers() : 0;
    }
}
