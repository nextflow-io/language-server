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
package nextflow.config.v2

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigAssignmentNode extends ExpressionStatement {
    final List<String> names
    final Expression value

    ConfigAssignmentNode(List<String> names, Expression value) {
        super(EmptyExpression.INSTANCE)
        this.names = names
        this.value = value
    }
}


@CompileStatic
class ConfigAppendNode extends ConfigAssignmentNode {
    ConfigAppendNode(List<String> names, Expression value) {
        super(names, value)
    }
}
