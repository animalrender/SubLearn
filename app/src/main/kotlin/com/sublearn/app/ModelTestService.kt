package com.sublearn.app

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.app.ActivityManager
import com.sublearn.core.ai.ModelSelfTestReport
import com.sublearn.core.ai.NlpModelCatalog
import com.sublearn.core.ai.NlpModelSelfTest
import org.koin.core.context.GlobalContext
import java.util.concurrent.Executors

/** Native diagnostics live in a disposable process: SIGABRT cannot be caught by Kotlin. */
class ModelTestService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        val modelId = request.data.getString("modelId") ?: return@Handler true
        val reply = request.replyTo ?: return@Handler true
        executor.execute {
            val report = try {
                val memory = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                val required = (NlpModelCatalog.byId[modelId]?.minRamMb ?: 0).toLong() * 1024 * 1024
                if (memory.lowMemory || memory.availMem - memory.threshold < required) {
                    testFailure(modelId, "Not enough free RAM to test this model. Close other apps and try again.")
                } else {
                    GlobalContext.get().get<NlpModelSelfTest>().run(modelId)
                }
            } catch (error: Throwable) {
                testFailure(modelId, "Model test failed: ${error.message ?: error.javaClass.simpleName}")
            }
            runCatching { reply.send(Message.obtain().apply { data = report.toBundle() }) }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        // Release all native allocations, including those left by a failed load. This process
        // hosts only this bound diagnostic service, never the activity or playback.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

internal fun testFailure(modelId: String, reason: String) = ModelSelfTestReport(
    modelId, false, null, 0L, 0L, 0L, reason,
)

private fun ModelSelfTestReport.toBundle() = Bundle().apply {
    putString("modelId", modelId)
    putBoolean("ok", ok)
    putString("runtimeName", runtimeName)
    putLong("loadMs", loadMs)
    putLong("inferenceMs", inferenceMs)
    putLong("heapDeltaBytes", heapDeltaBytes)
    putString("detail", detail)
}

internal fun Bundle.testReport() = ModelSelfTestReport(
    getString("modelId").orEmpty(), getBoolean("ok"), getString("runtimeName"),
    getLong("loadMs"), getLong("inferenceMs"), getLong("heapDeltaBytes"), getString("detail").orEmpty(),
)
