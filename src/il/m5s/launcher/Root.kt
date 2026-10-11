package il.m5s.launcher

import android.content.Context
import java.io.File

/**
 * Optional root helpers (Magisk). Nothing here runs unless the user starts it from "ניהול מכשיר ← רוט".
 * Every call goes through `su`, so Magisk shows its own approval dialog. Hardware touch disabling is sysfs based,
 * never persists across a reboot, and is restored whenever the touch lock is turned off.
 */
object Root {
    class Res(val code: Int, val out: String)
    @Volatile private var lastTouchOff: Boolean? = null

    fun sh(cmd: String, timeoutMs: Long = 30000): Res {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val rd = Thread { try { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } catch (e: Exception) { } }
            rd.start()
            val end = System.currentTimeMillis() + timeoutMs
            var code = -1
            while (System.currentTimeMillis() < end) {
                try { code = p.exitValue(); break } catch (e: IllegalThreadStateException) { Thread.sleep(50) }
            }
            if (code == -1) { try { p.destroy() } catch (e: Exception) { } }
            rd.join(500)
            Res(code, sb.toString().trim())
        } catch (e: Exception) { Res(-1, "su לא זמין: " + (e.message ?: "")) }
    }

    fun check(): List<String> {
        val r = sh("id")
        return if (r.code == 0 && r.out.contains("uid=0")) listOf("✓ יש רוט", r.out)
        else listOf("✗ אין גישת רוט", if (r.out.isEmpty()) "קוד " + r.code else r.out, "אם Magisk הציג חלון - אשר אותו ונסה שוב")
    }

    private fun line(title: String, r: Res) = (if (r.code == 0) "✓ " else "✗ ") + title + (if (r.out.isNotEmpty()) ": " + r.out.lines().first().take(90) else "")

    fun enableAccessibility(c: Context): List<String> {
        val me = c.packageName + "/" + c.packageName + ".Blocker"
        val cur = sh("settings get secure enabled_accessibility_services").out.trim().let { if (it == "null") "" else it }
        val parts = cur.split(":").filter { it.isNotEmpty() }
        val l = ArrayList<String>()
        l.add(line("אישור הגדרות מוגבלות", sh("appops set " + c.packageName + " ACCESS_RESTRICTED_SETTINGS allow")))
        if (parts.contains(me)) l.add("✓ השירות כבר מופעל")
        else l.add(line("הפעלת שירות הנגישות", sh("settings put secure enabled_accessibility_services " + (parts + me).joinToString(":"))))
        l.add(line("הפעלת נגישות", sh("settings put secure accessibility_enabled 1")))
        return l
    }

    fun grantAll(c: Context): List<String> {
        val perms = listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "CALL_PHONE", "READ_CALL_LOG", "READ_SMS", "SEND_SMS",
            "READ_CALENDAR", "WRITE_CALENDAR", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")
        val l = ArrayList<String>()
        var ok = 0
        for (p in perms) { val r = sh("pm grant " + c.packageName + " android.permission." + p); if (r.code == 0) ok++ else l.add(line(p, r)) }
        l.add(0, "✓ ניתנו $ok מתוך ${perms.size} הרשאות")
        l.add(line("שינוי הגדרות מערכת", sh("appops set " + c.packageName + " WRITE_SETTINGS allow")))
        l.add(line("גישה ל'נא לא להפריע'", sh("cmd notification allow_dnd " + c.packageName)))
        return l
    }

    fun setHome(c: Context): List<String> {
        val r = sh("cmd role add-role-holder android.app.role.HOME " + c.packageName)
        if (r.code == 0) return listOf(line("מסך בית ברירת מחדל", r))
        return listOf(line("מסך בית (role)", r), line("מסך בית (pm)", sh("pm set-home-activity " + c.packageName + "/.MainActivity")))
    }
    fun setDialer(c: Context) = listOf(line("אפליקציית טלפון ברירת מחדל", sh("cmd role add-role-holder android.app.role.DIALER " + c.packageName)))
    fun setOwner(c: Context): List<String> {
        val r = sh("dpm set-device-owner " + c.packageName + "/.AdminReceiver")
        return listOf(line("Device Owner", r)) + (if (r.code != 0) listOf("בדרך כלל נכשל כשיש חשבונות במכשיר (גוגל וכד')") else emptyList())
    }
    fun removeOwner(c: Context) = listOf(line("ביטול Device Owner", sh("dpm remove-active-admin " + c.packageName + "/.AdminReceiver")))

    // ---------- hardware touch ----------
    class TouchDev(val name: String, val input: String, val event: String)
    fun findTouch(): TouchDev? {
        return try {
            val blocks = File("/proc/bus/input/devices").readText().split("\n\n")
            var best: TouchDev? = null
            val re = Regex("touch|ts|fts|goodix|synaptics|himax|focal|ilitek|novatek|gt9|cyttsp|atmel|sec_|mtk-tpd|tp", RegexOption.IGNORE_CASE)
            for (b in blocks) {
                val name = Regex("Name=\"([^\"]*)\"").find(b)?.groupValues?.get(1) ?: continue
                val handlers = Regex("Handlers=(.*)").find(b)?.groupValues?.get(1) ?: ""
                val ev = Regex("event(\\d+)").find(handlers)?.value ?: continue
                val sys = Regex("Sysfs=(\\S+)").find(b)?.groupValues?.get(1) ?: ""
                val input = Regex("input\\d+").findAll(sys).lastOrNull()?.value ?: continue
                val prop = Regex("PROP=(\\w+)").find(b)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
                val abs = Regex("ABS=(\\w+)").find(b)?.groupValues?.get(1)?.replace(" ", "") ?: ""
                val direct = (prop and 2L) != 0L
                if (direct || (abs.isNotEmpty() && abs != "0" && re.containsMatchIn(name))) { best = TouchDev(name, input, ev); if (direct) break }
            }
            best
        } catch (e: Exception) { null }
    }
    private class Method(val key: String, val path: String, val off: String, val on: String)
    private fun methods(t: TouchDev) = listOf(
        Method("inhibited", "/sys/class/input/" + t.input + "/inhibited", "1", "0"),
        Method("dev_enabled", "/sys/class/input/" + t.input + "/device/enabled", "0", "1"),
        Method("enabled", "/sys/class/input/" + t.input + "/enabled", "0", "1")
    )

    fun testTouch(c: Context): List<String> {
        val l = ArrayList<String>()
        if (check().first().startsWith("✗")) return listOf("✗ אין גישת רוט")
        val t = findTouch() ?: return listOf("✗ לא נמצא מסך מגע ב-/proc/bus/input/devices")
        l.add("מסך מגע: " + t.name + " (" + t.input + ", " + t.event + ")")
        var chosen: String? = null
        for (m in methods(t)) {
            if (sh("[ -w " + m.path + " ] && echo y").out.trim() != "y") { l.add("— " + m.key + ": אין"); continue }
            sh("echo " + m.off + " > " + m.path)
            val back = sh("cat " + m.path).out.trim()
            Thread.sleep(1200)
            sh("echo " + m.on + " > " + m.path)
            val ok = back == m.off
            l.add((if (ok) "✓ " else "? ") + m.key + ": נקרא " + back + (if (ok) " (עובד)" else " (לא הוחלף)"))
            if (ok && chosen == null) chosen = m.key
        }
        if (chosen != null) { Prefs.setCustom(c, "hwMethod", chosen); l.add("נבחרה שיטה: " + chosen + ". אפשר להפעיל 'ניטרול מגע בחומרה'.") }
        else l.add("לא נמצאה שיטה שמחליפה. ניטרול החומרה לא יופעל במכשיר הזה.")
        return l
    }

    /** off=true disables the touch hardware, off=false re-enables it. Safe to call repeatedly. */
    fun setTouch(c: Context, off: Boolean): List<String> {
        val t = findTouch() ?: return listOf("✗ לא נמצא מסך מגע")
        val key = Prefs.custom(c, "hwMethod", "")
        val ms = if (key.isEmpty()) methods(t) else methods(t).filter { it.key == key }
        val l = ArrayList<String>()
        for (m in ms) {
            val r = sh("[ -w " + m.path + " ] && echo " + (if (off) m.off else m.on) + " > " + m.path, 8000)
            l.add(line(m.key, r))
        }
        lastTouchOff = off
        return l
    }

    /** Called from the accessibility service whenever the touch lock state is (re)applied. */
    fun applyTouch(c: Context, locked: Boolean) {
        val enabled = Prefs.custom(c, "hwtouch", "") == "1" && Prefs.custom(c, "hwMethod", "").isNotEmpty()
        val want = enabled && locked
        if (lastTouchOff == null && !want) return   // hardware was never touched: nothing to restore
        if (lastTouchOff == want) return
        Thread { try { setTouch(c, want) } catch (e: Throwable) { } }.start()
    }

    /** Failsafe at start: if hardware disable is configured but the touch lock is off, make sure touch is on. */
    fun restoreIfNeeded(c: Context) {
        if (Prefs.custom(c, "hwtouch", "") == "1" && !Prefs.touchLock(c)) Thread { try { setTouch(c, false) } catch (e: Throwable) { } }.start()
    }
}
