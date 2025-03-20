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
package nextflow.lsp.ast;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;

/**
 * Create a reverse lookup of ast node to parent.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTParentVisitor extends CodeVisitorSupport {

    private Map<ASTNode, ASTNode> parents = new HashMap<>();

    private Stack<ASTNode> stack = new Stack<>();

    public void push(ASTNode node) {
        var isSynthetic = node instanceof AnnotatedNode an && an.isSynthetic();
        if( !isSynthetic ) {
            var parent = stack.size() > 0 ? stack.lastElement() : null;
            parents.put(node, parent);
        }

        stack.add(node);
    }

    public void pop() {
        stack.pop();
    }

    public Map<ASTNode, ASTNode> getParents() {
        return parents;
    }

    // statements

    @Override
    public void visitBlockStatement(BlockStatement node) {
        push(node);
        try {
            super.visitBlockStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitIfElse(IfStatement node) {
        push(node);
        try {
            super.visitIfElse(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitExpressionStatement(ExpressionStatement node) {
        push(node);
        try {
            super.visitExpressionStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitReturnStatement(ReturnStatement node) {
        push(node);
        try {
            super.visitReturnStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitAssertStatement(AssertStatement node) {
        push(node);
        try {
            super.visitAssertStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitTryCatchFinally(TryCatchStatement node) {
        push(node);
        try {
            super.visitTryCatchFinally(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitThrowStatement(ThrowStatement node) {
        push(node);
        try {
            super.visitThrowStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitCatchStatement(CatchStatement node) {
        push(node);
        try {
            super.visitCatchStatement(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitEmptyStatement(EmptyStatement node) {
        push(node);
        try {
            super.visitEmptyStatement(node);
        }
        finally {
            pop();
        }
    }

    // expressions

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        push(node);
        try {
            if( !node.isImplicitThis() )
                node.getObjectExpression().visit(this);
            node.getMethod().visit(this);
            node.getArguments().visit(this);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
        push(node);
        try {
            super.visitStaticMethodCallExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression node) {
        push(node);
        try {
            super.visitConstructorCallExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitTernaryExpression(TernaryExpression node) {
        push(node);
        try {
            super.visitTernaryExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitShortTernaryExpression(ElvisOperatorExpression node) {
        push(node);
        try {
            // see CodeVisitorSupport::visitShortTernaryExpression()
            super.visitTernaryExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        push(node);
        try {
            super.visitBinaryExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitBooleanExpression(BooleanExpression node) {
        push(node);
        try {
            super.visitBooleanExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        push(node);
        try {
            var parameters = node.getParameters();
            if( parameters != null ) {
                for( var parameter : parameters )
                    visitParameter(parameter);
            }
            super.visitClosureExpression(node);
        }
        finally {
            pop();
        }
    }

    public void visitParameter(Parameter node) {
        push(node);
        try {
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitTupleExpression(TupleExpression node) {
        push(node);
        try {
            super.visitTupleExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitMapExpression(MapExpression node) {
        push(node);
        try {
            super.visitMapExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitMapEntryExpression(MapEntryExpression node) {
        push(node);
        try {
            super.visitMapEntryExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitListExpression(ListExpression node) {
        push(node);
        try {
            super.visitListExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitRangeExpression(RangeExpression node) {
        push(node);
        try {
            super.visitRangeExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        push(node);
        try {
            super.visitPropertyExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitFieldExpression(FieldExpression node) {
        push(node);
        try {
            super.visitFieldExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitConstantExpression(ConstantExpression node) {
    }

    @Override
    public void visitClassExpression(ClassExpression node) {
        push(node);
        try {
            super.visitClassExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        push(node);
        try {
            super.visitVariableExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitGStringExpression(GStringExpression node) {
        push(node);
        try {
            super.visitGStringExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitNotExpression(NotExpression node) {
        push(node);
        try {
            super.visitNotExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitUnaryMinusExpression(UnaryMinusExpression node) {
        push(node);
        try {
            super.visitUnaryMinusExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitUnaryPlusExpression(UnaryPlusExpression node) {
        push(node);
        try {
            super.visitUnaryPlusExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
        push(node);
        try {
            super.visitBitwiseNegationExpression(node);
        }
        finally {
            pop();
        }
    }

    @Override
    public void visitCastExpression(CastExpression node) {
        push(node);
        try {
            super.visitCastExpression(node);
        }
        finally {
            pop();
        }
    }
}
