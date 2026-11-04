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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Local plugin registry that serves the fixtures under
 * `src/test/resources/registry`, so that tests don't depend
 * on the public registry.
 *
 * A request for `v1/plugins/<name>/<version>` is served from
 * `registry/v1/plugins/<name>/<version>.json`, or returns 404
 * if there is no such fixture.
 */
public class TestPluginRegistry {

    private static String url;

    public static synchronized String url() {
        if( url == null )
            url = start();
        return url;
    }

    private static String start() {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/", TestPluginRegistry::handle);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
    }

    private static void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath().substring("/api/".length());
        try( var resource = TestPluginRegistry.class.getResourceAsStream("/registry/" + path + ".json") ) {
            if( resource == null ) {
                exchange.sendResponseHeaders(404, -1);
            }
            else {
                var body = resource.readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }
        exchange.close();
    }

}
