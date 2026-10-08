package com.sublearn.core.common

/** Time helpers shared by subtitle parsing, player UI and shadowing math. */
object TimeUtils {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L

    /** `m:ss` under an hour, `h:mm:ss` above. Never negative. */
    fun formatClock(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            buildString {
                append(hours)
                append(':')
                append(minutes.toString().padStart(2, '0'))
                append(':')
                append(seconds.toString().padStart(2, '0'))
            }
        } else {
            buildString {
                append(minutes)
                append(':')
                append(seconds.toString().padStart(2, '0'))
            }
        }
    }

    /** `h:mm:ss.mmm` used by subtitle files and AI context headers. */
    fun formatTimestamp(ms: Long, separator: Char = ':'): String {
        val clamped = ms.coerceAtLeast(0L)
        val millis = clamped % 1000L
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis).replace(':', separator)
    }

    fun clamp(value: Long, min: Long, max: Long): Long = if (max < min) min else value.coerceIn(min, max)

    fun clamp(value: Float, min: Float, max: Float): Float = if (max < min) min else value.coerceIn(min, max)
}
