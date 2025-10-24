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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import groovy.yaml.YamlSlurper;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.TupleParameter;
import nextflow.script.types.Tuple;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ExpressionTransformer;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTUtils.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 * Convert a legacy process definition to a typed process.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProcessConverter {

    private static final ClassNode PATH_TYPE = ClassHelper.makeCached(Path.class);

    private Map<String,ClassNode> specInputs;

    public ProcessConverter(URI uri) {
        specInputs = new ModuleSpecVisitor().getInputs(uri);
    }

    public ProcessNodeV2 apply(ProcessNodeV1 node) {
        var stagers = new ArrayList<Statement>();
        var inputs = typedInputs(node.inputs, stagers);

        var topics = new ArrayList<Statement>();
        var outputs = typedOutputs(node.outputs, topics);

        return new ProcessNodeV2(
            node.getName(),
            node.directives,
            inputs,
            blockOrEmpty(stagers),
            outputs,
            blockOrEmpty(topics),
            node.when,
            node.type,
            node.exec,
            node.stub);
    }

    private int nextInputId;

    private Parameter[] typedInputs(Statement inputs, List<Statement> stagers) {
        if( inputs.isEmpty() )
            return Parameter.EMPTY_ARRAY;
        nextInputId = 1;
        return asDirectives(inputs)
            .map(call -> typedInput(call, stagers))
            .toArray(Parameter[]::new);
    }

    private Parameter typedInput(MethodCallExpression call, List<Statement> stagers) {
        var qualifier = call.getMethodAsString();

        if( "each".equals(qualifier) ) {
            var arg = lastArg(call);
            if( arg instanceof VariableExpression ve ) {
                var name = ve.getName();
                var type = specInputs.getOrDefault(name, ClassHelper.dynamicType());
                return param(name, type);
            }
            if( arg instanceof MethodCallExpression mce ) {
                return typedInput(mce, stagers);
            }
            return nextParam(ClassHelper.dynamicType());
        }

        if( "env".equals(qualifier) ) {
            var param = nextParam(ClassHelper.STRING_TYPE);
            var envName = lastArg(call);
            var envValue = constX(param.getName());
            var stager = stmt(callThisX("env", args(envName, envValue)));
            stagers.add(stager);
            return param;
        }

        if( "file".equals(qualifier) || "path".equals(qualifier) ) {
            var type = isFileCollection(call) ? fileCollectionType() : PATH_TYPE;
            var param = param(call, type);
            if( param == null )
                param = nextParam(type);
            var stageName = stageName(call);
            if( stageName != null ) {
                var stageValue = constX(param.getName());
                var stager = stmt(callThisX("stageAs", args(stageName, stageValue)));
                stagers.add(stager);
            }
            return param;
        }

        if( "stdin".equals(qualifier) ) {
            var param = nextParam(ClassHelper.STRING_TYPE);
            var stager = stmt(callThisX("stdin", args(varX(param.getName()))));
            stagers.add(stager);
            return param;
        }

        if( "tuple".equals(qualifier) ) {
            var components = asMethodCallArguments(call).stream()
                .map((arg) -> {
                    if( arg instanceof MethodCallExpression mce )
                        return mce;
                    if( arg instanceof VariableExpression ve )
                        return callThisX(ve.getName(), new ArgumentListExpression());
                    return null;
                })
                .filter(mce -> mce != null)
                .map(mce -> typedInput(mce, stagers))
                .toArray(Parameter[]::new);
            var type = new ClassNode(Tuple.class);
            var genericsTypes = Arrays.stream(components)
                .map(p -> new GenericsType(p.getType()))
                .toArray(GenericsType[]::new);
            type.setGenericsTypes(genericsTypes);
            return new TupleParameter(type, components);
        }

        if( "val".equals(qualifier) ) {
            var name = lastArg(call).getText();
            var type = specInputs.getOrDefault(name, ClassHelper.dynamicType());
            return param(name, type);
        }

        throw new IllegalStateException();
    }

    private static Parameter param(String name, ClassNode type) {
        return new Parameter(type, name);
    }

    private static Parameter param(MethodCallExpression call, ClassNode type) {
        if( lastArg(call) instanceof VariableExpression ve )
            return param(ve.getName(), type);
        else
            return null;
    }

    private static Expression lastArg(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        return args.get(args.size() - 1);
    }

    private Parameter nextParam(ClassNode type) {
        var name = String.format("$in%d", nextInputId++);
        return param(name, type);
    }

    private static Expression stageName(MethodCallExpression call) {
        var namedArgs = namedArgs(call);
        if( namedArgs.containsKey("stageAs") )
            return namedArgs.get("stageAs");
        if( namedArgs.containsKey("name") )
            return namedArgs.get("name");
        var lastArg = lastArg(call);
        return lastArg instanceof VariableExpression ? null : lastArg;
    }

    private static boolean isFileCollection(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            var arity = FileParamASTUtils.arityValue(nale);
            if( arity != null )
                return !"1".equals(arity);
        }
        return stageName(call) instanceof ConstantExpression ce
            && FileParamASTUtils.isGlobPattern(ce);
    }

    private static ClassNode fileCollectionType() {
        var result = new ClassNode(Set.class);
        result.setGenericsTypes(new GenericsType[] {
            new GenericsType(PATH_TYPE)
        });
        return result;
    }

    private int nextOutputId;

    private Statement typedOutputs(Statement outputs, List<Statement> topics) {
        if( outputs.isEmpty() )
            return EmptyStatement.INSTANCE;
        nextOutputId = 1;
        var statements = asDirectives(outputs)
            .map(call -> typedOutput(call, topics))
            .filter(call -> call != null)
            .toList();
        checkExpressionOutput(statements);
        return block(null, statements);
    }

    private void checkExpressionOutput(List<Statement> statements) {
        if( statements.size() != 1 )
            return;
        var es = (ExpressionStatement) statements.get(0);
        if( es.getExpression() instanceof AssignmentExpression ae ) {
            var target = (VariableExpression) ae.getLeftExpression();
            if( !target.getName().startsWith("$out") )
                return;
            var source = ae.getRightExpression();
            es.setExpression(source);
        }
    }

    private static final Token RIGHT_SHIFT = Token.newSymbol(Types.RIGHT_SHIFT, -1, -1);

    private Statement typedOutput(MethodCallExpression call, List<Statement> topics) {
        var namedArgs = namedArgs(call);
        var hasEmit = namedArgs.containsKey("emit");
        var hasTopic = namedArgs.containsKey("topic");
        var optional = namedArgs.get("optional") instanceof ConstantExpression ce && Boolean.TRUE.equals(ce.getValue());
        var outputValue = typedOutputValue(call, optional);
        if( hasTopic ) {
            var topicName = namedArgs.get("topic").getText();
            var topic = stmt(binX(outputValue, RIGHT_SHIFT, stringX(topicName)));
            topics.add(topic);
            if( !hasEmit )
                return null;
        }
        var outputName = hasEmit 
            ? namedArgs.get("emit").getText()
            : String.format("$out%d", nextOutputId++);
        return stmt(new AssignmentExpression(varX(outputName), outputValue));
    }

    private static Map<String,Expression> namedArgs(MethodCallExpression call) {
        var entries = asNamedArgs(call).stream()
            .map((entry) -> {
                var name = entry.getKeyExpression().getText();
                var value = entry.getValueExpression();
                return Map.entry(name, value);
            })
            .toArray(Map.Entry[]::new);
        return Map.ofEntries(entries);
    }

    private static Expression typedOutputValue(MethodCallExpression call, boolean optional) {
        // TODO: preserve named args in output dsl xform, filter out emit/topic/optional after xform
        var arguments = withoutNamedArgs(call);
        var result = callThisX(call.getMethodAsString(), args(arguments));
        result.setSourcePosition(call);
        return new OutputDslTransformer(optional).transform(result);
    }

    private static List<Expression> withoutNamedArgs(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        return args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression
            ? args.subList(1, args.size())
            : args;
    }

    private static Expression stringX(String str) {
        var result = constX(str);
        result.putNodeMetaData(ASTNodeMarker.VERBATIM_TEXT, String.format("'%s'", str));
        return result;
    }

    private static Statement blockOrEmpty(List<Statement> statements) {
        return !statements.isEmpty()
            ? block(null, statements)
            : EmptyStatement.INSTANCE;
    }
}


class ModuleSpecVisitor {

    private static final ClassNode PATH_TYPE = ClassHelper.makeCached(Path.class);

    public Map<String,ClassNode> getInputs(URI uri) {
        try {
            return specInputs(uri);
        }
        catch( Exception e ) {
            System.err.println("Failed to parse module spec (meta.yml): " + e.toString());
            return Collections.emptyMap();
        }
    }

    private static Map<String,ClassNode> specInputs(URI uri) {
        var specPath = Path.of(uri).getParent().resolve("meta.yml");
        if( !Files.exists(specPath) )
            return Collections.emptyMap();
        var spec = (Map<String,?>) loadSpec(specPath);
        var inputs = (List) spec.get("input");
        var result = new HashMap<String,ClassNode>();
        inputs.stream()
            .flatMap(input -> inputEntries(input))
            .forEach((obj) -> {
                var entry = (Map.Entry) obj;
                var name = (String) entry.getKey();
                var inputSpec = (Map<String,?>) entry.getValue();
                var type = inputSpec.get("type") instanceof String s
                    ? inputType(s)
                    : null;
                if( type == null )
                    return;
                result.put(name, type);
            });
        return result;
    }

    private static Object loadSpec(Path specPath) {
        try {
            return new YamlSlurper().parse(specPath);
        }
        catch( IOException e ) {
            System.err.println("Failed to read module spec: " + e.toString());
            return null;
        }
    }

    private static Stream<Map.Entry> inputEntries(Object input) {
        if( input instanceof List inputList )
            return inputList.stream().flatMap(m -> inputEntries(m));
        if( input instanceof Map inputMap )
            return inputMap.entrySet().stream();
        return Stream.empty();
    }

    private static ClassNode inputType(String type) {
        if( "boolean".equals(type) )
            return ClassHelper.Boolean_TYPE;
        if( "file".equals(type) || "directory".equals(type) )
            return PATH_TYPE;
        if( "integer".equals(type) )
            return ClassHelper.Integer_TYPE;
        if( "map".equals(type) )
            return ClassHelper.MAP_TYPE.getPlainNodeReference();
        if( "number".equals(type) )
            return ClassHelper.Float_TYPE;
        if( "string".equals(type) )
            return ClassHelper.STRING_TYPE;
        return ClassHelper.dynamicType();
    }
}


class OutputDslTransformer implements ExpressionTransformer {

    private boolean optional;

    public OutputDslTransformer(boolean optional) {
        this.optional = optional;
    }

    @Override
    public Expression transform(Expression node) {
        if( node == null )
            return null;

        if( node instanceof VariableExpression ve )
            return transformVariableExpression(ve);

        if( node instanceof MethodCallExpression mce )
            return transformMethodCallExpression(mce);

        return node.transformExpression(this);
    }

    private Expression transformVariableExpression(VariableExpression ve) {
        if( "stdout".equals(ve) )
            return callThisX(ve.getName(), new ArgumentListExpression());
        return ve;
    }

    private Expression transformMethodCallExpression(MethodCallExpression mce) {
        if( !mce.isImplicitThis() )
            return mce.transformExpression(this);
        var name = mce.getMethodAsString();
        if( "val".equals(name) )
            return lastArg(mce);
        var arguments = transform(mce.getArguments());
        if( "file".equals(name) || "path".equals(name) ) {
            name = isFileCollection(arguments) ? "files" : "file";
            if( optional )
                addNamedArg(arguments, entryX(constX("optional"), constX(true)));
        }
        var result = callThisX(name, arguments);
        result.setSourcePosition(mce);
        return result;
    }

    public static boolean isFileCollection(Expression arguments) {
        var args = ((TupleExpression) arguments).getExpressions();
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            var arity = FileParamASTUtils.arityValue(nale);
            if( arity != null )
                return !"1".equals(arity);
        }
        return false;
    }

    private static void addNamedArg(Expression arguments, MapEntryExpression entry) {
        var args = ((TupleExpression) arguments).getExpressions();
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            nale.addMapEntryExpression(entry);
        }
        else {
            var namedArgs = new NamedArgumentListExpression();
            namedArgs.addMapEntryExpression(entry);
            args.add(0, namedArgs);
        }
    }

    private static Expression lastArg(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        return args.get(args.size() - 1);
    }
}


class FileParamASTUtils {

    public static String arityValue(NamedArgumentListExpression nale) {
        return nale.getMapEntryExpressions().stream()
            .filter(entry -> (
                entry.getKeyExpression() instanceof ConstantExpression ce && "arity".equals(ce.getValue())
            ))
            .map(entry -> (
                entry.getValueExpression() instanceof ConstantExpression ce && ce.getValue() instanceof String s ? s : null
            ))
            .filter(value -> value != null)
            .findFirst().orElse(null);
    }

    public static boolean isGlobPattern(ConstantExpression ce) {
        return ce.getValue() instanceof String s && (s.contains("*") || s.contains("?"));
    }
}
