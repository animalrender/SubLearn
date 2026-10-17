package com.sublearn.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * A snapshot of what this process and the device are using right now.
 *
 * Three numbers matter when a user asks "how heavy is this profile": the JVM heap (lexicon
 * caches, parsed subtitles, Kotlin embeddings), the native heap (ONNX sessions, LiteRT weights,
 * SQLite page caches) and the process's PSS, which is what the system kills on. The device totals
 * put them in proportion.
 *
 * @property heapUsedBytes JVM heap in use
 * @property heapMaxBytes the heap limit the runtime will grow to
 * @property nativeHeapBytes native allocations of this process
 * @property pssKb proportional set size of this process, in kilobytes
 * @property deviceAvailableRamMb memory the system reports as available
 * @property deviceTotalRamMb the device's total memory
 * @property lowMemory true when the system considers itself low on memory
 */
data class ProcessResources(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nativeHeapBytes: Long,
    val pssKb: Long,
    val deviceAvailableRamMb: Long,
    val deviceTotalRamMb: Long,
    val lowMemory: Boolean,
)

/**
 * Reads [ProcessResources] from the runtime and the platform.
 *
 * Reading PSS walks `/proc`, so it costs a few milliseconds; callers take a snapshot after an
 * analysis step, not on every frame.
 *
 * @property context the application context
 */
class ProcessResourceReader(private val context: Context) {

    fun read(): ProcessResources {
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        return ProcessResources(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            pssKb = Debug.getPss(),
            deviceAvailableRamMb = memoryInfo.availMem / MB,
            deviceTotalRamMb = memoryInfo.totalMem / MB,
            lowMemory = memoryInfo.lowMemory,
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
