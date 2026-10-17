package com.sublearn.app

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.sublearn.core.ai.ModelSelfTestReport
import com.sublearn.core.ai.NlpModelCatalog
import com.sublearn.core.ai.NlpModelSelfTest
import com.sublearn.core.ai.SelfTestStage
import org.koin.core.context.GlobalContext
import java.util.concurrent.Executors

/**
 * Native diagnostics live in a disposable process: SIGABRT cannot be caught by Kotlin.
 *
 * Before each step of a test the service tells the caller which step it is starting (D-91). When
 * the process dies the caller gets no reason of its own, so that last announcement is what says
 * whether the model died while the engine was opening it or while it was answering.
 */
class ModelTestService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        val modelId = request.data.getString(KEY_MODEL_ID) ?: return@Handler true
        val reply = request.replyTo ?: return@Handler true
        executor.execute {
            Log.i(TAG, "model $modelId: test started in process ${Process.myPid()}")
            val report = try {
                val memory = ActivityManager.MemoryInfo()
                val manager = getSystemService(ActivityManager::class.java) ?: error("Device memory service unavailable")
                manager.getMemoryInfo(memory)
                val required = (NlpModelCatalog.byId[modelId]?.minRamMb ?: 0).toLong() * 1024 * 1024
                if (memory.lowMemory || memory.availMem - memory.threshold < required) {
                    testFailure(modelId, "Not enough free RAM to test this model. Close other apps and try again.")
                } else {
                    GlobalContext.get().get<NlpModelSelfTest>().run(modelId) { stage ->
                        Log.i(TAG, "model $modelId: ${stage.name}")
                        announce(reply, stage)
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "model $modelId: test threw ${error.javaClass.name}", error)
                testFailure(modelId, "Model test failed: ${error.message ?: error.javaClass.simpleName}")
            }
            Log.i(TAG, "model $modelId: finished, ok=${report.ok}")
            runCatching { reply.send(Message.obtain().apply { data = report.toBundle() }) }
        }
        true
    })

    /** Tells the caller which step has started. A dead caller simply makes this a no-op. */
    private fun announce(reply: Messenger, stage: SelfTestStage) {
        runCatching {
            reply.send(Message.obtain().apply {
                what = WHAT_STAGE
                data = Bundle().apply { putString(KEY_STAGE, stage.name) }
            })
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        // Release all native allocations, including those left by a failed load. This process
        // hosts only this bound diagnostic service, never the activity or playback.
        Process.killProcess(Process.myPid())
    }

    companion object {
        /** Message type of a stage announcement; any other message carries the final report. */
        const val WHAT_STAGE = 1
        const val KEY_MODEL_ID = "modelId"
        const val KEY_STAGE = "stage"
        private const val TAG = "ModelTest"
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
