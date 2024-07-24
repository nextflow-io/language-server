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
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class FeatureFlagNode extends ExpressionStatement {
    final String name
    final Expression value
    Variable accessedVariable

    FeatureFlagNode(String name, Expression value) {
        super(EmptyExpression.INSTANCE)
        this.name = name
        this.value = value
    }
}
