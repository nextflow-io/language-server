/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package nextflow.script.v2;

import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ExpressionTransformer;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;

/**
 * Transforms expressions in a whole class. Transformed expressions are usually not visited.
 *
 * @see ClassCodeExpressionTransformer
 */
public abstract class ScriptExpressionTransformer extends ScriptVisitorSupport implements ExpressionTransformer {

    /**
     * <strong>NOTE</strong>: This method does not visit Expressions within Closures,
     * for performance and historical reasons.
     * If you want those Expressions to be visited, you can do this:
     * <pre>
     * {@code
     * public class YourTransformer extends ClassCodeExpressionTransformer {
     *   ...
     *
     *   @Override
     *   public Expression transform(final Expression expr) {
     *     if (expr instanceof ClosureExpression) {
     *       expr.visit(this);
     *
     *       return expr;
     *     }
     *     // ...
     *   }
     * }}
     * </pre>
     */
    @Override
    public Expression transform(Expression expr) {
        if( expr == null ) return null;
        return expr.transformExpression(this);
    }

    @Override
    public void visitParam(ParamNode node) {
        node.value = transform(node.value);
    }

    @Override
    public void visitIfElse(IfStatement stmt) {
        stmt.setBooleanExpression((BooleanExpression) transform(stmt.getBooleanExpression()));
        stmt.getIfBlock().visit(this);
        stmt.getElseBlock().visit(this);
    }

    @Override
    public void visitExpressionStatement(ExpressionStatement stmt) {
        stmt.setExpression(transform(stmt.getExpression()));
    }

    @Override
    public void visitReturnStatement(ReturnStatement stmt) {
        stmt.setExpression(transform(stmt.getExpression()));
    }

    @Override
    public void visitAssertStatement(AssertStatement stmt) {
        stmt.setBooleanExpression((BooleanExpression) transform(stmt.getBooleanExpression()));
        stmt.setMessageExpression(transform(stmt.getMessageExpression()));
    }

    @Override
    public void visitThrowStatement(ThrowStatement stmt) {
        stmt.setExpression(transform(stmt.getExpression()));
    }

    /**
     * Set the source position of target, including its property if it has one.
     *
     * @param target
     * @param source
     */
    protected static void setSourcePosition(Expression target, Expression source) {
        target.setSourcePosition(source);
        if( target instanceof PropertyExpression pe ) {
            pe.getProperty().setSourcePosition(source);
        }
    }
}
