package io.github.ctxinf.cameraagent.infrastructure.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object AppLog : Logger {
    private const val DEFAULT_TAG = "CameraAgent"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _logFlow = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 200)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private fun broadcast(level: String, tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "[$timestamp] [$level/$tag] $message"
        _logFlow.tryEmit(formatted)
    }

    override fun d(tag: String, message: String) {
        try { Log.d(tag, message) } catch (_: Throwable) {}
        broadcast("D", tag, message)
    }

    override fun i(tag: String, message: String) {
        try { Log.i(tag, message) } catch (_: Throwable) {}
        broadcast("I", tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        try {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        } catch (_: Throwable) {}
        if (throwable != null) {
            broadcast("W", tag, "$message: ${throwable.message}")
        } else {
            broadcast("W", tag, message)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        try {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        } catch (_: Throwable) {}
        if (throwable != null) {
            broadcast("E", tag, "$message: ${throwable.message}")
        } else {
            broadcast("E", tag, message)
        }
    }

    fun d(message: String) = d(DEFAULT_TAG, message)
    fun i(message: String) = i(DEFAULT_TAG, message)
    fun w(message: String, throwable: Throwable? = null) = w(DEFAULT_TAG, message, throwable)
    fun e(message: String, throwable: Throwable? = null) = e(DEFAULT_TAG, message, throwable)
}

class AndroidLogger : Logger by AppLog
