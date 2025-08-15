package io.nextflow.intellij;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * Stream connection provider for Nextflow Language Server.
 * Handles starting and managing the language server process.
 */
public class NextflowStreamConnectionProvider extends ProcessStreamConnectionProvider {
    
    public NextflowStreamConnectionProvider(@NotNull Project project) {
        super(getCommand());
    }
    
    /**
     * Gets the command to start the Nextflow Language Server.
     */
    private static List<String> getCommand() {
        return Arrays.asList(
            "java",
            "-jar",
            getLanguageServerPath()
        );
    }
    
    /**
     * Gets the path to the Nextflow language server JAR.
     * Checks multiple common installation locations.
     */
    private static String getLanguageServerPath() {
        // Try common installation locations
        String[] possiblePaths = {
            System.getProperty("user.home") + "/.local/share/nextflow/language-server/language-server-all.jar",
            "/usr/local/share/nextflow/language-server/language-server-all.jar",
            "/opt/nextflow/language-server/language-server-all.jar",
            "language-server-all.jar"  // Current directory or PATH
        };
        
        for (String path : possiblePaths) {
            if (new java.io.File(path).exists()) {
                return path;
            }
        }
        
        // Default fallback - user should configure this
        return System.getProperty("user.home") + "/.local/share/nextflow/language-server/language-server-all.jar";
    }
}