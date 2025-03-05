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

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import nextflow.script.dsl.Constant;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTHelpers.*;

/**
 * Helper class for checking variable names.
 *
 * See: org.codehaus.groovy.classgen.VariableScopeVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class VariableScopeChecker {

    private SourceUnit sourceUnit;

    private Map<String,Variable> includes = new HashMap<>();

    private VariableScope currentScope;

    private Set<Variable> declaredVariables = Collections.newSetFromMap(new IdentityHashMap<>());

    public VariableScopeChecker(SourceUnit sourceUnit, ClassNode classScope) {
        this.sourceUnit = sourceUnit;
        this.currentScope = new VariableScope();
        this.currentScope.setClassScope(classScope);
    }

    public void setCurrentScope(VariableScope scope) {
        currentScope = scope;
    }

    public VariableScope getCurrentScope() {
        return currentScope;
    }

    public void include(String name, Variable variable) {
        includes.put(name, variable);
    }

    public Variable getInclude(String name) {
        return includes.get(name);
    }

    public void checkUnusedVariables() {
        for( var variable : declaredVariables ) {
            if( variable instanceof ASTNode node && !variable.getName().startsWith("_") ) {
                var message = variable instanceof Parameter
                    ? "Parameter was not used -- prefix with `_` to suppress warning"
                    : "Variable was declared but not used";
                sourceUnit.addWarning(message, node);
            }
        }
    }

    public void pushScope(Class classScope) {
        currentScope = new VariableScope(currentScope);
        if( classScope != null )
            currentScope.setClassScope(ClassHelper.makeCached(classScope));
    }

    public void pushScope() {
        pushScope(null);
    }

    public void popScope() {
        currentScope = currentScope.getParent();
    }

    public void declare(VariableExpression variable) {
        declare(variable, variable);
        variable.setAccessedVariable(variable);
    }

    public void declare(Variable variable, ASTNode context) {
        var name = variable.getName();
        for( var scope = currentScope; scope != null; scope = scope.getParent() ) {
            var other = scope.getDeclaredVariable(name);
            if( other != null ) {
                addError("`" + name + "` is already declared", context, "First declared here", (ASTNode) other);
                break;
            }
        }
        currentScope.putDeclaredVariable(variable);
        declaredVariables.add(variable);
    }

    /**
     * Find the declaration of a given variable.
     *
     * @param name
     * @param node
     */
    public Variable findVariableDeclaration(String name, ASTNode node) {
        Variable variable = null;
        VariableScope scope = currentScope;
        boolean isClassVariable = false;
        while( scope != null ) {
            variable = scope.getDeclaredVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedLocalVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedClassVariable(name);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            variable = findDslMember(scope.getClassScope(), name, node);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            variable = includes.get(name);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            scope = scope.getParent();
        }
        if( variable == null )
            return null;
        VariableScope end = scope;
        scope = currentScope;
        while( true ) {
            if( isClassVariable )
                scope.putReferencedClassVariable(variable);
            else
                scope.putReferencedLocalVariable(variable);
            if( scope == end )
                break;
            scope = scope.getParent();
        }
        declaredVariables.remove(variable);
        return variable;
    }

    /**
     * Find the definition of a built-in variable or function.
     *
     * @param cn
     * @param name
     * @param node
     */
    public Variable findDslMember(ClassNode cn, String name, ASTNode node) {
        while( cn != null ) {
            for( var mn : cn.getMethods() ) {
                var an = findAnnotation(mn, Constant.class);
                var memberName = an.isPresent()
                    ? an.get().getMember("value").getText()
                    : mn.getName();
                if( !name.equals(memberName) )
                    continue;
                if( findAnnotation(mn, Deprecated.class).isPresent() )
                    addFutureWarning("`" + name + "` is deprecated and will be removed in a future version", node);
                return wrapMethodAsVariable(mn, memberName);
            }

            cn = cn.getInterfaces().length > 0
                ? cn.getInterfaces()[0]
                : null;
        }

        return null;
    }

    private Variable wrapMethodAsVariable(MethodNode mn, String name) {
        var cn = mn.getDeclaringClass();
        var fn = new FieldNode(name, mn.getModifiers() & 0xF, mn.getReturnType(), cn, null);
        fn.setHasNoRealSourcePosition(true);
        fn.setDeclaringClass(cn);
        fn.setSynthetic(true);
        var pn = new PropertyNode(fn, fn.getModifiers(), null, null);
        pn.putNodeMetaData("access.method", mn);
        pn.setDeclaringClass(cn);
        return pn;
    }

    public void addFutureWarning(String message, String tokenText, ASTNode node, String otherMessage, ASTNode otherNode) {
        var token = new Token(0, tokenText, node.getLineNumber(), node.getColumnNumber()); // ASTNode to CSTNode
        var warning = new FutureWarning(WarningMessage.POSSIBLE_ERRORS, message, token, sourceUnit);
        if( otherNode != null )
            warning.setRelatedInformation(otherMessage, otherNode);
        sourceUnit.getErrorCollector().addWarning(warning);
    }

    public void addFutureWarning(String message, ASTNode node, String otherMessage, ASTNode otherNode) {
        addFutureWarning(message, "", node, otherMessage, otherNode);
    }

    public void addFutureWarning(String message, String tokenText, ASTNode node) {
        addFutureWarning(message, tokenText, node, null, null);
    }

    public void addFutureWarning(String message, ASTNode node) {
        addFutureWarning(message, "", node, null, null);
    }

    public void addError(String message, ASTNode node) {
        addError(new VariableScopeError(message, node));
    }

    public void addError(String message, ASTNode node, String otherMessage, ASTNode otherNode) {
        var cause = new VariableScopeError(message, node);
        if( otherNode != null )
            cause.setRelatedInformation(otherMessage, otherNode);
        addError(cause);
    }

    public void addError(SyntaxException cause) {
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class VariableScopeError extends SyntaxException implements PhaseAware, RelatedInformationAware {

        private String otherMessage;

        private ASTNode otherNode;

        public VariableScopeError(String message, ASTNode node) {
            super(message, node);
        }

        public void setRelatedInformation(String otherMessage, ASTNode otherNode) {
            this.otherMessage = otherMessage;
            this.otherNode = otherNode;
        }

        @Override
        public int getPhase() {
            return Phases.NAME_RESOLUTION;
        }

        @Override
        public String getOtherMessage() {
            return otherMessage;
        }

        @Override
        public ASTNode getOtherNode() {
            return otherNode;
        }
    }

}
