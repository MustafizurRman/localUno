package com.mutsho.localuno

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches an uncaught exception, writes it to disk, and re-throws to the platform handler.
 *
 * This exists because of where the app is used. A crash happens at somebody's kitchen table on a
 * phone that has never been plugged into a computer, and by the time it is described to anyone the
 * only surviving detail is "it crashed during the game". Android's own trace goes to logcat, which
 * is gone the moment the process dies and unreachable without adb; Play Console reports need a Play
 * install and a day's delay. So the trace is written somewhere the next launch can find it.
 *
 * Deliberately does NOT swallow the crash. The default handler still runs afterwards, so the
 * process dies exactly as it would have - a game that limps on after an unexpected exception, with
 * the engine in a state nobody designed, is worse than one that restarts.
 *
 * No network, no automatic upload: the file stays in the app's private storage until the player
 * chooses to share it.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Extra text to append to the report - in practice the round's replay log, set by GameViewModel
     * on a debug build (see SeedJournal). A stack trace says where the app died; this says what the
     * table was doing at the time, which is the half that lets anyone try it again.
     *
     * Volatile and defensive: it is read from the crashing thread, whichever that turns out to be,
     * and anything it throws is swallowed by [install]'s try/catch rather than replacing the crash.
     */
    @Volatile
    var extraContext: (() -> String)? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (_: Throwable) {
                // A failure while recording a crash must never replace the crash - the original
                // exception is the one worth surfacing.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // The thread name matters more than usual here: this app runs its engine on a single
        // confined dispatcher and its sockets on IO, so "which thread" narrows a report to the
        // host's engine, a client's reader loop, or the UI before the trace is even read.
        val report = buildString {
            appendLine("Local UNO crash")
            appendLine("time     : $when_")
            appendLine("thread   : ${thread.name}")
            appendLine("version  : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device   : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
            val extra = try { extraContext?.invoke() } catch (_: Throwable) { null }
            if (!extra.isNullOrBlank()) {
                appendLine()
                appendLine()
                append(extra)
            }
        }
        File(context.filesDir, FILE_NAME).writeText(report)
    }

    /** The report from the previous run, or null if the last run ended cleanly. */
    fun pendingReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
