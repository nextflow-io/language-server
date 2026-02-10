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
package nextflow.script.control;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import nextflow.script.types.Bag;
import nextflow.script.types.Channel;
import nextflow.script.types.Tuple;
import nextflow.script.types.Value;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class TupleOpResolver {

    private static final ClassNode BAG_TYPE = ClassHelper.makeCached(Bag.class);
    private static final ClassNode CHANNEL_TYPE = ClassHelper.makeCached(Channel.class);
    private static final ClassNode TUPLE_TYPE = ClassHelper.makeCached(Tuple.class);
    private static final ClassNode VALUE_TYPE = ClassHelper.makeCached(Value.class);

    /**
     * Resolve the return type of dataflow operators that tranform
     * tuples, such as `combine`, `groupTuple`, and `join`.
     *
     * @param lhsType
     * @param method
     * @param arguments
     */
    public ClassNode apply(ClassNode lhsType, MethodNode method, List<Expression> arguments) {
        var name = method.getName();

        if( "combine".equals(name) )
            return applyCombine(lhsType, arguments);

        if( "groupTuple".equals(name) )
            return applyGroupBy(lhsType, arguments);

        if( "join".equals(name) )
            return applyJoin(lhsType, arguments);

        return ClassHelper.dynamicType();
    }

    /**
     * Resolve the result type of a `combine` operation in terms of the left
     * and right operands.
     *
     * Given arguments of type `(L1, L2, ..., Lm)` and `R`, `combine`
     * produces a tuple of type `(L1, L2, ..., Lm, R).
     *
     * When the `by` option is specified, `combine` produces the same result
     * type as `join`.
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyCombine(ClassNode lhsType, List<Expression> arguments) {
        if( !TUPLE_TYPE.equals(lhsType) )
            return ClassHelper.dynamicType();

        var namedArgs = namedArgs(arguments);
        if( namedArgs.containsKey("by") )
            return applyJoin(lhsType, arguments);

        var argType = getType(arguments.get(arguments.size() - 1));
        var rhsType = dataflowElementType(argType);

        var lgts = lhsType.getGenericsTypes();
        if( lgts == null || lgts.length == 0 )
            return ClassHelper.dynamicType();

        var gts = new GenericsType[lgts.length + 1];
        for( int i = 0; i < lgts.length; i++ )
            gts[i] = lgts[i];
        gts[lgts.length] = new GenericsType(rhsType);

        return channelTupleType(gts);
    }

    /**
     * Resolve the result type of a `groupTuple` operation.
     *
     * Given source tuples of type `(K, V1, V2, ..., Vn)`,
     * `groupTuple` produces a tuple of type `(K, Bag<V1>, Bag<V2>, ..., Bag<Vn>)`.
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyGroupBy(ClassNode lhsType, List<Expression> arguments) {
        if( !TUPLE_TYPE.equals(lhsType) )
            return ClassHelper.dynamicType();

        var namedArgs = namedArgs(arguments);
        if( namedArgs.containsKey("by") )
            return ClassHelper.dynamicType();

        var lgts = lhsType.getGenericsTypes();
        if( lgts == null || lgts.length == 0 )
            return ClassHelper.dynamicType();

        // TODO: group on index specified by `by` option
        // TODO: skip if `by` option isn't a single integer
        var gts = new GenericsType[lgts.length];
        gts[0] = lgts[0];
        for( int i = 1; i < lgts.length; i++ ) {
            var groupType = makeType(BAG_TYPE, lgts[i].getType());
            gts[i] = new GenericsType(groupType);
        }

        return channelTupleType(gts);
    }

    /**
     * Resolve the result type of a `join` operation in terms of the left
     * and right operands.
     *
     * Given tuples of type `(K, L1, L2, ..., Lm)` and `(K, R1, R2, ..., Rn)`,
     * `join` produces a tuple of type `(K, L1, L2, ..., Lm, R1, R2, ..., Rn).
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyJoin(ClassNode lhsType, List<Expression> arguments) {
        if( !TUPLE_TYPE.equals(lhsType) )
            return ClassHelper.dynamicType();

        var namedArgs = namedArgs(arguments);
        if( namedArgs.containsKey("by") )
            return ClassHelper.dynamicType();

        var argType = getType(arguments.get(arguments.size() - 1));
        var rhsType = dataflowElementType(argType);
        if( !TUPLE_TYPE.equals(rhsType) )
            return ClassHelper.dynamicType();

        var lgts = lhsType.getGenericsTypes();
        var rgts = rhsType.getGenericsTypes();
        if( lgts == null || lgts.length == 0 || rgts == null || rgts.length == 0 )
            return ClassHelper.dynamicType();

        // TODO: join on index specified by `by` option
        // TODO: skip if `by` option isn't a single integer
        var gts = new GenericsType[lgts.length + rgts.length - 1];
        for( int i = 0; i < lgts.length; i++ )
            gts[i] = lgts[i];
        for( int i = 1; i < rgts.length; i++ )
            gts[lgts.length + i - 1] = rgts[i];

        return channelTupleType(gts);
    }

    private static Map<String,Expression> namedArgs(List<Expression> args) {
        return args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale
            ? Map.ofEntries(
                nale.getMapEntryExpressions().stream()
                    .map((entry) -> {
                        var name = entry.getKeyExpression().getText();
                        var value = entry.getValueExpression();
                        return Map.entry(name, value);
                    })
                    .toArray(Map.Entry[]::new)
            )
            : Collections.emptyMap();
    }

    private static ClassNode dataflowElementType(ClassNode type) {
        if( CHANNEL_TYPE.equals(type) || VALUE_TYPE.equals(type) )
            return elementType(type);
        return ClassHelper.dynamicType();
    }

    private static ClassNode channelTupleType(GenericsType[] gts) {
        var tupleType = TUPLE_TYPE.getPlainNodeReference();
        tupleType.setGenericsTypes(gts);
        return makeType(CHANNEL_TYPE, tupleType);
    }

}
