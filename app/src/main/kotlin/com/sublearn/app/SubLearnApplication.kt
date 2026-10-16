package com.sublearn.app

import android.app.Application
import android.util.Log
import com.sublearn.app.di.subLearnModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class SubLearnApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Before Koin: whether a profile can run is a property of the build and the device, and the
        // answer is needed by the first screen that reads the catalog.
        val runtimes = NlpRuntimeBootstrap.register()
        if (BuildConfig.DEBUG) Log.i(TAG, NlpRuntimeBootstrap.describe(runtimes))

        startKoin {
            // Koin's own logger is off in release; the app has no crash reporting of its own.
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@SubLearnApplication)
            modules(subLearnModules())
        }
    }

    private companion object {
        const val TAG = "SubLearn"
    }
}
