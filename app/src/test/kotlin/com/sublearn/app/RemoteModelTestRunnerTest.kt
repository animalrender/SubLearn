package com.sublearn.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Binder death, cancellation and timeout must finish diagnostics and release their binding. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteModelTestRunnerTest {
    private class TestContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        var released = false
        var acceptsBinding = true

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return acceptsBinding
        }

        override fun unbindService(conn: ServiceConnection) {
            released = true
        }
    }

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun nativeProcessDeathIsReportedWithoutCrashingTheCaller() = runTest {
        val context = TestContext()
        val result = async { RemoteModelTestRunner(context).run("test-model") }
        runCurrent()
        context.connection.onServiceDisconnected(ComponentName(context, ModelTestService::class.java))
        val report = result.await()
        assertFalse(report.ok)
        assertEquals("test-model", report.modelId)
        assertTrue(report.detail.contains("crashed"))
        assertTrue(context.released)
    }

    @Test fun aHungServiceTimesOutAndIsUnbound() = runTest {
        val context = TestContext()
        val report = RemoteModelTestRunner(context).run("test-model")
        assertFalse(report.ok)
        assertTrue(report.detail.contains("timed out"))
        assertTrue(context.released)
    }

    @Test fun cancellingADiagnosticUnbindsItsProcess() = runTest {
        val context = TestContext()
        val result = async { RemoteModelTestRunner(context).run("test-model") }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(context.released)
    }

    @Test fun aRefusedBindingIsAFailedReport() = runTest {
        val context = TestContext().apply { acceptsBinding = false }
        val report = RemoteModelTestRunner(context).run("test-model")
        assertFalse(report.ok)
        assertTrue(report.detail.contains("bind"))
        assertFalse(context.released)
    }
}
