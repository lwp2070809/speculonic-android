package de.lwp2070809.speculonic.playback

enum class SleepTimerMode {
    OFF,
    TIME,
    SONG_COUNT,
    END_OF_PLAYLIST;

    companion object {
        fun fromName(raw: String?): SleepTimerMode {
            if (raw.isNullOrBlank()) return OFF
            return entries.firstOrNull { it.name == raw } ?: OFF
        }
    }
}
