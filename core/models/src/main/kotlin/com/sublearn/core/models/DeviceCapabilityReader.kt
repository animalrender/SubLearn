package com.sublearn.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import com.sublearn.core.ai.DeviceCapabilities

/**
 * What the device can do, read from the platform.
 *
 * The accuracy profiles are a promise about quality, so the app has to know what the phone can
 * actually do: how much memory it has, how many cores it has, whether it is saving battery and
 * how much storage is free.
 *
 * The app's own heap says none of that. A phone with 8 GB of RAM often gives the app 256 MB, so
 * a guard that compared the profile's floor with the free heap downgraded every profile to the
 * lowest one and the profiles never ran at all (D-81).
 *
 * @property context the application context; the reader only reads from it
 */
class DeviceCapabilityReader(private val context: Context) {

    /** A snapshot of the device, taken now. */
    fun read(): DeviceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batterySaver = powerManager?.isPowerSaveMode ?: false
        return DeviceCapabilities(
            totalRamMb = totalRamMb,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            batterySaver = batterySaver,
            freeStorageMb = freeStorageMb(),
        )
    }

    /** Free space on the volume the app's own files live on. */
    private fun freeStorageMb(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        } catch (t: Throwable) {
            0L
        }
    }
}
