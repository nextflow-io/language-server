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

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
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
import org.codehaus.groovy.ast.MethodNode
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
        final changedUris = new HashSet<>(uris)
        final errorsByUri = super.update(uris, fileCache)

        for( final sourceUnit : getSourceUnits() ) {
            final visitor = new ResolveIncludeVisitor(sourceUnit, this, uris)
            visitor.visit()

            final uri = sourceUnit.getSource().getURI()
            errorsByUri.computeIfAbsent(uri, (k) -> [])
            errorsByUri[uri].removeIf((error) -> error instanceof IncludeException)
            errorsByUri[uri].addAll(visitor.getErrors())
            if( visitor.isChanged() )
                changedUris.add(uri)
        }

        for( final uri : changedUris ) {
            final sourceUnit = getSourceUnit(uri)
            final visitor = new MethodCallVisitor(sourceUnit, this)
            visitor.visit()
            errorsByUri[uri].removeIf((error) -> error instanceof MethodCallException)
            errorsByUri[uri].addAll(visitor.getErrors())
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
                visit(node.source)
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

}
