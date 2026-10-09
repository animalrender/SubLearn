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
    /**
     * `HH:MM:SS<sep>mmm` as subtitle files write it: [millisSeparator] is `,` for SRT and `.` for
     * WebVTT. Digits are always ASCII, whatever the device locale, because this goes into files.
     */
    fun formatTimestamp(ms: Long, millisSeparator: Char = '.'): String {
        val clamped = ms.coerceAtLeast(0L)
        val millis = clamped % 1000L
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString {
            append(hours.toString().padStart(2, '0'))
            append(':')
            append(minutes.toString().padStart(2, '0'))
            append(':')
            append(seconds.toString().padStart(2, '0'))
            append(millisSeparator)
            append(millis.toString().padStart(3, '0'))
        }
    }

    fun clamp(value: Long, min: Long, max: Long): Long = if (max < min) min else value.coerceIn(min, max)

    fun clamp(value: Float, min: Float, max: Float): Float = if (max < min) min else value.coerceIn(min, max)
}
