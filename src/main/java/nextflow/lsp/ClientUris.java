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
package nextflow.lsp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.UnaryOperator;

import com.google.gson.JsonPrimitive;
import nextflow.lsp.engine.Uris;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Conversion of URIs between the client's spelling and the
 * canonical form used by the engine and the providers.
 *
 * Requests are converted to the canonical form, so that providers
 * can compare the request URI with the URIs of AST nodes. Responses
 * are converted back, so that an open document is referred to by
 * the URI that the client opened it with.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class ClientUris {

    private ClientUris() {
    }

    /**
     * Get the canonical form of a client URI, or the URI itself if
     * it is not a file URI.
     *
     * @param uri
     */
    static String canonical(String uri) {
        return Uris.canonical(uri).map(Object::toString).orElse(uri);
    }

    static TextDocumentIdentifier canonical(TextDocumentIdentifier textDocument) {
        return new TextDocumentIdentifier(canonical(textDocument.getUri()));
    }

    static CallHierarchyItem canonical(CallHierarchyItem item) {
        var result = new CallHierarchyItem(item.getName(), item.getKind(), canonical(item.getUri()), item.getRange(), item.getSelectionRange());
        result.setDetail(item.getDetail());
        result.setTags(item.getTags());
        result.setData(item.getData());
        return result;
    }

    // -- responses

    static <T extends Location> List<T> locations(List<T> locations, UnaryOperator<String> clientUri) {
        if( locations != null ) {
            for( var location : locations )
                location.setUri(clientUri.apply(location.getUri()));
        }
        return locations;
    }

    static Either<List<? extends Location>, List<? extends LocationLink>> definition(Either<List<? extends Location>, List<? extends LocationLink>> result, UnaryOperator<String> clientUri) {
        if( result == null )
            return null;
        if( result.isLeft() ) {
            locations(result.getLeft(), clientUri);
        }
        else if( result.getRight() != null ) {
            for( var link : result.getRight() )
                link.setTargetUri(clientUri.apply(link.getTargetUri()));
        }
        return result;
    }

    static WorkspaceEdit edit(WorkspaceEdit edit, UnaryOperator<String> clientUri) {
        if( edit == null || edit.getChanges() == null )
            return edit;
        var changes = new LinkedHashMap<String, List<TextEdit>>();
        edit.getChanges().forEach((uri, edits) -> changes.put(clientUri.apply(uri), edits));
        edit.setChanges(changes);
        return edit;
    }

    static List<CallHierarchyItem> items(List<CallHierarchyItem> items, UnaryOperator<String> clientUri) {
        if( items != null ) {
            for( var item : items )
                item.setUri(clientUri.apply(item.getUri()));
        }
        return items;
    }

    static List<CallHierarchyIncomingCall> incoming(List<CallHierarchyIncomingCall> calls, UnaryOperator<String> clientUri) {
        if( calls != null ) {
            for( var call : calls )
                items(List.of(call.getFrom()), clientUri);
        }
        return calls;
    }

    static List<CallHierarchyOutgoingCall> outgoing(List<CallHierarchyOutgoingCall> calls, UnaryOperator<String> clientUri) {
        if( calls != null ) {
            for( var call : calls )
                items(List.of(call.getTo()), clientUri);
        }
        return calls;
    }

    static <T extends WorkspaceSymbol> List<T> symbols(List<T> symbols, UnaryOperator<String> clientUri) {
        for( var symbol : symbols ) {
            if( symbol.getLocation() != null && symbol.getLocation().isLeft() )
                locations(List.of(symbol.getLocation().getLeft()), clientUri);
        }
        return symbols;
    }

    static <T extends CodeLens> List<T> codeLenses(List<T> lenses, UnaryOperator<String> clientUri) {
        if( lenses != null ) {
            for( var lens : lenses )
                command(lens.getCommand(), clientUri);
        }
        return lenses;
    }

    /**
     * Convert the file URIs among the arguments of a command.
     *
     * @param command
     * @param clientUri
     */
    static Command command(Command command, UnaryOperator<String> clientUri) {
        if( command == null || command.getArguments() == null )
            return command;
        var arguments = command.getArguments().stream()
            .map(argument -> argument instanceof JsonPrimitive json && json.isString() && json.getAsString().startsWith("file:")
                ? new JsonPrimitive(clientUri.apply(json.getAsString()))
                : argument)
            .toList();
        command.setArguments(arguments);
        return command;
    }

}
