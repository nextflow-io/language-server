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
package nextflow.lsp.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import nextflow.script.dsl.Constant;
import nextflow.script.types.TypeChecker;
import nextflow.script.types.TypesEx;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import static nextflow.lsp.ast.CompletionUtils.*;
import static nextflow.script.ast.ASTUtils.*;

/**
 * Helper class for collecting completion proposals.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class CompletionHelper {

    private int maxItems;

    private List<CompletionItem> items = new ArrayList<>();

    private boolean incomplete = false;

    public CompletionHelper(int maxItems) {
        this.maxItems = maxItems;
    }

    public boolean addItem(CompletionItem item) {
        if( items.size() >= maxItems ) {
            incomplete = true;
            return false;
        }
        items.add(item);
        return true;
    }

    public List<CompletionItem> getItems() {
        return items;
    }

    public boolean isIncomplete() {
        return incomplete;
    }

    public void addItemsFromObjectScope(Expression object, String namePrefix) {
        ClassNode cn = TypeChecker.getType(object);
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            var isStatic = object instanceof ClassExpression;

            for( var fn : cn.getFields() ) {
                if( !fn.isPublic() || isStatic != fn.isStatic() )
                    continue;
                if( !addItemForField(fn, namePrefix) )
                    break;
            }

            for( var mn : cn.getMethods() ) {
                if( !mn.isPublic() || isStatic != mn.isStatic() )
                    continue;
                var an = findAnnotation(mn, Constant.class);
                boolean result;
                if( an.isPresent() ) {
                    var name = an.get().getMember("value").getText();
                    result = TypesEx.isNamespace(mn)
                        ? addItemForNamespace(name, mn, namePrefix)
                        : addItemForConstant(name, mn, namePrefix);
                }
                else {
                    result = addItemForMethod(mn, namePrefix);
                }
                if( !result )
                    break;
            }

            cn = superClassOrInterface(cn);
        }
    }

    public void addMethodsFromObjectScope(Expression object, String namePrefix) {
        ClassNode cn = TypeChecker.getType(object);
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            var isStatic = object instanceof ClassExpression;

            for( var mn : cn.getMethods() ) {
                if( !mn.isPublic() || isStatic != mn.isStatic() )
                    continue;
                if( findAnnotation(mn, Constant.class).isPresent() )
                    continue;
                if( !addItemForMethod(mn, namePrefix) )
                    break;
            }

            cn = superClassOrInterface(cn);
        }
    }

    private static ClassNode superClassOrInterface(ClassNode cn) {
        if( cn.getSuperClass() != null )
            return cn.getSuperClass();
        if( cn.getInterfaces().length == 1 )
            return cn.getInterfaces()[0];
        return null;
    }

    private boolean addItemForField(FieldNode fn, String namePrefix) {
        var name = fn.getName();
        if( !name.startsWith(namePrefix) )
            return true;
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Field);
        item.setLabelDetails(astNodeToItemLabelDetails(fn));
        item.setDetail(astNodeToItemDetail(fn));
        item.setDocumentation(astNodeToItemDocumentation(fn));
        return addItem(item);
    }

    private boolean addItemForNamespace(String name, MethodNode mn, String namePrefix) {
        if( !name.startsWith(namePrefix) )
            return true;
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Module);
        item.setLabelDetails(astNodeToItemLabelDetails(mn));
        item.setDetail(astNodeToItemDetail(mn));
        item.setDocumentation(astNodeToItemDocumentation(mn));
        return addItem(item);
    }

    private boolean addItemForConstant(String name, MethodNode mn, String namePrefix) {
        if( !name.startsWith(namePrefix) )
            return true;
        var fn = new FieldNode(name, 0xF, mn.getReturnType(), mn.getDeclaringClass(), null);
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Constant);
        item.setLabelDetails(astNodeToItemLabelDetails(fn));
        item.setDetail(astNodeToItemDetail(fn));
        item.setDocumentation(astNodeToItemDocumentation(mn));
        return addItem(item);
    }

    private boolean addItemForMethod(MethodNode mn, String namePrefix) {
        var name = mn.getName();
        if( !name.startsWith(namePrefix) )
            return true;
        var item = new CompletionItem(mn.getName());
        item.setKind(CompletionItemKind.Function);
        item.setLabelDetails(astNodeToItemLabelDetails(mn));
        item.setDetail(astNodeToItemDetail(mn));
        item.setDocumentation(astNodeToItemDocumentation(mn));
        return addItem(item);
    }

    public void addItemsFromScope(VariableScope scope, String namePrefix) {
        while( scope != null ) {
            addLocalVariables(scope, namePrefix);
            addItemsFromDslScope(scope.getClassScope(), namePrefix);
            scope = scope.getParent();
        }
    }

    private void addLocalVariables(VariableScope scope, String namePrefix) {
        for( var it = scope.getDeclaredVariablesIterator(); it.hasNext(); ) {
            var variable = it.next();
            var name = variable.getName();
            if( !name.startsWith(namePrefix) )
                continue;
            var item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Variable);
            item.setLabelDetails(astNodeToItemLabelDetails(variable));
            if( !addItem(item) )
                break;
        }
    }

    private void addItemsFromDslScope(ClassNode cn, String namePrefix) {
        while( cn != null ) {
            for( var mn : cn.getMethods() ) {
                var an = findAnnotation(mn, Constant.class);
                boolean result;
                if( an.isPresent() ) {
                    var name = an.get().getMember("value").getText();
                    result = TypesEx.isNamespace(mn)
                        ? addItemForNamespace(name, mn, namePrefix)
                        : addItemForConstant(name, mn, namePrefix);
                }
                else {
                    result = addItemForMethod(mn, namePrefix);
                }
                if( !result )
                    break;
            }
            cn = cn.getInterfaces().length > 0
                ? cn.getInterfaces()[0]
                : null;
        }
    }

    public void addTypes(Collection<ClassNode> classNodes, String namePrefix) {
        for( var cn : classNodes ) {
            var name = cn.getNameWithoutPackage();
            if( !name.startsWith(namePrefix) )
                continue;

            var item = new CompletionItem(name);
            item.setKind(astNodeToItemKind(cn));
            item.setDocumentation(astNodeToItemDocumentation(cn));

            if( !addItem(item) )
                break;
        }
    }

}
