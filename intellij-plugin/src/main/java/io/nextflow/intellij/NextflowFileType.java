package io.nextflow.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyLanguage;

import javax.swing.*;

/**
 * File type for Nextflow script files (.nf)
 */
public class NextflowFileType extends LanguageFileType {
    
    public static final NextflowFileType INSTANCE = new NextflowFileType();
    
    private NextflowFileType() {
        super(GroovyLanguage.INSTANCE);
    }
    
    @NotNull
    @Override
    public String getName() {
        return "Nextflow";
    }
    
    @NotNull
    @Override
    public String getDescription() {
        return "Nextflow workflow script";
    }
    
    @NotNull
    @Override
    public String getDefaultExtension() {
        return "nf";
    }
    
    @Nullable
    @Override
    public Icon getIcon() {
        return null; // Use default Groovy icon
    }
}