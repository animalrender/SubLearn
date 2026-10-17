package com.sublearn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.sublearn.core.ai.ModelSelfTestReport
import com.sublearn.core.ai.ModelTestRunner
import com.sublearn.core.ai.SelfTestStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Turns service death (native abort/OS memory kill) into a failed report, not an app crash. */
class RemoteModelTestRunner(private val context: Context) : ModelTestRunner {
    override suspend fun run(modelId: String): ModelSelfTestReport = withContext(Dispatchers.Main) {
        var bound = false
        var connection: ServiceConnection? = null
        try {
            withTimeoutOrNull(TEST_TIMEOUT_MS) {
                suspendCancellableCoroutine<ModelSelfTestReport> { continuation ->
                    fun finish(report: ModelSelfTestReport) {
                        if (continuation.isActive) continuation.resume(report)
                    }
                    // The last step the service announced: the only evidence of where a dead process stopped.
                    var lastStage: String? = null
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.what == ModelTestService.WHAT_STAGE) {
                            lastStage = message.data.getString(ModelTestService.KEY_STAGE)
                        } else {
                            finish(message.data.testReport())
                        }
                        true
                    })
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                Messenger(binder).send(Message.obtain().apply {
                                    data = Bundle().apply { putString(ModelTestService.KEY_MODEL_ID, modelId) }
                                    replyTo = reply
                                })
                            } catch (error: Exception) {
                                finish(testFailure(modelId, "Could not start model test: ${error.message}"))
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            finish(testFailure(modelId, deadProcessDetail(lastStage)))
                        }

                        override fun onNullBinding(name: ComponentName) {
                            finish(testFailure(modelId, "The model test service could not start."))
                        }

                        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    }
                    connection = callback
                    bound = context.bindService(Intent(context, ModelTestService::class.java), callback, Context.BIND_AUTO_CREATE)
                    if (!bound) finish(testFailure(modelId, "Could not bind the model test service."))
                }
            } ?: testFailure(modelId, "The model test timed out. Try a smaller model.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            testFailure(modelId, "Could not run model test: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            if (bound) connection?.let { context.unbindService(it) }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L
    }
}

/**
 * What the dialog says when the test process died (D-91).
 *
 * The first sentence is the one the dialog has always shown and is kept word for word. The second
 * says where the process stopped, which is the one fact that tells a load failure from an inference
 * failure. When no stage was announced the process died before the probe began.
 */
internal fun deadProcessDetail(lastStage: String?): String {
    val where = when (lastStage) {
        SelfTestStage.LOADING_MODEL.name -> "It stopped while the engine was opening the model."
        SelfTestStage.PROBING.name -> "It stopped while the model was answering the probe."
        else -> "It stopped before the probe started."
    }
    return "The model process crashed or ran out of memory; this model may be incompatible with this device. " +
        "$where Playback has not been closed."
}
