package com.papercontrol.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone and shows it on the next start, so a problem can be reported
 * with its exact error instead of "it closed". Also records errors that were caught and survived.
 */
object CrashLog {
    private var file: File? = null
    private var versionName = ""

    fun install(context: Context) {
        val app = context.applicationContext
        file = File(app.filesDir, "last-crash.txt")
        versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file?.writeText(report("Crash", thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** An error that was caught and did not stop the app. Does not overwrite a real crash. */
    fun note(where: String, error: Throwable) {
        Log.w("CrashLog", where, error)
        val f = file ?: return
        runCatching { if (!f.exists()) f.writeText(report("Handled error in $where", Thread.currentThread().name, error)) }
    }

    /** The report waiting to be shown, if any. */
    fun pending(): String? = file?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() { file?.delete() }

    private fun report(kind: String, thread: String, error: Throwable): String = buildString {
        appendLine("$kind · PaperControl $versionName")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Thread: $thread")
        appendLine()
        // The root cause first: long traces get cut off, and the cause is what matters.
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 6) {
            appendLine((if (depth == 0) "Error: " else "Caused by: ") + e.javaClass.name + ": " + e.message)
            e.stackTrace.firstOrNull { it.className.startsWith("com.papercontrol") }?.let { appendLine("    at $it") }
            e = e.cause.takeIf { it !== e }
            depth++
        }
        appendLine()
        append(Log.getStackTraceString(error).take(6000))
    }
}
