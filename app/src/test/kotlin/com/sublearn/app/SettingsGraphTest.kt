package com.sublearn.app

import android.app.Application
import com.sublearn.app.di.subLearnModules
import com.sublearn.core.ai.NlpModelInstaller
import com.sublearn.feature.settings.ResourcesViewModel
import com.sublearn.feature.settings.SettingsViewModel
import org.junit.Assert.assertNotNull
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
    fun settingsAndResourcesResolveWithTheProductionModules() {
        val graph = koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(subLearnModules())
        }
        try {
            assertNotNull(graph.koin.get<NlpModelInstaller>())
            assertNotNull(graph.koin.get<SettingsViewModel>())
            assertNotNull(graph.koin.get<ResourcesViewModel>())
        } finally {
            graph.close()
        }
    }
}
