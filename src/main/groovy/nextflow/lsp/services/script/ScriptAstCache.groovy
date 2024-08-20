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
package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.file.FileCache
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.IncludeVariable
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.ScriptVisitor
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptAstCache extends ASTNodeCache {

    ScriptAstCache(Compiler compiler) {
        super(compiler)
    }

    @Override
    Map<URI, List<SyntaxException>> update(Set<URI> uris, FileCache fileCache) {
        final errorsByUri = super.update(uris, fileCache)

        for( final sourceUnit : getSourceUnits() ) {
            final uri = sourceUnit.getSource().getURI()
            if( !errorsByUri.containsKey(uri) )
                errorsByUri.put(uri, [])

            final includeVisitor = new ResolveIncludeVisitor(sourceUnit, this, uris)
            includeVisitor.visit()
            errorsByUri[uri].removeIf((error) -> error instanceof IncludeException)
            errorsByUri[uri].addAll(includeVisitor.getErrors())

            final callArgsVisitor = new CallArgumentsVisitor(sourceUnit, this)
            callArgsVisitor.visit()
            errorsByUri[uri].removeIf((error) -> error instanceof CallArgumentsException)
            errorsByUri[uri].addAll(callArgsVisitor.getErrors())
        }

        return errorsByUri
    }

    protected ASTNodeCache.Visitor createVisitor(SourceUnit sourceUnit) {
        return new Visitor(sourceUnit)
    }

    List<IncludeNode> getIncludeNodes(URI uri) {
        final scriptNode = getScriptNode(uri)
        if( !scriptNode )
            return Collections.emptyList()
        return scriptNode.getIncludes()
    }

    List<MethodNode> getDefinitions() {
        final List<MethodNode> result = []
        result.addAll(getFunctionNodes())
        result.addAll(getProcessNodes())
        result.addAll(getWorkflowNodes())
        return result
    }

    List<MethodNode> getDefinitions(URI uri) {
        final List<MethodNode> result = []
        result.addAll(getFunctionNodes(uri))
        result.addAll(getProcessNodes(uri))
        result.addAll(getWorkflowNodes(uri))
        return result
    }

    List<FunctionNode> getFunctionNodes() {
        final List<FunctionNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getFunctions())
        return result
    }

    List<FunctionNode> getFunctionNodes(URI uri) {
        final scriptNode = getScriptNode(uri)
        if( !scriptNode )
            return Collections.emptyList()
        return scriptNode.getFunctions()
    }

    List<ProcessNode> getProcessNodes() {
        final List<ProcessNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getProcesses())
        return result
    }

    List<ProcessNode> getProcessNodes(URI uri) {
        final scriptNode = getScriptNode(uri)
        if( !scriptNode )
            return Collections.emptyList()
        return scriptNode.getProcesses()
    }

    List<WorkflowNode> getWorkflowNodes() {
        final List<WorkflowNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getWorkflows())
        return result
    }

    List<WorkflowNode> getWorkflowNodes(URI uri) {
        final scriptNode = getScriptNode(uri)
        if( !scriptNode )
            return Collections.emptyList()
        return scriptNode.getWorkflows()
    }

    private List<ScriptNode> getScriptNodes() {
        final List<ScriptNode> result = []
        for( final sourceUnit : getSourceUnits() ) {
            final scriptNode = (ScriptNode) sourceUnit.getAST()
            if( scriptNode )
                result << scriptNode
        }
        return result
    }

    private ScriptNode getScriptNode(URI uri) {
        return (ScriptNode) getSourceUnit(uri).getAST()
    }

    private class Visitor extends ASTNodeCache.Visitor implements ScriptVisitor {

        Visitor(SourceUnit sourceUnit) {
            super(sourceUnit)
        }

        @Override
        void visit() {
            final moduleNode = sourceUnit.getAST()
            if( moduleNode !instanceof ScriptNode )
                return
            super.visit((ScriptNode) moduleNode)
        }

        @Override
        void visitFeatureFlag(FeatureFlagNode node) {
            pushASTNode(node)
            try {
                visit(node.value)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitInclude(IncludeNode node) {
            pushASTNode(node)
            try {
                for( final module : node.modules )
                    visitIncludeVariable(module)
            }
            finally {
                popASTNode()
            }
        }

        void visitIncludeVariable(IncludeVariable node) {
            pushASTNode(node)
            try {
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitFunction(FunctionNode node) {
            pushASTNode(node)
            try {
                for( final parameter : node.getParameters() )
                    visitParameter(parameter)
                visit(node.code)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitProcess(ProcessNode node) {
            pushASTNode(node)
            try {
                super.visitProcess(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitWorkflow(WorkflowNode node) {
            pushASTNode(node)
            try {
                super.visitWorkflow(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitOutput(OutputNode node) {
            pushASTNode(node)
            try {
                super.visitOutput(node)
            }
            finally {
                popASTNode()
            }
        }
    }

    private class ResolveIncludeVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

        private SourceUnit sourceUnit

        private URI uri

        private ScriptAstCache astCache

        private Set<URI> changedUris

        private List<SyntaxException> errors = []

        ResolveIncludeVisitor(SourceUnit sourceUnit, ScriptAstCache astCache, Set<URI> changedUris) {
            this.sourceUnit = sourceUnit
            this.uri = sourceUnit.getSource().getURI()
            this.astCache = astCache
            this.changedUris = changedUris
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        void visit() {
            final moduleNode = sourceUnit.getAST()
            if( moduleNode !instanceof ScriptNode )
                return
            final scriptNode = (ScriptNode) moduleNode
            for( final node : scriptNode.getIncludes() )
                visitInclude(node)
        }

        @Override
        void visitInclude(IncludeNode node) {
            final source = node.source
            if( source.startsWith('plugin/') )
                return
            final includeUri = getIncludeUri(uri, source)
            if( !isIncludeStale(node, includeUri) )
                return
            for( final module : node.modules )
                module.setMethod(null)
            final includeUnit = astCache.getSourceUnit(includeUri)
            if( !includeUnit ) {
                addError("Invalid include source: '${includeUri}'", node)
                return
            }
            if( !includeUnit.getAST() ) {
                addError("Module could not be parsed: '${includeUri}'", node)
                return
            }
            final definitions = astCache.getDefinitions(includeUri)
            for( final module : node.modules ) {
                final includedName = module.@name
                final includedNode = definitions.stream()
                    .filter(defNode -> defNode.name == includedName)
                    .findFirst()
                if( !includedNode.isPresent() ) {
                    addError("Included name '${includedName}' is not defined in module '${includeUri}'", node)
                    continue
                }
                module.setMethod(includedNode.get())
            }
        }

        protected static URI getIncludeUri(URI uri, String source) {
            Path includePath = Path.of(uri).getParent().resolve(source)
            if( includePath.isDirectory() )
                includePath = includePath.resolve('main.nf')
            else if( !source.endsWith('.nf') )
                includePath = Path.of(includePath.toString() + '.nf')
            return includePath.normalize().toUri()
        }

        protected boolean isIncludeStale(IncludeNode node, URI includeUri) {
            if( uri in changedUris || includeUri in changedUris )
                return true
            for( final module : node.modules ) {
                if( !module.getMethod() )
                    return true
            }
            return false
        }

        List<SyntaxException> getErrors() {
            return errors
        }

        @Override
        void addError(String message, ASTNode node) {
            errors.add(new IncludeException(message, node))
        }
    }

    private class CallArgumentsVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

        private SourceUnit sourceUnit

        private ScriptAstCache astCache

        private List<SyntaxException> errors = []

        CallArgumentsVisitor(SourceUnit sourceUnit, ScriptAstCache astCache) {
            this.sourceUnit = sourceUnit
            this.astCache = astCache
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        void visit() {
            final moduleNode = sourceUnit.getAST()
            if( moduleNode !instanceof ScriptNode )
                return
            final scriptNode = (ScriptNode) moduleNode
            for( final node : scriptNode.getWorkflows() )
                visitWorkflow(node)
        }

        @Override
        void visitWorkflow(WorkflowNode node) {
            visit(node.main)
        }

        @Override
        void visitMethodCallExpression(MethodCallExpression node) {
            final defNode = ASTUtils.getMethodFromCallExpression(node, astCache)
            if( !defNode )
                return
            if( defNode !instanceof ProcessNode && defNode !instanceof WorkflowNode )
                return

            final argsCount = ((ArgumentListExpression) node.arguments).size()
            final paramsCount = getNumberofParameters(defNode)
            if( argsCount != paramsCount )
                addError("Incorrect number of call arguments, expected ${paramsCount} but received ${argsCount}", node)

            super.visitMethodCallExpression(node)
        }

        protected static int getNumberofParameters(MethodNode node) {
            if( node instanceof ProcessNode ) {
                if( node.inputs !instanceof BlockStatement )
                    return 0
                final code = (BlockStatement) node.inputs
                return code.statements.size()
            }
            if( node instanceof WorkflowNode ) {
                if( node.takes !instanceof BlockStatement )
                    return 0
                final code = (BlockStatement) node.takes
                return code.statements.size()
            }
            return node.parameters.length
        }

        List<SyntaxException> getErrors() {
            return errors
        }

        @Override
        void addError(String message, ASTNode node) {
            errors.add(new CallArgumentsException(message, node))
        }
    }

}
