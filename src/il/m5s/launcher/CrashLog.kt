package il.m5s.launcher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the latest crash stack traces in a small file so they can be shown and copied from the device menu. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")
    private fun version(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Throwable) { "?" }
    fun record(c: Context, t: Throwable) {
        try {
            val sw = java.io.StringWriter(); t.printStackTrace(java.io.PrintWriter(sw))
            val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  v" + version(c) + "\n"
            val old = if (file(c).exists()) file(c).readText() else ""
            file(c).writeText((head + sw.toString() + "\n" + old).take(6000))
        } catch (e: Throwable) { }
    }
    fun last(c: Context): String = try { if (file(c).exists()) file(c).readText() else "" } catch (e: Throwable) { "" }
    fun clear(c: Context) { try { file(c).delete() } catch (e: Throwable) { } }
    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> record(c, e); prev?.uncaughtException(th, e) }
    }
}
