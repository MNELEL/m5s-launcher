package il.m5s.launcher

import android.content.Context
import java.security.MessageDigest

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("m5s", Context.MODE_PRIVATE)

    fun city(c: Context) = p(c).getInt("city", 0)
    fun setCity(c: Context, v: Int) = p(c).edit().putInt("city", v).apply()

    fun homeBg(c: Context) = p(c).getInt("homeBg", 0)
    fun setHomeBg(c: Context, v: Int) = p(c).edit().putInt("homeBg", v).apply()

    fun custom(c: Context, k: String, def: String): String = p(c).getString("c:" + k, null) ?: def
    fun setCustom(c: Context, k: String, v: String?) {
        val e = p(c).edit(); if (v == null) e.remove("c:" + k) else e.putString("c:" + k, v); e.apply()
    }
    fun hiddenMenu(c: Context): Set<String> = p(c).getStringSet("hiddenMenu", emptySet()) ?: emptySet()
    fun toggleHiddenMenu(c: Context, k: String) {
        val s = HashSet(hiddenMenu(c)); if (!s.add(k)) s.remove(k)
        p(c).edit().putStringSet("hiddenMenu", s).apply()
    }
    fun arrowInv(c: Context) = p(c).getBoolean("arrowInv", false)
    fun setArrowInv(c: Context, v: Boolean) = p(c).edit().putBoolean("arrowInv", v).apply()
    fun sort(c: Context) = p(c).getInt("csort", 0)
    fun setSort(c: Context, v: Int) = p(c).edit().putInt("csort", v).apply()
    fun speed(c: Context, d: Int): String = p(c).getString("sd" + d, "") ?: ""
    fun setSpeed(c: Context, d: Int, v: String) = p(c).edit().putString("sd" + d, v).apply()
    fun extras(c: Context): List<String> = (p(c).getString("menuExtras", "") ?: "").split(",").filter { it.isNotEmpty() }
    fun setExtras(c: Context, l: List<String>) = p(c).edit().putString("menuExtras", l.joinToString(",")).apply()
    fun order(c: Context): List<String> = (p(c).getString("menuOrder", "") ?: "").split("|").filter { it.isNotEmpty() }
    fun setOrder(c: Context, l: List<String>) = p(c).edit().putString("menuOrder", l.joinToString("|")).apply()
    fun touchLock(c: Context) = p(c).getBoolean("touchLock", false)
    fun setTouchLock(c: Context, v: Boolean) = p(c).edit().putBoolean("touchLock", v).apply()
    fun setBlocked(c: Context, s: Set<String>) = p(c).edit().putStringSet("blocked", HashSet(s)).apply()
    fun hiddenApps(c: Context): Set<String> = p(c).getStringSet("hiddenApps", emptySet()) ?: emptySet()
    fun toggleHiddenApp(c: Context, pkg: String) {
        val s = HashSet(hiddenApps(c)); if (!s.add(pkg)) s.remove(pkg)
        p(c).edit().putStringSet("hiddenApps", s).apply()
    }
    fun setHiddenApps(c: Context, s: Set<String>) = p(c).edit().putStringSet("hiddenApps", HashSet(s)).apply()
    fun theme(c: Context) = p(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = p(c).edit().putInt("theme", v).apply()
    fun homeLayout(c: Context) = p(c).getInt("homeLayout", 0)
    fun setHomeLayout(c: Context, v: Int) = p(c).edit().putInt("homeLayout", v).apply()
    fun tts(c: Context) = p(c).getBoolean("tts", false)
    fun setTts(c: Context, v: Boolean) = p(c).edit().putBoolean("tts", v).apply()
    fun recents(c: Context): List<String> = (p(c).getString("recents", "") ?: "").split(",").filter { it.isNotEmpty() }
    fun setRecents(c: Context, l: List<String>) = p(c).edit().putString("recents", l.joinToString(",")).apply()
    fun pushRecent(c: Context, pkg: String) { setRecents(c, (listOf(pkg) + recents(c).filter { it != pkg }).take(10)) }
    fun drawer(c: Context) = p(c).getInt("drawer", 0)
    fun setDrawer(c: Context, v: Int) = p(c).edit().putInt("drawer", v).apply()

    fun screenMode(c: Context) = p(c).getInt("screenMode", 0)
    fun setScreenMode(c: Context, v: Int) = p(c).edit().putInt("screenMode", v).apply()

    fun blocked(c: Context): Set<String> = p(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun toggleBlocked(c: Context, pkg: String) {
        val s = HashSet(blocked(c))
        if (!s.add(pkg)) s.remove(pkg)
        p(c).edit().putStringSet("blocked", s).apply()
    }

    fun hasPin(c: Context) = p(c).getString("pin", null) != null
    fun setPin(c: Context, pin: String) {
        val salt = System.nanoTime().toString()
        p(c).edit().putString("salt", salt).putString("pin", hash(salt, pin)).apply()
    }
    fun checkPin(c: Context, pin: String): Boolean {
        val h = p(c).getString("pin", null) ?: return true
        return h == hash(p(c).getString("salt", "") ?: "", pin)
    }
    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}
