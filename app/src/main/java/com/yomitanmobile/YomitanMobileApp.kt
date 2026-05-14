package com.yomitanmobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

@HiltAndroidApp
class YomitanMobileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installLastResortHandler()
    }

    /**
     * Hooks the JVM's default uncaught-exception handler so:
     *  1. Logs to logcat (E-level for filter-friendliness)
     *  2. Posts a toast best-effort (may not finish before process death)
     *  3. **Writes a crash file to filesDir/last_crash.txt** — survives
     *     process death and is readable on next launch via the
     *     "Last crash" banner shown from MainActivity. This is the
     *     diagnostic path for users without adb.
     *  4. Delegates to the previous handler so Android still kills the
     *     process normally.
     *
     * Native crashes (SIGSEGV / OOM-killer) bypass this entirely — they
     * never enter the JVM. For those, the system logcat is the only path.
     */
    private fun installLastResortHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mainHandler = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "YomitanMobileApp",
                "Uncaught on thread ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}",
                throwable
            )

            // Persist to a file so the next launch can show this crash.
            // Best-effort: if the disk write fails (full, permission, etc.)
            // we don't want a second uncaught exception bouncing around
            // the handler.
            runCatching {
                val stackTrace = StringWriter().also {
                    PrintWriter(it).use { writer ->
                        throwable.printStackTrace(writer)
                    }
                }.toString()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(System.currentTimeMillis())
                val content = buildString {
                    appendLine("Time: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(stackTrace)
                }
                File(filesDir, LAST_CRASH_FILE).writeText(content)
            }

            try {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "Crash: ${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Throwable) {
                // ignore — we're already dying
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** Filename inside [Application.getFilesDir] for the last crash report. */
        const val LAST_CRASH_FILE = "last_crash.txt"
    }
}
