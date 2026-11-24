package de.lwp2070809.speculonic

import de.lwp2070809.speculonic.data.normalizeReleaseNotes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChangelogTest {
    @Test
    fun separatorsEndListAndQuoteAcrossReleaseNoteLineEndings() {
        val lines = listOf("1. Changes", "", "---", "", "> [!IMPORTANT]", "> Ends support", "", "---", "", "> Download instructions")
        val expected = listOf(
            MarkdownElementTypes.ORDERED_LIST,
            MarkdownTokenTypes.HORIZONTAL_RULE,
            GFMElementTypes.ALERT,
            MarkdownTokenTypes.HORIZONTAL_RULE,
            MarkdownElementTypes.BLOCK_QUOTE
        )
        val parser = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
        for (lineEnding in listOf("\r\n", "\r", "\n")) {
            val markdown: CharSequence = normalizeReleaseNotes(lines.joinToString(lineEnding))
            val root = parser.buildMarkdownTreeFromString(markdown)
            assertEquals(expected, root.children.map { it.type }.filterNot { it == MarkdownTokenTypes.EOL })
        }
    }
}
