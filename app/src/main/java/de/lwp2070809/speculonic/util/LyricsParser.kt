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
            val matches = timeRegex.findAll(line)
            if (matches.none()) continue
            
            val content = line.replace(timeRegex, "").trim()
            if (content.isEmpty()) continue

            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionStr = match.groupValues[3]
                val fraction = fractionStr.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
                
                val fractionMs = when (fractionStr.length) {
                    1 -> fraction * 100
                    2 -> fraction * 10
                    3 -> fraction
                    else -> if (fractionStr.length > 3) fractionStr.substring(0, 3).toLong() else 0L
                }
                
                val timeMs = (min * 60 * 1000) + (sec * 1000) + fractionMs
                parsedLines.add(LyricLine(timeMs, content))
            }
        }

        return parsedLines.sortedBy { it.timeMs }
    }

    
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
