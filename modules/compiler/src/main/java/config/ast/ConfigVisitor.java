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
package nextflow.config.ast;

import org.codehaus.groovy.ast.GroovyCodeVisitor;

public interface ConfigVisitor extends GroovyCodeVisitor {

    void visit(ConfigNode node);

    void visit(ConfigStatement node);

    void visitConfigAssign(ConfigAssignNode node);

    void visitConfigBlock(ConfigBlockNode node);

    void visitConfigInclude(ConfigIncludeNode node);

    void visitConfigIncomplete(ConfigIncompleteNode node);

}