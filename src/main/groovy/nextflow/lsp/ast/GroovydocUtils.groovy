package nextflow.lsp.ast

import groovy.lang.groovydoc.Groovydoc
import groovy.transform.CompileStatic
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode

/**
 * Utility method to extract Groovydoc text from the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class GroovydocUtils {

    static String getDocumentation(ASTNode node) {
        if( node instanceof ProcessNode )
            return groovydocToMarkdownDescription(node.getGroovydoc())
        if( node instanceof WorkflowNode )
            return groovydocToMarkdownDescription(node.getGroovydoc())
        if( node instanceof AnnotatedNode )
            return groovydocToMarkdownDescription(node.getGroovydoc())

        return null
    }

    private static String groovydocToMarkdownDescription(Groovydoc groovydoc) {
        if( groovydoc == null || !groovydoc.isPresent() )
            return null
        final content = groovydoc.getContent()
        final lines = content.split('\n')
        final builder = new StringBuilder()
        final n = lines.length
        // strip end of groovydoc comment
        if( n == 1 ) {
            final c = lines[0].indexOf('*/')
            if( c != -1 )
                lines[0] = lines[0].substring(0, c)
        }
        // strip start of groovydoc coment
        final first = lines[0]
        final lengthToRemove = Math.min(first.length(), 3)
        appendLine(builder, first.substring(lengthToRemove))
        // append lines that start with an asterisk (*)
        for( int i = 1; i < n - 1; i++ ) {
            final line = lines[i]
            final star = line.indexOf('*')
            final at = line.indexOf('@')
            if( at == -1 && star > -1 )
                appendLine(builder, line.substring(star + 1))
        }
        return builder.toString().trim()
    }

    private static void appendLine(StringBuilder builder, String line) {
        line = reformatLine(line)
        if( line.length() == 0 )
            return
        builder.append(line)
        builder.append('\n')
    }

    private static String reformatLine(String line) {
        line = line.replaceAll('<(\\w+)(?:\\s+\\w+(?::\\w+)?=(\"|\')[^\"\']*\\2)*\\s*(\\/{0,1})>', '<$1$3>')
        line = line.replaceAll('<pre>', '\n\n```\n')
        line = line.replaceAll('</pre>', '\n```\n')
        line = line.replaceAll('</?(em|i)>', '_')
        line = line.replaceAll('</?(strong|b)>', '**')
        line = line.replaceAll('</?code>', '`')
        line = line.replaceAll('<hr ?\\/>', '\n\n---\n\n')
        line = line.replaceAll('<(p|ul|ol|dl|li|dt|table|tr|div|blockquote)>', '\n\n')
        line = line.replaceAll('<br\\s*/?>\\s*', '  \n')
        line = line.replaceAll('<\\/{0,1}\\w+\\/{0,1}>', '')
        return line
    }

}
