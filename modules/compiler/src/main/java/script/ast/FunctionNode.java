/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.script.ast;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.Statement;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class FunctionNode extends MethodNode {
    public final String documentation;

    public FunctionNode(String name, ClassNode returnType, Parameter[] parameters, Statement code) {
        super(name, 0, returnType, parameters, ClassNode.EMPTY_ARRAY, code);
        this.documentation = null;
    }

    public FunctionNode(String name, String documentation) {
        super(name, 0, null, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
        this.documentation = documentation;
    }
}
