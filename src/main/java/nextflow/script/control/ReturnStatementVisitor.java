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
package nextflow.script.control;

import java.util.ArrayList;

import nextflow.script.types.TypesEx;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 * Infer the return type of a code block based on implicit
 * and explicit return statements.
 *
 * @see org.codehaus.groovy.classgen.ReturnAdder
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ReturnStatementVisitor extends ClassCodeVisitorSupport {

    private SourceUnit sourceUnit;

    private ClassNode returnType;

    private ClassNode inferredReturnType;

    public ReturnStatementVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit(ClassNode returnType, Statement code) {
        this.returnType = returnType;
        visit(addReturnsIfNeeded(code));
        this.returnType = null;
    }

    private Statement addReturnsIfNeeded(Statement node) {
        if( node instanceof BlockStatement block && !block.isEmpty() ) {
            var statements = new ArrayList<>(block.getStatements());
            int lastIndex = statements.size() - 1;
            var last = addReturnsIfNeeded(statements.get(lastIndex));
            statements.set(lastIndex, last);
            return new BlockStatement(statements, block.getVariableScope());
        }

        if( node instanceof ExpressionStatement es ) {
            return new ReturnStatement(es.getExpression());
        }

        if( node instanceof IfStatement ies ) {
            return new IfStatement(
                ies.getBooleanExpression(),
                addReturnsIfNeeded(ies.getIfBlock()),
                addReturnsIfNeeded(ies.getElseBlock()) );
        }

        return node;
    }

    @Override
    public void visitReturnStatement(ReturnStatement node) {
        var sourceType = getType(node.getExpression());
        if( inferredReturnType != null && !ClassHelper.isDynamicTyped(returnType) ) {
            if( !TypesEx.isAssignableFrom(inferredReturnType, sourceType) )
                addError(String.format("Return value with type %s does not match previous return type (%s)", TypesEx.getName(sourceType), TypesEx.getName(inferredReturnType)), node);
        }
        else if( TypesEx.isAssignableFrom(returnType, sourceType) ) {
            inferredReturnType = sourceType;
        }
        else {
            addError(String.format("Return value with type %s does not match the declared return type (%s)", TypesEx.getName(sourceType), TypesEx.getName(returnType)), node);
        }
    }

    public ClassNode getInferredReturnType() {
        return inferredReturnType;
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new TypeError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class TypeError extends SyntaxException implements PhaseAware {

        public TypeError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.TYPE_CHECKING;
        }
    }
}
