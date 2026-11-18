package de.lwp2070809.speculonic.util

import de.lwp2070809.speculonic.network.model.LyricsLine


data class LyricLine(
    val timeMs: Long,
    val content: String
)

object LyricsParser {
    private val timeRegex = Regex("\\[\\s*(\\d+):(\\d+)(?:[:.,](\\d+))?\\s*\\]")
    
    fun parse(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val lines = lrcContent.split(Regex("\\r?\\n"))
        val parsedLines = mutableListOf<LyricLine>()

        for (line in lines) {
            parseLineInto(line, parsedLines)
        }

        return parsedLines.sortedBy { it.timeMs }
    }

    private fun parseLineInto(line: String, out: MutableList<LyricLine>) {
        val matches = timeRegex.findAll(line)
        val content = line.replace(timeRegex, "").trim()
        if (matches.none() || content.isEmpty()) return

        for (match in matches) {
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fractionMs = parseFractionMs(match.groupValues[3])
            val timeMs = (min * SECONDS_PER_MINUTE * MILLIS_PER_SECOND) + (sec * MILLIS_PER_SECOND) + fractionMs
            out.add(LyricLine(timeMs, content))
        }
    }

    private fun parseFractionMs(fractionStr: String): Long {
        val fraction = fractionStr.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        return when (fractionStr.length) {
            1 -> fraction * HUNDRED_MS
            2 -> fraction * TEN_MS
            FRACTION_MAX_DIGITS -> fraction
            else -> if (fractionStr.length > FRACTION_MAX_DIGITS) {
                fractionStr.substring(0, FRACTION_MAX_DIGITS).toLong()
            } else {
                0L
            }
        }
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val MILLIS_PER_SECOND = 1000L
    private const val HUNDRED_MS = 100L
    private const val TEN_MS = 10L
    private const val FRACTION_MAX_DIGITS = 3

    
    fun fromStructured(lines: List<LyricsLine>): List<LyricLine> {
        return lines.map { 
            LyricLine(timeMs = it.start ?: 0L, content = it.value)
        }.sortedBy { it.timeMs }
    }

    
    fun toLrcString(lines: List<LyricLine>): String {
        return lines.joinToString("\n") { line ->
            val totalSeconds = line.timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hundredths = (line.timeMs % 1000) / 10
            "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.content)
        }
    }
}
