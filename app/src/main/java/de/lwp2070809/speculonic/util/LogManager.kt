package de.lwp2070809.speculonic.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, KAGUYA
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val isEasterEgg: Boolean = false
)

object LogManager {
    private const val TAG = "SpeculonicLog"
    private const val MAX_LOGS = 1000
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    @Volatile
    private var minLevel = LogLevel.INFO

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    
    private val isDirty = AtomicBoolean(false)

    private val easterEggs: List<EasterEggGroup> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val jsonText = de.lwp2070809.speculonic.SpeculonicApp.instance.assets.open("easter_eggs.json").bufferedReader().use { it.readText() }
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
            addLogAndPrint(LogLevel.INFO, "${msg.speaker}: ${msg.message}", isEasterEgg = true)
        }
    }

    private fun triggerRandomEasterEgg() {
        val available = easterEggs.filter { it.id != 1 }
        if (available.isEmpty()) return
        val group = available.random()
        group.messages.forEach { msg ->
            addLogAndPrint(LogLevel.INFO, "${msg.speaker}: ${msg.message}", isEasterEgg = true)
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

    fun d(message: String) {
        addLogAndPrint(LogLevel.DEBUG, message)
    }

    fun i(message: String) {
        addLogAndPrint(LogLevel.INFO, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        addLogAndPrint(LogLevel.WARN, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        addLogAndPrint(LogLevel.ERROR, message, throwable)
    }

    private val buffer = java.util.ArrayDeque<LogEntry>(MAX_LOGS)

    @Synchronized
    private fun addLogAndPrint(level: LogLevel, message: String, throwable: Throwable? = null, isEasterEgg: Boolean = false) {
        val effectiveMin = if (minLevel == LogLevel.KAGUYA) LogLevel.INFO.ordinal else minLevel.ordinal
        val effectiveLevel = if (level == LogLevel.KAGUYA) LogLevel.INFO.ordinal else level.ordinal
        if (effectiveLevel < effectiveMin) return

        val msg = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        if (!isEasterEgg) {
            when (level) {
                LogLevel.DEBUG -> Log.d(TAG, msg)
                LogLevel.INFO, LogLevel.KAGUYA -> Log.i(TAG, msg)
                LogLevel.WARN -> Log.w(TAG, msg)
                LogLevel.ERROR -> Log.e(TAG, msg)
            }
        }

        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, level, msg, isEasterEgg)
        
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

    fun getAllLogsText(): String {
        flushIfDirty()
        return _logs.value.joinToString("\n") { 
            if (minLevel == LogLevel.KAGUYA && it.isEasterEgg) {
                "[${it.timestamp}] ${it.message}"
            } else {
                val levelText = if (minLevel == LogLevel.KAGUYA && it.level == LogLevel.INFO) "月見 ヤチヨ" else it.level.name
                "[${it.timestamp}] $levelText: ${it.message}"
            }
        }
    }
}

data class EasterEggMessage(val speaker: String, val message: String)
data class EasterEggGroup(val id: Int, val messages: List<EasterEggMessage>)

