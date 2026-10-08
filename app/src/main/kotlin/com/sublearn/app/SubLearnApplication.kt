package com.sublearn.app

import android.app.Application
import com.sublearn.app.di.subLearnModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class SubLearnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            // Koin's own logger is off in release; the app has no crash reporting of its own.
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@SubLearnApplication)
            modules(subLearnModules())
        }
    }
}
