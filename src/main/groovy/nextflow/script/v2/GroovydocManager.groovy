/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package nextflow.script.v2

import groovy.lang.groovydoc.Groovydoc
import groovy.lang.groovydoc.GroovydocHolder
import nextflow.antlr.ScriptParser
import org.antlr.v4.runtime.ParserRuleContext
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode

import java.util.regex.Pattern

import static org.apache.groovy.parser.antlr4.util.StringUtils.matches

/**
 * Extract Groovydoc comments from source code and
 * add it as metadata to AST nodes.
 */
class GroovydocManager {

    private static final String GROOVYDOC_PREFIX = '/**'
    private static final Pattern SPACES_PATTERN = Pattern.compile('\\s+')

    private final boolean groovydocEnabled

    GroovydocManager(boolean groovydocEnabled) {
        this.groovydocEnabled = groovydocEnabled
    }

    /**
     * Attach doc comment to member node as meta data
     *
     */
    void handle(ASTNode node, ParserRuleContext ctx) {
        if( !groovydocEnabled )
            return
        if( !node || !ctx )
            return
        if( node !instanceof GroovydocHolder )
            return

        final docCommentNodeText = findDocCommentByNode(ctx)
        if( !docCommentNodeText )
            return

        node.putNodeMetaData(GroovydocHolder.DOC_COMMENT, new Groovydoc(docCommentNodeText, (GroovydocHolder) node))
    }

    private String findDocCommentByNode(ParserRuleContext ctx) {
        final parent = ctx.parent
        if( !parent )
            return null

        String docCommentNodeText = null
        boolean sameTypeNodeBefore = false
        for( final child : parent.children ) {
            if( child == ctx ) {
                // if no doc comment ctx found and no siblings of same type before the ctx,
                // try to find doc comment ctx of its parent
                if( !docCommentNodeText && !sameTypeNodeBefore )
                    return findDocCommentByNode(parent)
                else
                    return docCommentNodeText
            }

            if( child.class == ctx.class ) {
                docCommentNodeText = null
                sameTypeNodeBefore = true
                continue
            }

            if( child !instanceof ScriptParser.NlsContext && child !instanceof ScriptParser.SepContext )
                continue

            // doc comments are treated as NL tokens
            final newlines = child instanceof ScriptParser.NlsContext
                ? ((ScriptParser.NlsContext) child).NL()
                : ((ScriptParser.SepContext) child).NL()

            if( !newlines )
                continue

            for( int i = newlines.size() - 1; i >= 0; i-- ) {
                final text = newlines[i].text
                if( matches(text, SPACES_PATTERN) )
                    continue
                docCommentNodeText = text.startsWith(GROOVYDOC_PREFIX)
                    ? text
                    : null
                break
            }
        }

        throw new GroovyBugError("Groovydoc context can not be found: ${ctx.text}")
    }
}