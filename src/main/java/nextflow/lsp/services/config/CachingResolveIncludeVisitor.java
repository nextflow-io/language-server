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

import java.net.URI;
import java.util.Set;

import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.control.ResolveIncludeVisitor;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.control.SourceUnit;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class CachingResolveIncludeVisitor extends ResolveIncludeVisitor {

    private Set<URI> changedUris;

    private boolean changed;

    public CachingResolveIncludeVisitor(SourceUnit sourceUnit, Set<URI> changedUris) {
        super(sourceUnit);
        this.changedUris = changedUris;
    }

    private URI uri() {
        return getSourceUnit().getSource().getURI();
    }

    @Override
    public void visitConfigInclude(ConfigIncludeNode node) {
        if( !(node.source instanceof ConstantExpression) )
            return;
        var source = node.source.getText();
        var includeUri = getIncludeUri(uri(), source);
        if( !isIncludeLocal(includeUri) || !isIncludeStale(includeUri) )
            return;
        changed = true;
        super.visitConfigInclude(node);
    }

    protected boolean isIncludeStale(URI includeUri) {
        return changedUris.contains(uri()) || changedUris.contains(includeUri);
    }

    public boolean isChanged() {
        return changed;
    }
}
