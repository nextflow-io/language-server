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
package nextflow.lsp.services.config;

import java.util.Map;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.ast.ConfigIncompleteNode;
import nextflow.config.ast.ConfigNode;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.lsp.ast.ASTParentVisitor;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigAstParentVisitor extends ConfigVisitorSupport {

    private SourceUnit sourceUnit;

    private ASTParentVisitor lookup = new ASTParentVisitor();

    public ConfigAstParentVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ConfigNode cn )
            super.visit(cn);
    }

    public Map<ASTNode, ASTNode> getParents() {
        return lookup.getParents();
    }

    @Override
    public void visitConfigAssign(ConfigAssignNode node) {
        lookup.push(node);
        try {
            lookup.visit(node.value);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitConfigBlock(ConfigBlockNode node) {
        lookup.push(node);
        try {
            super.visitConfigBlock(node);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitConfigInclude(ConfigIncludeNode node) {
        lookup.push(node);
        try {
            lookup.visit(node.source);
        }
        finally {
            lookup.pop();
        }
    }

    @Override
    public void visitConfigIncomplete(ConfigIncompleteNode node) {
        lookup.push(node);
        try {
            super.visitConfigIncomplete(node);
        }
        finally {
            lookup.pop();
        }
    }
}
