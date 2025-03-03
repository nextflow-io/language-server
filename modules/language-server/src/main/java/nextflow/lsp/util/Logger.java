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
package nextflow.lsp.util;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Log messages to the client using the language server protocol.
 *
 * NOTE: The logger must be used instead of printing directly
 * to standard output (i.e. System.out), because the language
 * server itself uses standard output to send protocol messages.
 * Printing directly to standard output will cause client errors.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class Logger {

    private static Logger instance;
    private static boolean debugEnabled;

    private LanguageClient client;
    private boolean initialized;

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean value) {
        debugEnabled = value;
    }

    private Logger() {
    }

    public void initialize(LanguageClient client) {
        if( !initialized )
            this.client = client;
        initialized = true;
    }

    public static Logger getInstance() {
        if( instance == null )
            instance = new Logger();
        return instance;
    }

    public void debug(String message) {
        if( !initialized || !isDebugEnabled() )
            return;
        client.logMessage(new MessageParams(MessageType.Log, message));
    }

    public void error(String message) {
        if( !initialized )
            return;
        client.logMessage(new MessageParams(MessageType.Error, message));
    }

    public void showError(String message) {
        if( !initialized )
            return;
        client.showMessage(new MessageParams(MessageType.Error, message));
    }

    public void info(String message) {
        if( !initialized )
            return;
        client.logMessage(new MessageParams(MessageType.Info, message));
    }

}
