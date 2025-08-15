package io.nextflow.intellij;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating Nextflow Language Server connections in IntelliJ IDEA.
 */
public class NextflowLanguageServerFactory implements LanguageServerFactory {
    
    @Override
    @NotNull
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new NextflowStreamConnectionProvider(project);
    }
    
    @Override
    @NotNull
    public LanguageClientImpl createLanguageClient(@NotNull Project project) {
        return new LanguageClientImpl(project);
    }
}