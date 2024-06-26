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
package nextflow.script.v2

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.Statement

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class FunctionNode extends MethodNode {
    final String documentation

    FunctionNode(String name, ClassNode returnType, Parameter[] parameters, Statement code) {
        super(name, 0, returnType, parameters, [] as ClassNode[], code)
    }

    FunctionNode(String name, String documentation) {
        this(name, null, Parameter.EMPTY_ARRAY, EmptyStatement.INSTANCE)
        this.documentation = documentation
    }
}
