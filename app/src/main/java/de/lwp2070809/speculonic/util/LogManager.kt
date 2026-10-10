package de.lwp2070809.speculonic.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class LogLevel(val priority: Int) {
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    KAGUYA(2);

    /**
     * 是否作为常规日志过滤级别对外展示（KAGUYA 本质上是 INFO 级别彩蛋，不作为独立筛选级别）
     */
    val isFilterable: Boolean
        get() = this != KAGUYA
}

object LogTag {
    const val ALL = "ALL"
    const val APP = "App"
    const val PLAYBACK = "Playback"
    const val NETWORK = "Network"
    const val SYNC = "Sync"
    const val CACHE = "Cache"

    /**
     * 将细分模块前缀归一化为核心业务大类标签
     */
    fun resolve(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.equals(PLAYBACK, ignoreCase = true) || trimmed.startsWith("Playback", ignoreCase = true) ||
                    trimmed.startsWith("Audio", ignoreCase = true) || trimmed.startsWith("Car", ignoreCase = true) ||
                    trimmed.startsWith("Bluetooth", ignoreCase = true) -> PLAYBACK

            trimmed.equals(NETWORK, ignoreCase = true) || trimmed.startsWith("SonicLog", ignoreCase = true) ||
                    trimmed.startsWith("Network", ignoreCase = true) || trimmed.startsWith("ServerReachable", ignoreCase = true) ||
                    trimmed.startsWith("Offline", ignoreCase = true) -> NETWORK

            trimmed.equals(SYNC, ignoreCase = true) || trimmed.startsWith("Sync", ignoreCase = true) ||
                    trimmed.startsWith("CoverArtSync", ignoreCase = true) || trimmed.startsWith("MetadataSync", ignoreCase = true) -> SYNC

            trimmed.equals(CACHE, ignoreCase = true) || trimmed.startsWith("Cache", ignoreCase = true) ||
                    trimmed.startsWith("Download", ignoreCase = true) || trimmed.startsWith("LocalFallback", ignoreCase = true) -> CACHE

            else -> APP
        }
    }
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String = LogTag.APP,
    val message: String,
    val isEasterEgg: Boolean = false
)

object LogManager {
    private const val TAG = "SpeculonicLog"
    private const val MAX_LOGS = 2000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    @Volatile
    private var minLevel = LogLevel.INFO

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val isDirty = AtomicBoolean(false)

    @Volatile
    private var isCrashHandlerInstalled = false

    private val easterEggs: List<EasterEggGroup> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val jsonText = de.lwp2070809.speculonic.SpeculonicApp.instance
                .assets.open("easter_eggs.json")
                .bufferedReader()
                .use { it.readText() }
            val array = org.json.JSONArray(jsonText)
            val list = mutableListOf<EasterEggGroup>()
            for (i in 0 until array.length()) {
                val groupObj = array.getJSONObject(i)
                val id = groupObj.getInt("id")
                val messagesObj = groupObj.getJSONObject("messages")
                val lang = if (Locale.getDefault().language == "zh") "zh" else "en"
                val messagesArray = if (messagesObj.has(lang)) messagesObj.getJSONArray(lang) else messagesObj.getJSONArray("en")
                val msgList = mutableListOf<EasterEggMessage>()
                for (j in 0 until messagesArray.length()) {
                    val msgObj = messagesArray.getJSONObject(j)
                    msgList.add(EasterEggMessage(msgObj.getString("speaker"), msgObj.getString("message")))
                }
                list.add(EasterEggGroup(id, msgList))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun triggerEasterEggGroup(id: Int) {
        val group = easterEggs.find { it.id == id } ?: return
        group.messages.forEach { msg ->
            addLogAndPrint(LogLevel.INFO, LogTag.APP, "${msg.speaker}: ${msg.message}", isEasterEgg = true)
        }
    }

    private fun triggerRandomEasterEgg() {
        val available = easterEggs.filter { it.id != 1 }
        if (available.isEmpty()) return
        val group = available.random()
        group.messages.forEach { msg ->
            addLogAndPrint(LogLevel.INFO, LogTag.APP, "${msg.speaker}: ${msg.message}", isEasterEgg = true)
        }
    }

    @Synchronized
    fun setMinLevel(level: LogLevel) {
        val wasKaguya = minLevel == LogLevel.KAGUYA
        minLevel = level
        if (level == LogLevel.KAGUYA && !wasKaguya) {
            triggerEasterEggGroup(1)
        }
    }

    /**
     * 注册未捕获异常崩溃日志落盘持久化
     */
    fun setupCrashHandler(context: Context) {
        if (isCrashHandlerInstalled) return
        isCrashHandlerInstalled = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashDump(appContext, thread, throwable)
            } catch (_: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveCrashDump(context: Context, thread: Thread, throwable: Throwable) {
        val crashFile = File(context.cacheDir, "crash_dump.txt")
        val sb = StringBuilder()
        sb.append("=== Speculonic Crash Report ===\n")
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())).append("\n")
        sb.append("Thread: ").append(thread.name).append(" (ID: ").append(thread.id).append(")\n")
        sb.append("Exception: ").append(Log.getStackTraceString(throwable)).append("\n\n")
        sb.append("=== Recent In-Memory Logs (Last 200 entries) ===\n")
        val recentLogs: List<LogEntry>
        synchronized(this) {
            recentLogs = buffer.toList().takeLast(200)
        }
        recentLogs.forEach { entry ->
            sb.append(formatLogEntry(entry, isKaguya = false)).append("\n")
        }
        crashFile.writeText(sb.toString())
    }

    /**
     * 导出完整日志至缓存目录文件，若存在崩溃记录则附加在顶部
     */
    fun exportLogsToFile(context: Context): File {
        flushIfDirty()
        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(logsDir, "speculonic_logs_$timeStr.txt")

        val sb = StringBuilder()
        sb.append("=== Speculonic Diagnostic Logs ===\n")
        sb.append("Exported At: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())).append("\n\n")

        val crashFile = File(context.cacheDir, "crash_dump.txt")
        if (crashFile.exists()) {
            try {
                sb.append(">>> DETECTED LAST CRASH RECORD <<<\n")
                sb.append(crashFile.readText()).append("\n")
                sb.append(">>> END CRASH RECORD <<<\n\n")
            } catch (_: Exception) {}
        }

        sb.append("=== Complete Log Buffer (${_logs.value.size} items) ===\n")
        _logs.value.forEach { entry ->
            sb.append(formatLogEntry(entry, isKaguya = false)).append("\n")
        }
        exportFile.writeText(sb.toString())
        return exportFile
    }

    fun d(message: String) = d(LogTag.APP, message)
    fun d(tag: String, message: String) {
        val (finalTag, finalMsg) = parseTagAndMessage(tag, message)
        addLogAndPrint(LogLevel.DEBUG, finalTag, finalMsg)
    }

    fun i(message: String) = i(LogTag.APP, message)
    fun i(tag: String, message: String) {
        val (finalTag, finalMsg) = parseTagAndMessage(tag, message)
        addLogAndPrint(LogLevel.INFO, finalTag, finalMsg)
    }

    fun w(message: String, throwable: Throwable? = null) = w(LogTag.APP, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val (finalTag, finalMsg) = parseTagAndMessage(tag, message)
        addLogAndPrint(LogLevel.WARN, finalTag, finalMsg, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) = e(LogTag.APP, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val (finalTag, finalMsg) = parseTagAndMessage(tag, message)
        addLogAndPrint(LogLevel.ERROR, finalTag, finalMsg, throwable)
    }

    private val tagRegex = Regex("^([A-Za-z0-9_]+):\\s*(.*)", RegexOption.DOT_MATCHES_ALL)

    private fun parseTagAndMessage(tag: String, message: String): Pair<String, String> {
        if (tag != LogTag.APP) {
            return LogTag.resolve(tag) to message
        }
        val match = tagRegex.matchEntire(message)
        return if (match != null) {
            val rawPrefix = match.groupValues[1]
            val content = match.groupValues[2]
            LogTag.resolve(rawPrefix) to "$rawPrefix: $content"
        } else {
            LogTag.APP to message
        }
    }

    private val buffer = java.util.ArrayDeque<LogEntry>(MAX_LOGS)

    @Synchronized
    private fun addLogAndPrint(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        isEasterEgg: Boolean = false
    ) {
        if (level.priority < minLevel.priority) return

        val msg = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        if (!isEasterEgg) {
            val logcatTag = "$TAG/$tag"
            when (level) {
                LogLevel.DEBUG -> Log.d(logcatTag, msg)
                LogLevel.INFO, LogLevel.KAGUYA -> Log.i(logcatTag, msg)
                LogLevel.WARN -> Log.w(logcatTag, msg)
                LogLevel.ERROR -> Log.e(logcatTag, msg)
            }
        }

        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, level, tag, msg, isEasterEgg)

        if (buffer.size >= MAX_LOGS) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
        isDirty.set(true)

        if (!isEasterEgg && minLevel == LogLevel.KAGUYA && Math.random() < 0.2) {
            triggerRandomEasterEgg()
        }
    }

    @Synchronized
    fun flushIfDirty() {
        if (isDirty.compareAndSet(true, false)) {
            _logs.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(this) {
            buffer.clear()
            isDirty.set(false)
        }
        _logs.value = emptyList()
    }

    fun formatLogEntry(entry: LogEntry, isKaguya: Boolean = minLevel == LogLevel.KAGUYA): String {
        return if (isKaguya && entry.isEasterEgg) {
            "[${entry.timestamp}] ${entry.message}"
        } else {
            val levelText = if (isKaguya && entry.level == LogLevel.INFO) "月見 ヤチヨ" else entry.level.name
            "[${entry.timestamp}] [${entry.tag}] $levelText: ${entry.message}"
        }
    }

    fun getAllLogsText(): String {
        flushIfDirty()
        return _logs.value.joinToString("\n") { formatLogEntry(it) }
    }
}

data class EasterEggMessage(val speaker: String, val message: String)
data class EasterEggGroup(val id: Int, val messages: List<EasterEggMessage>)
