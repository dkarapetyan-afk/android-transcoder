package com.androidcompress.app.util

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

object AppLog {
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error is CancellationException) return
        val safe = if (tag.length <= 23) tag else tag.take(23)
        if (error != null) Log.e(safe, message, error) else Log.e(safe, message)
    }
}

fun <T> Result<T>.onFailureLog(tag: String, message: String): Result<T> {
    exceptionOrNull()?.let { AppLog.e(tag, message, it) }
    return this
}

inline fun <T> runCatchingLog(tag: String, message: String, block: () -> T): Result<T> =
    runCatching(block).onFailureLog(tag, message)
