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
package nextflow.lsp.services.script;

import java.util.Map;

import nextflow.lsp.ast.ASTParentVisitor;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeEntryNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ParamBlockNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.TupleParameter;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.control.SourceUnit;

import static nextflow.script.ast.ASTUtils.asFlatParams;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptAstParentVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ASTParentVisitor lookup = new ASTParentVisitor();

    public ScriptAstParentVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn )
            super.visit(sn);
    }

    public Map<ASTNode, ASTNode> getParents() {
        return lookup.getParents();
    }

    @Override
    public void visitFeatureFlag(FeatureFlagNode node) {
        lookup.push(node);
        try {
            lookup.visit(node.value);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitInclude(IncludeNode node) {
        lookup.push(node);
        try {
            lookup.visit(node.source);
            for( var entry : node.entries )
                visitIncludeEntry(entry);
        }
        finally {
            lookup.pop();
        }
    }

    protected void visitIncludeEntry(IncludeEntryNode node) {
        lookup.push(node);
        try {
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitParams(ParamBlockNode node) {
        lookup.push(node);
        try {
            lookup.visitParameters(node.declarations);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitWorkflow(WorkflowNode node) {
        lookup.push(node);
        try {
            lookup.visitParameters(node.getParameters());
            lookup.visit(node.main);
            lookup.visit(node.emits);
            lookup.visit(node.publishers);
            lookup.visit(node.onComplete);
            lookup.visit(node.onError);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitProcessV2(ProcessNodeV2 node) {
        lookup.push(node);
        try {
            lookup.visitParameters(asFlatParams(node.inputs));
            lookup.visit(node.directives);
            lookup.visit(node.stagers);
            lookup.visit(node.outputs);
            lookup.visit(node.topics);
            lookup.visit(node.when);
            lookup.visit(node.exec);
            lookup.visit(node.stub);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitProcessV1(ProcessNodeV1 node) {
        lookup.push(node);
        try {
            lookup.visit(node.directives);
            lookup.visit(node.inputs);
            lookup.visit(node.outputs);
            lookup.visit(node.when);
            lookup.visit(node.exec);
            lookup.visit(node.stub);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitFunction(FunctionNode node) {
        lookup.push(node);
        try {
            lookup.visitParameters(node.getParameters());
            lookup.visit(node.getCode());
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitEnum(ClassNode node) {
        lookup.push(node);
        try {
            super.visitEnum(node);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitField(FieldNode node) {
        lookup.push(node);
        try {
            super.visitField(node);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitOutput(OutputNode node) {
        lookup.push(node);
        try {
            lookup.visit(node.body);
        }
        finally {
            lookup.pop();
        }
    }
}
