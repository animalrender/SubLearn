package com.sublearn.app

import android.util.Log
import com.sublearn.core.common.SubLearnLogger

/** Logcat only. SubLearn has no analytics, no crash reporting and nothing that leaves the device. */
class AndroidLogcatLogger : SubLearnLogger {
    override fun log(level: SubLearnLogger.Level, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            SubLearnLogger.Level.DEBUG -> Log.d(tag, message, throwable)
            SubLearnLogger.Level.INFO -> Log.i(tag, message, throwable)
            SubLearnLogger.Level.WARN -> Log.w(tag, message, throwable)
            SubLearnLogger.Level.ERROR -> Log.e(tag, message, throwable)
        }
    }
}
