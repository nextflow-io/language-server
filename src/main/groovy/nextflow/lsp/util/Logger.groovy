package nextflow.lsp.util

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient

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
@CompileStatic
public class Logger {

    private static Logger instance
    private static Boolean debugEnabled

    private LanguageClient client
    private boolean initialized

    static boolean isDebugEnabled() {
        if( debugEnabled == null )
            debugEnabled = System.getenv('NXF_DEBUG')=='true'
        return debugEnabled
    }

    private Logger() {
    }

    public void initialize(LanguageClient languageClient) {
        if( !initialized )
            this.client = languageClient
        initialized = true
    }

    public static Logger getInstance() {
        if( instance == null )
            instance = new Logger()
        return instance
    }

    public void debug(String message) {
        if( !initialized || !debugEnabled )
            return
        client.logMessage(new MessageParams(MessageType.Log, message))
    }

    public void error(String message) {
        if( !initialized )
            return
        client.logMessage(new MessageParams(MessageType.Error, message))
    }

    public void info(String message) {
        if( !initialized )
            return
        client.logMessage(new MessageParams(MessageType.Info, message))
    }

}
