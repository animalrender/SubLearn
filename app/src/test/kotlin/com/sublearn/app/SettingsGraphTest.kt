package com.sublearn.app

import android.app.Application
import com.sublearn.app.di.subLearnModules
import com.sublearn.core.ai.NlpModelInstaller
import com.sublearn.core.translate.TranslationModelManager
import com.sublearn.feature.settings.DownloadsViewModel
import com.sublearn.feature.settings.SettingsViewModel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Resolve the real settings graph: source checks cannot detect an unregistered function dependency in Koin. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SettingsGraphTest {
    @Test
    fun settingsAndDownloadsResolveWithTheProductionModules() {
        val graph = koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(subLearnModules())
        }
        try {
            assertNotNull(graph.koin.get<NlpModelInstaller>())
            assertNotNull(graph.koin.get<SettingsViewModel>())
            assertNotNull(graph.koin.get<DownloadsViewModel>())
        } finally {
            graph.close()
        }
    }

    @Test
    fun theOfflineTranslationModelIsOneSharedObject() {
        // The settings screen, the downloads screen and the player must share one manager, or the
        // three screens can disagree about whether the model is on the device (D-81).
        val graph = koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(subLearnModules())
        }
        try {
            val first = graph.koin.get<TranslationModelManager>()
            val second = graph.koin.get<TranslationModelManager>()
            assertSame(first, second)
        } finally {
            graph.close()
        }
    }
}
