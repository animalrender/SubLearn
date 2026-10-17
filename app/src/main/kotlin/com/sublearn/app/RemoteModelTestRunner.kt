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
                suspendCancellableCoroutine { continuation ->
                    fun finish(report: ModelSelfTestReport) {
                        if (continuation.isActive) continuation.resume(report)
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        finish(message.data.testReport())
                        true
                    })
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                Messenger(binder).send(Message.obtain().apply {
                                    data = Bundle().apply { putString("modelId", modelId) }
                                    replyTo = reply
                                })
                            } catch (error: Exception) {
                                finish(testFailure(modelId, "Could not start model test: ${error.message}"))
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            finish(testFailure(modelId, "The model process crashed or ran out of memory; this model may be " +
                                "incompatible with this device. Playback has not been closed."))
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
