package de.lwp2070809.speculonic.util

import de.lwp2070809.speculonic.network.model.LyricsLine
import java.util.Locale


data class LyricLine(
    val timeMs: Long,
    val content: String
)

object LyricsParser {
    private val timeRegex = Regex("\\[\\s*(\\d+):(\\d+)(?:[:.,](\\d+))?\\s*\\]")
    
    fun parse(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val parsedLines = mutableListOf<LyricLine>()

        for (line in lrcContent.lineSequence()) {
            parseLineInto(line, parsedLines)
        }

        return parsedLines.sortedBy { it.timeMs }
    }

    private fun parseLineInto(line: String, out: MutableList<LyricLine>) {
        val matches = timeRegex.findAll(line)
        val content = line.replace(timeRegex, "").trim()
        if (content.isEmpty()) return

        for (match in matches) {
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fractionMs = parseFractionMs(match.groupValues[3])
            val timeMs = (min * SECONDS_PER_MINUTE * MILLIS_PER_SECOND) + (sec * MILLIS_PER_SECOND) + fractionMs
            out.add(LyricLine(timeMs, content))
        }
    }

    private fun parseFractionMs(fractionStr: String): Long {
        return fractionStr.take(FRACTION_MAX_DIGITS).padEnd(FRACTION_MAX_DIGITS, '0').toLong()
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val MILLIS_PER_SECOND = 1000L
    private const val FRACTION_MAX_DIGITS = 3

    
    fun fromStructured(lines: List<LyricsLine>, offset: Int = 0): List<LyricLine> {
        return lines.mapNotNull { line ->
            line.start?.let { LyricLine(timeMs = (it - offset).coerceAtLeast(0L), content = line.value) }
        }.sortedBy { it.timeMs }
    }

    
    fun toLrcString(lines: List<LyricLine>): String {
        return lines.joinToString("\n") { line ->
            val totalSeconds = line.timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val milliseconds = line.timeMs % MILLIS_PER_SECOND
            "[%02d:%02d.%03d]%s".format(Locale.ROOT, minutes, seconds, milliseconds, line.content)
        }
    }
}
