package com.calmcast.podcast.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight file-based logger for diagnosing field issues on devices where live
 * `adb logcat` isn't practical (e.g. the user is listening untethered for 30+ min).
 *
 * Writes to the app's external files dir, which is pullable without root:
 *   adb pull /sdcard/Android/data/com.calmapps.calmcast/files/calmcast_log.txt
 *
 * All file I/O happens on a dedicated low-priority background thread so logging
 * never janks the UI (important on slow e-ink hardware). The only exception is
 * an uncaught-exception crash, which writes synchronously because the process
 * is about to die.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "calmcast_log.txt"
    private const val MAX_BYTES = 512 * 1024 // 512 KB cap; trims oldest when exceeded

    @Volatile
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CrashLogger").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, FILE_NAME)

            // Chain to any existing handler so we don't suppress the system crash dialog.
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    // Synchronous on purpose — the process is dying.
                    writeLine(Date(), "UNCAUGHT", "Crash on thread ${thread.name}", throwable)
                } catch (_: Throwable) {
                }
                previous?.uncaughtException(thread, throwable)
            }
            log("CrashLogger", "=== Logger initialized ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init crash logger", e)
        }
    }

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        val ts = Date()
        writer.execute { writeLine(ts, tag, message, null) }
    }

    fun logError(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        val ts = Date()
        writer.execute { writeLine(ts, tag, message, throwable) }
    }

    @Synchronized
    private fun writeLine(ts: Date, tag: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        try {
            trimIfNeeded(file)
            val sb = StringBuilder()
            sb.append(timeFormat.format(ts)).append("  ").append(tag).append(": ").append(message).append('\n')
            if (throwable != null) {
                sb.append(Log.getStackTraceString(throwable)).append('\n')
            }
            file.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun trimIfNeeded(file: File) {
        try {
            if (file.exists() && file.length() > MAX_BYTES) {
                val text = file.readText()
                // Keep the most recent half.
                file.writeText(text.substring(text.length / 2))
            }
        } catch (_: Exception) {
        }
    }
}
