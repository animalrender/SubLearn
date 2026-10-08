package com.sublearn.core.common

/**
 * Tiny logging seam. Android implementations map to `android.util.Log`; unit tests use
 * [RecordingLogger]. Nothing in SubLearn may log raw API keys or subtitle text with PII concerns.
 */
interface SubLearnLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null)

    companion object {
        val redactedKeys = setOf("x-api-key", "authorization", "api_key", "apikey", "key")

        /** Removes anything that looks like a secret before a message reaches a log sink. */
        fun redact(message: String): String {
            var out = message
            out = Regex("""(?i)("(?:api[_-]?key|key|token|authorization)"\s*:\s*")[^"]+(")""")
                .replace(out) { m -> m.groupValues[1] + "***" + m.groupValues[2] }
            out = Regex("""(?i)\b(AIza[0-9A-Za-z_\-]{20,}|sk-[0-9A-Za-z]{16,}|sk-ant-[0-9A-Za-z]{16,})\b""")
                .replace(out, "***")
            out = Regex("""(?i)(key=)[^&\s]+""").replace(out) { m -> m.groupValues[1] + "***" }
            return out
        }
    }
}

class RecordingLogger : SubLearnLogger {
    data class Entry(val level: SubLearnLogger.Level, val tag: String, val message: String)

    val entries = mutableListOf<Entry>()

    override fun log(level: SubLearnLogger.Level, tag: String, message: String, throwable: Throwable?) {
        entries.add(Entry(level, tag, SubLearnLogger.redact(message)))
    }
}
