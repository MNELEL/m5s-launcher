package il.m5s.launcher

import android.Manifest
import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import android.view.MotionEvent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.media.RingtoneManager
import android.widget.EditText
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.icu.text.SimpleDateFormat
import android.media.AudioManager
import android.telecom.TelecomManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.content.ContentValues
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private class Item(
        val title: String, val icon: String = "", val tint: Int = 0xFFE0F2F1.toInt(),
        val sub: String = "", val ico: Drawable? = null, val data: String = "", val run: () -> Unit = {}
    )
    private class App(val pkg: String, val label: String, val icon: Drawable)
    private enum class Screen { HOME, MENU, DEVICE, ZMANIM, CITY, APPS, BLOCK, PERMS, PIN, HOMEBG, DIALER, CONTACTS, ACTIONS, CALLLOG, EDITMENU, EDITITEM, PICKAPP, CONTACTMGR, ICONPICK, EDITAPPS, SOUNDS, CALC, CHECK, SPEED, TEXT, HIDEAPPS, OPTIONS, SHORTCUTS, SHORTCUTPICK, THEMES, KEYMAP, RECENT, BT, MESSAGES, MESSAGE_THREAD, MESSAGE_COMPOSE, CALENDAR, ROOTMENU }

    private var screen = Screen.HOME
    private var idx = 0
    private val stack = ArrayList<Screen>()
    private val idxStack = ArrayList<Int>()

    private val ink: Int get() = th().ink
    private val cyan: Int get() = th().cyan
    private val band: Int get() = th().band
    private val soft: Int get() = th().soft
    private val tint = 0xFFE0F2F1.toInt()

    private val bgNames = listOf("כחול", "ירוק", "כהה", "לבן עם סמל", "כחול עם סמל", "ים", "שקיעה", "הרים", "שדה", "לילה", "ים עם סמל", "יער עם סמל", "הר געש עם סמל", "כחול עם סמל לבן")
    private val wpNames = listOf("wp_sea", "wp_dusk", "wp_mountains", "wp_field", "wp_night", "bg_sea_seal", "bg_forest_seal", "bg_volcano_seal", "bg_blue_seal")
    private fun sealRes(light: Boolean) = resources.getIdentifier(if (light) "seal_light" else "seal_dark", "drawable", packageName)
    private fun sealView() = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        .also { it.contentDescription = "ועדת הרבנים לענייני תקשורת" }

    // PIN state: mode 0 = verify, 1 = choose new, 2 = confirm new
    private val pinBuf = StringBuilder()
    private var pinMode = 0
    private var pinFirst = ""
    private var pinMsg = ""
    private var pinThen: (() -> Unit)? = null

    private var appCache: List<App>? = null

    private val dialBuf = StringBuilder()
    private val filterBuf = StringBuilder()
    private var actionTitle = ""
    private var actionItems: List<Item> = emptyList()
    private var contactsCache: List<PhoneUtil.Contact>? = null
    private var logCache: List<PhoneUtil.Rec>? = null
    private var pendingPerm: (() -> Unit)? = null
    private var contactLoading = false
    private var contactsQueryToken = 0
    private var contactsVer = 0
    private var cRowsKey = ""
    private var cRows: List<Item> = emptyList()
    private var listTop = 0
    private var recenter = false
    private var repeating = false
    private var smsCache: List<SmsRow> = emptyList()
    private var smsThreadNumber = ""
    private var smsThreadName = ""
    private var smsBody = ""
    private var calendarMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var calendarYear = Calendar.getInstance().get(Calendar.YEAR)
    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long, val type: Int, val name: String?)

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (screen == Screen.HOME) render() }
    }

    // ---------- helpers ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cat(c: String) = Intent(Intent.ACTION_MAIN).addCategory(c)
    private fun go(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("לא זמין במכשיר") }
    }
    private fun callLog() {
        ensurePerms(arrayOf(Manifest.permission.READ_CALL_LOG)) { logCache = null; open(Screen.CALLLOG) }
    }
    private fun dialChar(code: Int) = when (code) {
        KeyEvent.KEYCODE_STAR -> '*'
        KeyEvent.KEYCODE_POUND -> '#'
        else -> '0' + (code - KeyEvent.KEYCODE_0)
    }
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun ensurePerms(perms: Array<String>, then: () -> Unit) {
        val missing = perms.filter { !has(it) }
        if (missing.isEmpty()) { then(); return }
        pendingPerm = then
        requestPermissions(missing.toTypedArray(), 10)
    }
    private fun placeCall(number: String) {
        if (number.isEmpty()) return
        ensurePerms(arrayOf(Manifest.permission.CALL_PHONE)) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                dialBuf.setLength(0)
            } catch (e: Exception) { toast("לא ניתן לחייג") }
        }
    }
    private fun sms(n: String) = go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))))
    private fun newContact(n: String) {
        askText("שם איש הקשר", "") { name -> askText("מספר", n) { num -> if (name.isNotEmpty() || num.isNotEmpty()) contactCreate(name.ifEmpty { num }, num) } }
    }
    private var dialSel = -1
    private fun dialSuggestions(): List<PhoneUtil.Contact> {
        val q = dialBuf.toString()
        if (q.isEmpty() || q.any { !it.isDigit() } || !has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val all = contactsCache ?: run { prewarmContacts(); return emptyList() }
        return all.filter { PhoneUtil.matches(it, q) }.take(2)
    }
    private fun callTyped() {
        if (dialBuf.isEmpty()) callLog()
        else placeCall(dialSuggestions().getOrNull(dialSel)?.numbers?.firstOrNull() ?: dialBuf.toString())
    }
    private fun fmtNum(d: String): String {
        if (d.isEmpty() || d.any { !it.isDigit() }) return d
        return when {
            d.length == 9 && d.startsWith("0") && !d.startsWith("05") -> d.substring(0, 2) + "-" + d.substring(2)
            d.length > 3 && d.startsWith("05") -> d.substring(0, 3) + "-" + d.substring(3)
            else -> d
        }
    }
    // long press: * clears the number, 2-9 on an empty dialer = speed dial
    private fun longKey(code: Int): Boolean {
        if (screen != Screen.DIALER) return false
        if (code == KeyEvent.KEYCODE_STAR) { dialBuf.setLength(0); dialSel = -1; render(); return true }
        if (code in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 && dialBuf.length == 1 && dialBuf[0] == dialChar(code)) {
            val d = code - KeyEvent.KEYCODE_0
            val n = Prefs.speed(this, d)
            if (n.isNotEmpty()) { dialBuf.setLength(0); placeCall(n); render() } else toast("אין חיוג מהיר במקש $d")
            return true
        }
        return false
    }
    private fun speedItems(): List<Item> = (2..9).map { d ->
        val cur = Prefs.speed(this, d)
        Item("מקש $d", "⚡", 0xFFFFF3E0.toInt(), if (cur.isEmpty()) "ריק" else fmtNum(cur)) {
            askText("מספר לחיוג מהיר במקש $d (ריק = מחיקה)", cur) { v -> Prefs.setSpeed(this, d, PhoneUtil.digits(v)); render() }
        }
    }
    private fun yn(b: Boolean) = if (b) "פעיל ✓" else "לא פעיל"

    // ---------- navigation ----------
    private fun open(s: Screen) { stack.add(screen); idxStack.add(idx); screen = s; idx = 0; listScroll = 0; render() }
    private fun back() {
        listScroll = 0
        if (screen == Screen.BT && btScanning) { try { btAdapter?.cancelDiscovery() } catch (t: Throwable) { }; btScanning = false }
        if (stack.isEmpty()) { screen = Screen.HOME; idx = 0 }
        else { screen = stack.removeAt(stack.size - 1); idx = idxStack.removeAt(idxStack.size - 1); recenter = true }
        render()
    }
    private fun backAction() {
        when {
            screen == Screen.HOME -> lockScreen()
            screen == Screen.TEXT && textBuf.isNotEmpty() -> { textBuf.deleteCharAt(textBuf.length - 1); mtKey = -1; render() }
            screen == Screen.CALC && calcBuf.isNotEmpty() -> { calcBuf.setLength(calcBuf.length - 1); render() }
            screen == Screen.DIALER && dialBuf.isNotEmpty() -> { dialBuf.setLength(dialBuf.length - 1); render() }
            screen == Screen.CONTACTS && filterBuf.isNotEmpty() -> { filterBuf.setLength(filterBuf.length - 1); idx = 0; render() }
            else -> back()
        }
    }
    private fun leftLabel(): String = if (hasOptions()) "אפשרויות" else when (screen) { Screen.HOME -> "תפריט"; Screen.DIALER -> "חיוג"; Screen.TEXT -> "אישור"; Screen.CALC -> "="; else -> "אישור" }
    private fun rightLabel() = when (screen) {
        Screen.HOME -> "כיבוי"
        Screen.CALC -> if (calcBuf.isNotEmpty()) "מחק" else "אחורה"
        Screen.TEXT -> if (textBuf.isNotEmpty()) "מחק" else "ביטול"
        Screen.DIALER -> if (dialBuf.isNotEmpty()) "מחק" else "אחורה"
        Screen.CONTACTS -> if (filterBuf.isNotEmpty()) "מחק" else "אחורה"
        else -> "אחורה"
    }
    private fun goHome() { stack.clear(); idxStack.clear(); screen = Screen.HOME; idx = 0; listScroll = 0; pinThen = null; render() }
    private fun activate() {
        when (screen) {
            Screen.HOME -> open(Screen.MENU)
            Screen.PIN -> confirmPin()
            Screen.DIALER -> callTyped()
            Screen.MESSAGE_COMPOSE -> composeSms()
            Screen.CALC -> calcKey(KeyEvent.KEYCODE_ENTER, true)
            Screen.TEXT -> textConfirm()
            else -> rows().getOrNull(idx)?.run?.invoke()
        }
    }

    // ---------- PIN ----------
    private fun requirePin(then: () -> Unit) {
        if (!Prefs.hasPin(this)) { then(); return }
        pinMode = 0; pinBuf.setLength(0); pinMsg = ""; pinThen = then
        open(Screen.PIN)
    }
    private fun startSetPin() {
        pinMode = 1; pinBuf.setLength(0); pinMsg = ""; pinThen = null
        open(Screen.PIN)
    }
    private fun confirmPin() {
        val v = pinBuf.toString()
        when (pinMode) {
            0 -> if (Prefs.checkPin(this, v)) { val t = pinThen; pinThen = null; back(); t?.invoke() }
                 else { pinMsg = "קוד שגוי"; pinBuf.setLength(0); render() }
            1 -> if (v.length < 4) { pinMsg = "לפחות 4 ספרות"; render() }
                 else { pinFirst = v; pinBuf.setLength(0); pinMode = 2; pinMsg = ""; render() }
            else -> if (v == pinFirst) { Prefs.setPin(this, v); back(); toast("הקוד נשמר") }
                    else { pinMsg = "הקודים לא תואמים"; pinBuf.setLength(0); pinMode = 1; render() }
        }
    }
    private fun pinKey(code: Int, down: Boolean): Boolean {
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (down && pinBuf.length < 8) { pinBuf.append(code - KeyEvent.KEYCODE_0); pinMsg = ""; render() }
        } else if (code == KeyEvent.KEYCODE_STAR) {
            if (down && pinBuf.isNotEmpty()) { pinBuf.setLength(pinBuf.length - 1); render() }
        } else if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (down) confirmPin()
        } else if (code == KeyEvent.KEYCODE_BACK) {
            if (down) back()
        } else if (code == KeyEvent.KEYCODE_ENDCALL) {
            if (down) goHome()
        }
        return true
    }

    // ---------- data for screens ----------
    private val baseMenu by lazy {
        listOf(
            Item("חיוג", "☎", 0xFFE8F5E9.toInt()) { dialBuf.setLength(0); open(Screen.DIALER) },
            Item("אנשי קשר", "👤", 0xFFFCE4EC.toInt()) {
                ensurePerms(arrayOf(Manifest.permission.READ_CONTACTS)) { filterBuf.setLength(0); nameQuery = ""; contactLoading = true; open(Screen.CONTACTS); loadContactsAsync() }
            },
            Item("יומן שיחות", "📞", 0xFFFFEBEE.toInt()) { callLog() },
            Item("הודעות", "✉", 0xFFE3F2FD.toInt()) { openMessages() },
            Item("שעון", "⏰", 0xFFE0F7FA.toInt()) { launch(tClock) },
            Item("לוח שנה", "📅", 0xFFFFF3E0.toInt()) { openCalendar() },
            Item("מחשבון", "🧮", 0xFFE8F5E9.toInt()) { launch(tCalc) },
            Item("זמני היום", "🕯", 0xFFFFF8E1.toInt()) { open(Screen.ZMANIM) },
            Item("רשמקול", "🎙", 0xFFE1F5FE.toInt()) { launch(tRec) },
            Item("אפליקציות", "📱", 0xFFF3E5F5.toInt()) { open(Screen.APPS) },
            Item("ניהול מכשיר", "⚙", 0xFFEDE7F6.toInt()) { requirePin { open(Screen.DEVICE) } },
            Item("בלוטות'", "🔵", 0xFFE3F2FD.toInt()) { btOpen() },
            Item("ניהול כיתה", "🎓", 0xFFFFF3E0.toInt()) { launch(tClass()) },
            Item("הגדרות", "🔧", 0xFFE0F2F1.toInt()) { requirePin { go(Intent(Settings.ACTION_SETTINGS)) } }
        )
    }
    private fun mName(k: String, b: Item) = Prefs.custom(this, "n:" + k, b.title)
    private fun mIcon(k: String, b: Item) = Prefs.custom(this, "i:" + k, b.icon)
    private fun launchPkg(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) go(i) else toast("האפליקציה לא זמינה")
    }
    private fun extraList(): List<String> = (Prefs.extras(this) + Prefs.custom(this, "extra", "").split("\n").filter { it.isNotEmpty() }).distinct()
    private fun setExtraList(l: List<String>) { Prefs.setExtras(this, l); Prefs.setCustom(this, "extra", null) }
    private fun appByPkg(pkg: String): App? = apps().firstOrNull { it.pkg == pkg }
    private fun targetOf(k: String) = Prefs.custom(this, "t:" + k, "")
    private var pickAdd = true
    private fun menuEntries(): List<Pair<String, Item>> {
        val all = ArrayList<Pair<String, Item>>()
        for (b in baseMenu) {
            val t = targetOf(b.title)
            all.add(b.title to (if (t.isNotEmpty() && b.title != "ניהול מכשיר") Item(b.title, b.icon, b.tint, run = { launchPkg(t) }) else b))
        }
        val installed = apps()
        for (pkg in extraList()) {
            val a = installed.firstOrNull { it.pkg == pkg } ?: continue
            all.add(("pkg:" + pkg) to Item(Prefs.custom(this, "an:" + pkg, a.label), ico = a.icon) { launchPkg(pkg) })
        }
        val order = Prefs.order(this)
        return all.sortedBy { e -> order.indexOf(e.first).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    private val menuItems: List<Item>
        get() = menuEntries().filter { !Prefs.hiddenMenu(this).contains(it.first) }
            .map { e -> Item(mName(e.first, e.second), mIcon(e.first, e.second), e.second.tint, ico = e.second.ico, run = e.second.run) }

    private fun rows(): List<Item> = when (screen) {
        Screen.MENU -> menuItems
        Screen.DEVICE -> deviceItems()
        Screen.ZMANIM -> zmanimItems()
        Screen.CITY -> cityItems()
        Screen.APPS -> appItems()
        Screen.BLOCK -> blockItems()
        Screen.PERMS -> permItems()
        Screen.HOMEBG -> homeBgItems()
        Screen.CONTACTS -> contactItems()
        Screen.ACTIONS -> actionItems
        Screen.CALLLOG -> logItems()
        Screen.EDITMENU -> editMenuItems()
        Screen.EDITITEM -> editItemItems()
        Screen.PICKAPP -> pickAppItems()
        Screen.CONTACTMGR -> contactMgrItems()
        Screen.ICONPICK -> iconPickItems()
        Screen.EDITAPPS -> editAppItems()
        Screen.SOUNDS -> soundItems()
        Screen.SPEED -> speedItems()
        Screen.HIDEAPPS -> hideItems()
        Screen.OPTIONS -> optItems
        Screen.SHORTCUTS -> shortcutItems()
        Screen.SHORTCUTPICK -> shortcutPickItems()
        Screen.THEMES -> themeItems()
        Screen.KEYMAP -> keymapItems()
        Screen.ROOTMENU -> rootItems()
        Screen.RECENT -> recentItems()
        Screen.BT -> btItems()
        Screen.CHECK -> checkItems()
        Screen.MESSAGES -> messageItems()
        Screen.MESSAGE_THREAD -> messageThreadItems()
        Screen.MESSAGE_COMPOSE -> emptyList()
        Screen.CALENDAR -> calendarItems()
        else -> emptyList()
    }

    private fun title() = when (screen) {
        Screen.DEVICE -> "ניהול מכשיר"
        Screen.ZMANIM -> "זמני היום"
        Screen.CITY -> "בחירת עיר"
        Screen.APPS -> "אפליקציות"
        Screen.BLOCK -> "חסימת אפליקציות"
        Screen.PERMS -> "הרשאות והגדרות"
        Screen.HOMEBG -> "מסך הבית"
        Screen.DIALER -> "חיוג"
        Screen.CONTACTS -> { val cnt = (rows().size - 4).coerceAtLeast(0); (if (filterBuf.isEmpty()) "אנשי קשר" else "אנשי קשר · " + filterBuf) + " (" + cnt + ")" }
        Screen.CONTACTMGR -> "ניהול אנשי קשר"
        Screen.ACTIONS -> actionTitle
        Screen.CALLLOG -> "יומן שיחות"
        Screen.EDITMENU -> "עריכת תפריט"
        Screen.EDITITEM -> menuEntries().firstOrNull { it.first == editKey }?.let { mName(it.first, it.second) } ?: ""
        Screen.ICONPICK -> "בחירת אייקון"
        Screen.PICKAPP -> if (pickAdd) "הוספת אפליקציה לתפריט" else "בחירת אפליקציה"
        Screen.EDITAPPS -> "עריכת אפליקציות"
        Screen.SOUNDS -> "צלילים"
        Screen.SPEED -> "חיוג מהיר"
        Screen.HIDEAPPS -> "הסתרת אפליקציות"
        Screen.OPTIONS -> optTitle
        Screen.SHORTCUTS -> "קיצורי מקשים"
        Screen.SHORTCUTPICK -> "בחירת פעולה"
        Screen.THEMES -> "ערכת נושא"
        Screen.KEYMAP -> "מקשי אפשרויות וחזרה"
        Screen.ROOTMENU -> "רוט (Magisk)"
        Screen.RECENT -> "אפליקציות אחרונות"
        Screen.BT -> "בלוטות'"
        Screen.CALC -> "מחשבון"
        Screen.CHECK -> "בדיקת רכיבים"
        Screen.MESSAGES -> "הודעות"
        Screen.MESSAGE_THREAD -> smsThreadName.ifEmpty { smsThreadNumber }
        Screen.MESSAGE_COMPOSE -> "הודעה חדשה"
        Screen.CALENDAR -> "לוח שנה"
        else -> ""
    }

    // -- device management
    private fun ringerLabel() = when ((getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
        AudioManager.RINGER_MODE_VIBRATE -> "רטט"
        AudioManager.RINGER_MODE_SILENT -> "שקט"
        else -> "רגיל"
    }
    private fun cycleProfile() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (next == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
            toast("למצב שקט יש לאשר גישת 'נא לא להפריע'")
            go(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        try { am.ringerMode = next } catch (e: SecurityException) { toast("אין הרשאה לשנות פרופיל") }
        render()
    }
    private fun screenLabel() = when (Prefs.screenMode(this)) {
        1 -> "אנשי קשר בלבד"
        2 -> "חסימת מוסתרים"
        else -> "כבוי"
    }
    private fun cycleScreen() {
        val m = (Prefs.screenMode(this) + 1) % 3
        Prefs.setScreenMode(this, m)
        if (m != 0 && !callScreeningHeld()) toast("כדי שהסינון יפעל, הפעילו אותו ב'הרשאות והגדרות'")
        render()
    }
    private fun showReport(title: String, lines: List<String>) {
        optTitle = title; optItems = lines.map { Item(it) }
        open(Screen.OPTIONS)
    }
    private fun runRoot(title: String, job: () -> List<String>) {
        toast("מריץ... אם Magisk מציג חלון, אשר אותו")
        Thread {
            val l = try { job() } catch (t: Throwable) { listOf("✗ שגיאה: " + (t.message ?: t.javaClass.simpleName)) }
            runOnUiThread { showReport(title, l) }
        }.start()
    }
    private fun rootItems(): List<Item> {
        val hw = Prefs.custom(this, "hwtouch", "") == "1"
        val method = Prefs.custom(this, "hwMethod", "")
        return listOf(
            Item("בדיקת רוט (su)", "🔑", 0xFFE8F5E9.toInt()) { runRoot("בדיקת רוט") { Root.check() } },
            Item("פתיחת Device Security (התקנת Magisk)", "🛡", 0xFFFFF3E0.toInt()) { launchPkg("am.h10110000.roothider") },
            Item("הפעלת שירות הנגישות", "♿", 0xFFE3F2FD.toInt()) { runRoot("שירות הנגישות") { Root.enableAccessibility(this) } },
            Item("מתן כל ההרשאות", "✅", 0xFFE3F2FD.toInt()) { runRoot("הרשאות") { Root.grantAll(this) } },
            Item("מסך בית ברירת מחדל", "🏠", 0xFFE0F7FA.toInt()) { runRoot("מסך בית") { Root.setHome(this) } },
            Item("אפליקציית טלפון ברירת מחדל", "📞", 0xFFE0F7FA.toInt()) { runRoot("אפליקציית טלפון") { Root.setDialer(this) } },
            Item("הגדרה כ-Device Owner", "👑", 0xFFEDE7F6.toInt()) { confirm("להגדיר את הלאנצ'ר כ-Device Owner? הפעולה נכשלת אם יש חשבונות במכשיר.") { runRoot("Device Owner") { Root.setOwner(this) } } },
            Item("ביטול Device Owner", "↺", 0xFFFCE4EC.toInt()) { confirm("לבטל את ה-Device Owner?") { runRoot("ביטול Device Owner") { Root.removeOwner(this) } } },
            Item("בדיקת ניטרול מגע (חומרה)", "🧪", 0xFFFFF8E1.toInt(), if (method.isEmpty()) "לא נבדק" else "שיטה: " + method) {
                confirm("הבדיקה מכבה את המגע לשנייה ואז מחזירה אותו. להמשיך?") { runRoot("בדיקת מגע") { Root.testTouch(this) } }
            },
            Item("ניטרול מגע בחומרה בנעילת מגע", "🚫", 0xFFFFEBEE.toInt(), if (hw) "פעיל" else "כבוי") {
                if (!hw && method.isEmpty()) toast("קודם הרץ 'בדיקת ניטרול מגע'")
                else { Prefs.setCustom(this, "hwtouch", if (hw) "" else "1"); if (hw) runRoot("שחזור מגע") { Root.setTouch(this, false) } else render() }
            },
            Item("שחזור מגע עכשיו", "🩹", 0xFFE8F5E9.toInt()) { runRoot("שחזור מגע") { Root.setTouch(this, false) } }
        )
    }
    private fun deviceItems(): List<Item> = listOf(
        Item("רוט (Magisk)", "🔓", 0xFFEDE7F6.toInt(), if (Prefs.custom(this, "hwtouch", "") == "1") "ניטרול חומרה פעיל" else "") { open(Screen.ROOTMENU) },
        Item("פרופילים חכמים", "🌙", 0xFFE3F2FD.toInt(), ringerLabel()) { cycleProfile() },
        Item("סינון שיחות", "🛡", 0xFFE8F5E9.toInt(), screenLabel()) { cycleScreen() },
        Item("מסך הבית", "🖼", 0xFFE3F2FD.toInt(), bgNames[Prefs.homeBg(this).coerceIn(0, bgNames.size - 1)]) { open(Screen.HOMEBG) },
        Item("חיוג מהיר", "⚡", 0xFFFFF3E0.toInt()) { open(Screen.SPEED) },
        Item("כתובת ניהול כיתה", "🎓", 0xFFFFF3E0.toInt(), classUrl().removePrefix("https://")) { askText("כתובת האתר", classUrl()) { v -> if (v.isNotEmpty()) Prefs.setCustom(this, "classUrl", if (v.startsWith("http")) v else "https://" + v); render() } },
        Item("ערכת נושא", "🎨", 0xFFE3F2FD.toInt(), themes[Prefs.theme(this).coerceIn(0, themes.size - 1)].name) { open(Screen.THEMES) },
        Item("פריסת מסך הבית", "🧩", 0xFFE0F7FA.toInt(), listOf("קלאסית", "שעון גדול", "עליונה")[Prefs.homeLayout(this).coerceIn(0, 2)]) { Prefs.setHomeLayout(this, (Prefs.homeLayout(this) + 1) % 3); render() },
        Item("קיצורי מקשים", "⌨", 0xFFFFF8E1.toInt()) { open(Screen.SHORTCUTS) },
        Item("מקשי אפשרויות וחזרה", "🎛", 0xFFFFF3E0.toInt(), if (Prefs.custom(this, "kmList", "").isEmpty()) "ברירת מחדל" else "מותאם") { open(Screen.KEYMAP) },
        Item("אפליקציות אחרונות", "🕘", 0xFFF3E5F5.toInt()) { open(Screen.RECENT) },
        Item("החזקת Back באפליקציה = אחרונות", "↩", 0xFFE0F7FA.toInt(), if (Prefs.custom(this, "hbr", "1") == "1") "פעיל" else "כבוי") { Prefs.setCustom(this, "hbr", if (Prefs.custom(this, "hbr", "1") == "1") "0" else "1"); render() },
        Item("קריאת מסך", "🔊", 0xFFE8F5E9.toInt(), if (Prefs.tts(this)) "פעילה" else "כבויה") { toggleTts() },
        Item("הגדרות דיבור", "🗣", 0xFFE8F5E9.toInt()) { go(Intent("com.android.settings.TTS_SETTINGS")) },
        Item("עריכת תפריט", "✏", 0xFFFFF8E1.toInt()) { open(Screen.EDITMENU) },
        Item("עריכת אפליקציות", "📝", 0xFFF3E5F5.toInt()) { open(Screen.EDITAPPS) },
        Item("סגנון אפליקציות", "▦", 0xFFE0F7FA.toInt(), if (Prefs.drawer(this) == 1) "רשת" else "רשימה") { Prefs.setDrawer(this, 1 - Prefs.drawer(this)); render() },
        Item("צלילים", "🔔", 0xFFFFF3E0.toInt()) { open(Screen.SOUNDS) },
        Item("כיבוי מסך", "⏻", 0xFFFFEBEE.toInt()) { lockScreen() },
        Item("כיבוי אוטומטי", "⏱", 0xFFE0F7FA.toInt(), timeoutLabel()) { cycleTimeout() },
        Item("כיוון חיצים", "↔", 0xFFE3F2FD.toInt(), if (Prefs.arrowInv(this)) "הפוך" else "רגיל") { Prefs.setArrowInv(this, !Prefs.arrowInv(this)); render() },
        Item("נעילת מגע", "✋", 0xFFFFEBEE.toInt(), if (!Prefs.touchLock(this)) "כבויה" else if (Blocker.instance != null) "פעילה בכל המכשיר" else "פעילה (בלאנצ'ר בלבד)") { toggleTouchLock() },
        Item("דוח קריסה", "🐞", 0xFFFFEBEE.toInt(), if (CrashLog.last(this).isEmpty()) "אין" else "יש דוח") { showCrash() },
        Item("בדיקת רכיבים", "🩺", 0xFFE8F5E9.toInt()) { open(Screen.CHECK) },
        Item("הסתרת אפליקציות", "🙈", 0xFFF3E5F5.toInt(), Prefs.hiddenApps(this).size.toString() + " מוסתרות") { open(Screen.HIDEAPPS) },
        Item("חסימת אפליקציות", "🔒", 0xFFFFF3E0.toInt(), Prefs.blocked(this).size.toString() + " חסומות") { open(Screen.BLOCK) },
        Item("קוד מנהל", "🔑", 0xFFFFF8E1.toInt(), if (Prefs.hasPin(this)) "מוגדר" else "לא מוגדר") { startSetPin() },
        Item("הרשאות והגדרות", "✅", 0xFFE0F7FA.toInt()) { open(Screen.PERMS) },
        Item("הגדרות מערכת", "🔧", tint) { go(Intent(Settings.ACTION_SETTINGS)) }
    )

    // -- contacts, call log, actions
    private fun loadContactsAsync() {
        val token = ++contactsQueryToken
        Thread {
            val loaded = try { PhoneUtil.contacts(this) } catch (t: Throwable) { CrashLog.record(this, t); emptyList<PhoneUtil.Contact>() }
            runOnUiThread {
                if (token != contactsQueryToken) return@runOnUiThread
                contactsCache = loaded; contactsVer++
                contactLoading = false
                if (screen == Screen.CONTACTS || screen == Screen.DIALER) render()
            }
        }.start()
    }

    private fun contacts(): List<PhoneUtil.Contact> {
        contactsCache?.let { return it }
        if (!contactLoading) loadContactsAsync()
        return emptyList()
    }
    private fun contactItems(): List<Item> {
        if (contactLoading && contactsCache == null) return listOf(Item("טוען אנשי קשר…", "…", tint))
        val q = filterBuf.toString()
        val key = contactsVer.toString() + "|" + q + "|" + nameQuery + "|" + Prefs.sort(this)
        if (key == cRowsKey) return cRows
        val l = ArrayList<Item>()
        for (c in sortedContacts()) {
            if (!PhoneUtil.matches(c, q) || !PhoneUtil.matchesName(c, nameQuery)) continue
            val first = c.numbers.firstOrNull() ?: ""
            l.add(Item(c.name.ifEmpty { first }, if (c.starred) "⭐" else "👤", 0xFFFCE4EC.toInt(), fmtNum(first), data = first) { openContact(c) })
        }
        // actions live at the end (press ▲ from the top to reach them) so the list itself opens ready on the first contact
        l.add(Item("איש קשר חדש", "➕", 0xFFE8F5E9.toInt()) { newContact("") })
        l.add(Item("חיפוש לפי שם", "🔍", 0xFFE3F2FD.toInt(), nameQuery) {
            askText("חיפוש (חלק משם או מספר)", nameQuery) { v -> nameQuery = v; idx = 0; render() }
        })
        l.add(Item("מיון", "⇅", 0xFFFFF8E1.toInt(), sortLabel()) { Prefs.setSort(this, (Prefs.sort(this) + 1) % 3); idx = 0; render() })
        l.add(Item("ניהול: ייבוא, ייצוא, סנכרון", "⚙", 0xFFEDE7F6.toInt()) { open(Screen.CONTACTMGR) })
        cRowsKey = key; cRows = l
        return l
    }
    private fun contactMgrItems(): List<Item> = listOf(
        Item("סנכרון חשבונות", "🔄", 0xFFE3F2FD.toInt()) { syncContacts() },
        Item("רענון הרשימה", "↻", 0xFFE8F5E9.toInt()) { contactsCache = null; contactLoading = true; loadContactsAsync(); toast("מרענן…") },
        Item("ייצוא לקובץ (vcf)", "📤", 0xFFFFF8E1.toInt()) { exportContacts() },
        Item("ייבוא מקובץ (vcf)", "📥", 0xFFFCE4EC.toInt()) { importPick() },
        Item("חשבונות וסנכרון (הגדרות)", "🔧", 0xFFE0F2F1.toInt()) { go(Intent(Settings.ACTION_SYNC_SETTINGS)) }
    )
    private fun syncContacts() {
        ensurePerms(arrayOf(Manifest.permission.GET_ACCOUNTS)) {
            try {
                var n = 0
                for (a in android.accounts.AccountManager.get(this).accounts) {
                    if (android.content.ContentResolver.getIsSyncable(a, ContactsContract.AUTHORITY) <= 0) continue
                    val b = Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    }
                    android.content.ContentResolver.requestSync(a, ContactsContract.AUTHORITY, b); n++
                }
                if (n == 0) { toast("אין חשבון שמסנכרן אנשי קשר"); go(Intent(Settings.ACTION_SYNC_SETTINGS)) }
                else toast("מסנכרן $n חשבונות. הרשימה תתעדכן לבד")
            } catch (e: Exception) { toast("הסנכרון נכשל"); go(Intent(Settings.ACTION_SYNC_SETTINGS)) }
        }
    }
    private fun exportContacts() {
        ensurePerms(arrayOf(Manifest.permission.READ_CONTACTS)) {
            toast("מייצא…")
            Thread { val r = PhoneUtil.exportVcf(this); runOnUiThread { toast(r) } }.start()
        }
    }
    private fun importPick() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 30)
        } catch (e: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 30) }
            catch (e2: Exception) { toast("אין בורר קבצים במכשיר") }
        }
    }
    private fun importFrom(uri: Uri) {
        ensurePerms(arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)) {
            toast("מייבא…")
            Thread {
                val msg = try {
                    val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                    val cards = PhoneUtil.parseVcf(text)
                    if (cards.isEmpty()) "לא נמצאו אנשי קשר בקובץ"
                    else PhoneUtil.importCards(this, cards, contactsCache ?: PhoneUtil.contacts(this))
                } catch (e: Exception) { "הייבוא נכשל" }
                runOnUiThread { toast(msg); contactsCache = null; contactLoading = true; loadContactsAsync() }
            }.start()
        }
    }
    private fun openContact(c: PhoneUtil.Contact) {
        actionTitle = c.name.ifEmpty { c.numbers.firstOrNull() ?: "" }
        val l = ArrayList<Item>()
        for (n in c.numbers) l.add(Item("חיוג", "📞", 0xFFE8F5E9.toInt(), n) { placeCall(n) })
        c.numbers.firstOrNull()?.let { n -> l.add(Item("הודעה", "✉", 0xFFE3F2FD.toInt(), n) { sms(n) }) }
        l.add(Item(if (c.starred) "הסרה ממועדפים" else "הוספה למועדפים", "⭐", 0xFFFFF8E1.toInt()) {
            ensurePerms(arrayOf(Manifest.permission.WRITE_CONTACTS)) {
                val on = !c.starred
                PhoneUtil.setStarred(this, c.id, on); c.starred = on; contactsVer++
                toast(if (on) "נוסף למועדפים" else "הוסר ממועדפים"); back()
            }
        })
        l.add(Item("עריכה", "✏", 0xFFFFF8E1.toInt()) {
            optTitle = "עריכת " + c.name.ifEmpty { c.numbers.firstOrNull() ?: "" }; optItems = contactEditItems(c); open(Screen.OPTIONS)
        })
        actionItems = l
        open(Screen.ACTIONS)
    }
    private fun logItems(): List<Item> {
        val recs = logCache ?: PhoneUtil.callLog(this).also { logCache = it }
        if (recs.isEmpty()) return listOf(Item("אין שיחות"))
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        return recs.map { r ->
            val icon = when (r.type) {
                CallLog.Calls.INCOMING_TYPE -> "📥"
                CallLog.Calls.OUTGOING_TYPE -> "📤"
                CallLog.Calls.MISSED_TYPE -> "❌"
                else -> "🚫"
            }
            val title = r.name ?: if (r.number.isEmpty()) "מספר חסוי" else r.number
            Item(title, icon, 0xFFE3F2FD.toInt(), fmt.format(Date(r.date)), data = r.number) { openLogEntry(r) }
        }
    }
    private fun openLogEntry(r: PhoneUtil.Rec) {
        if (r.number.isEmpty()) { toast("מספר חסוי"); return }
        actionTitle = r.name ?: r.number
        val l = ArrayList<Item>()
        l.add(Item("חיוג", "📞", 0xFFE8F5E9.toInt(), r.number) { placeCall(r.number) })
        l.add(Item("הודעה", "✉", 0xFFE3F2FD.toInt(), r.number) { sms(r.number) })
        if (r.name == null) l.add(Item("שמירה כאיש קשר", "➕", 0xFFFFF8E1.toInt()) { newContact(r.number) })
        actionItems = l
        open(Screen.ACTIONS)
    }

    // -- home screen backgrounds
    private fun homeBgItems(): List<Item> {
        val cur = Prefs.homeBg(this)
        return bgNames.mapIndexed { i, n ->
            Item(n, "🖼", 0xFFE3F2FD.toInt(), if (i == cur) "✓" else "") { Prefs.setHomeBg(this, i); back() }
        }
    }
    private fun homeBgColors(): IntArray = when (Prefs.homeBg(this)) {
        1 -> intArrayOf(0xFF2E9E6B.toInt(), 0xFF0B3D2E.toInt())
        2 -> intArrayOf(0xFF3A3F4C.toInt(), 0xFF0B0C10.toInt())
        3 -> intArrayOf(Color.WHITE, 0xFFE8F1F6.toInt())
        else -> th().home
    }

    // -- zmanim
    private fun zmanimItems(): List<Item> {
        val city = Zmanim.cities[Prefs.city(this).coerceIn(0, Zmanim.cities.size - 1)]
        val l = ArrayList<Item>()
        l.add(Item("עיר", "📍", 0xFFE3F2FD.toInt(), city.name) { open(Screen.CITY) })
        for ((n, t) in Zmanim.list(city, Calendar.getInstance())) l.add(Item(n, "", tint, t))
        return l
    }
    private fun cityItems(): List<Item> {
        val cur = Prefs.city(this)
        return Zmanim.cities.mapIndexed { i, c ->
            Item(c.name, "📍", 0xFFE3F2FD.toInt(), if (i == cur) "✓" else "") { Prefs.setCity(this, i); back() }
        }
    }

    // -- apps
    private fun settingsPkg(): String? =
        packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    private fun apps(): List<App> {
        appCache?.let { return it }
        val pm = packageManager
        val sp = settingsPkg()
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName != sp }
            .distinctBy { it.activityInfo.packageName }
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label }
        appCache = l
        return l
    }
    private fun appItems(): List<Item> {
        val blocked = Prefs.blocked(this)
        val hiddenA = Prefs.hiddenApps(this)
        val l = apps().filter { !blocked.contains(it.pkg) && !hiddenA.contains(it.pkg) }.map { a ->
            Item(Prefs.custom(this, "an:" + a.pkg, a.label), ico = a.icon, data = a.pkg) {
                val i = packageManager.getLaunchIntentForPackage(a.pkg)
                if (i != null) go(i) else toast("לא זמין במכשיר")
            }
        }
        return if (l.isEmpty()) listOf(Item("אין אפליקציות")) else l
    }
    private fun blockItems(): List<Item> {
        val blocked = Prefs.blocked(this)
        val all = apps().filter { it.pkg != packageName }
        val l = ArrayList<Item>()
        l.add(Item("סימון הכל", "☑", 0xFFFFEBEE.toInt(), "חסימת כל האפליקציות") { Prefs.setBlocked(this, all.map { it.pkg }.toSet()); render() })
        l.add(Item("ניקוי הכל", "☐", 0xFFE8F5E9.toInt(), "שחרור כל החסימות") { Prefs.setBlocked(this, emptySet()); render() })
        for (a in all) l.add(Item(Prefs.custom(this, "an:" + a.pkg, a.label), sub = if (blocked.contains(a.pkg)) "☑ חסומה" else "☐ מותרת", ico = a.icon) {
            Prefs.toggleBlocked(this, a.pkg)
            if (!blockerEnabled()) toast("החסימה תיכנס לפעולה אחרי הפעלה ב'הרשאות והגדרות'")
            render()
        })
        return l
    }
    private fun hideItems(): List<Item> {
        val hidden = Prefs.hiddenApps(this)
        val all = apps().filter { it.pkg != packageName }
        val l = ArrayList<Item>()
        l.add(Item("סימון הכל", "☑", 0xFFF3E5F5.toInt(), "הסתרת כל האפליקציות") { Prefs.setHiddenApps(this, all.map { it.pkg }.toSet()); render() })
        l.add(Item("ניקוי הכל", "☐", 0xFFE8F5E9.toInt(), "הצגת כל האפליקציות") { Prefs.setHiddenApps(this, emptySet()); render() })
        for (a in all) l.add(Item(Prefs.custom(this, "an:" + a.pkg, a.label), sub = if (hidden.contains(a.pkg)) "☑ מוסתרת" else "☐ מוצגת", ico = a.icon) {
            Prefs.toggleHiddenApp(this, a.pkg); render()
        })
        return l
    }

    // -- permissions & defaults
    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(cat(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }
    private fun requestHome() {
        if (isDefaultHome()) { toast("כבר מוגדר כמסך הבית"); return }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 2); return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(cat(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { toast("לא זמין במכשיר") }
        }
    }
    private fun phonePerms() = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
    private fun isDefaultDialer(): Boolean = try {
        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == packageName
    } catch (e: Exception) { false }
    private fun requestDialer() {
        if (isDefaultDialer()) { toast("כבר פעיל"); return }
        toast("לאחר האישור, מסך השיחה יהיה של M5S Launcher")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER), 4)
            } else {
                startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
            }
        } catch (e: Exception) { toast("לא זמין במכשיר") }
    }
    private fun callScreeningHeld(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) } catch (e: Exception) { false }
    }
    private fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT < 29) { toast("סינון שיחות דורש אנדרואיד 10 ומעלה"); return }
        if (callScreeningHeld()) { toast("כבר פעיל"); return }
        try {
            val rm = getSystemService(RoleManager::class.java)
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 3)
        } catch (e: Exception) { toast("לא זמין במכשיר") }
    }
    private fun blockerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true
    private fun adminComp() = ComponentName(this, "il.m5s.launcher.AdminReceiver")
    private fun adminActive() = (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComp())
    private fun requestAdmin() {
        if (adminActive()) { toast("כבר פעיל"); return }
        go(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "מונע הסרה של M5S Launcher בלי להסיר קודם את ההרשאה הזו."))
    }
    private fun permItems(): List<Item> {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return listOf(
            Item("מסך בית ברירת מחדל", "🏠", 0xFFE3F2FD.toInt(), yn(isDefaultHome())) { requestHome() },
            Item("הרשאות טלפון (אנשי קשר, חיוג, יומן)", "👥", 0xFFFCE4EC.toInt(), yn(phonePerms().all { has(it) })) {
                requestPermissions(phonePerms(), 1)
            },
            Item("הרשאות הודעות", "✉", 0xFFE3F2FD.toInt(), yn(has(Manifest.permission.READ_SMS) && has(Manifest.permission.SEND_SMS))) {
                requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS), 11)
            },
            Item("הרשאות לוח שנה", "📅", 0xFFFFF3E0.toInt(), yn(has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR))) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 12)
            },
            Item("אפליקציית טלפון (חיוג ושיחה)", "☎", 0xFFE8F5E9.toInt(), yn(isDefaultDialer())) { requestDialer() },
            Item("הפעלת סינון שיחות", "🛡", 0xFFE8F5E9.toInt(), yn(callScreeningHeld())) { requestScreeningRole() },
            Item("הפעלת חסימת אפליקציות", "🔒", 0xFFFFF3E0.toInt(), yn(blockerEnabled())) {
                toast("מצאו את M5S Launcher ברשימה והפעילו")
                go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Item("הגנה מהסרה (מנהל מכשיר)", "🧷", 0xFFEDE7F6.toInt(), yn(adminActive())) { requestAdmin() },
            Item("גישת נא לא להפריע", "🔕", 0xFFE0F7FA.toInt(), yn(nm.isNotificationPolicyAccessGranted)) {
                go(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        )
    }

    // ---------- editing, sounds, drawer style ----------
    private var editKey = ""
    private val iconChoices = listOf("☎","📞","👤","✉","⏰","📅","🧮","🕯","🎙","📱","⚙","🔧","📖","🕍","🎵","📷","🗺","⭐","❤","🏠","📝","🔔","🔦","💡","📚","🌙","☀","🔒","📡","🛒","🚗","🎓","📻","💬","🔍","📍")
    private fun isGrid() = screen == Screen.MENU || screen == Screen.ICONPICK || (screen == Screen.APPS && Prefs.drawer(this) == 1)

    private var textTitle = ""
    private val textBuf = StringBuilder()
    private var textOk: ((String) -> Unit)? = null
    private var textMode = 0
    private var mtKey = -1
    private var mtIdx = 0
    private var mtTime = 0L
    // keypad-only text entry (no soft keyboard): multi-tap Hebrew / English / digits
    private fun askText(title: String, cur: String, onOk: (String) -> Unit) {
        textTitle = title; textBuf.setLength(0); textBuf.append(cur); textOk = onOk; textMode = 0; mtKey = -1
        open(Screen.TEXT)
    }
    private fun mtChars(d: Int): String {
        val he = arrayOf(" 0", ".,?!-1", "דהו2", "אבג3", "מםנן4", "יכךל5", "זחט6", "רשת7", "צקץ8", "סעפף9")
        val en = arrayOf(" 0", ".,@?!'1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")
        return when (textMode) { 0 -> he[d]; 1 -> en[d]; else -> d.toString() }
    }
    private fun textConfirm() { val v = textBuf.toString().trim(); val cb = textOk; back(); cb?.invoke(v) }
    private fun textKey(code: Int, down: Boolean): Boolean {
        when {
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (down) {
                val chars = mtChars(code - KeyEvent.KEYCODE_0); val now = SystemClock.uptimeMillis()
                if (chars.length > 1 && code == mtKey && now - mtTime < 1000 && textBuf.isNotEmpty()) {
                    mtIdx = (mtIdx + 1) % chars.length; textBuf.setCharAt(textBuf.length - 1, chars[mtIdx])
                } else if (textBuf.length < 120) { mtIdx = 0; textBuf.append(chars[0]); mtKey = code }
                mtTime = now; render()
            }
            code == KeyEvent.KEYCODE_STAR -> if (down) { if (textBuf.isNotEmpty()) textBuf.deleteCharAt(textBuf.length - 1); mtKey = -1; render() }
            code == KeyEvent.KEYCODE_POUND -> if (down) { textMode = (textMode + 1) % 3; mtKey = -1; render() }
            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER -> if (down) textConfirm()
            code == KeyEvent.KEYCODE_BACK -> if (down) backAction()
            code == KeyEvent.KEYCODE_ENDCALL -> if (down) goHome()
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> { }
            else -> return false
        }
        return true
    }
    private fun textView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        col.addView(tv(textTitle, 17f, ink, bold = true, g = Gravity.START))
        col.addView(tv(textBuf.toString() + "▏", 28f, ink, bold = true, g = Gravity.START).apply { setPadding(0, dp(16), 0, dp(10)); minHeight = dp(90) })
        col.addView(tv(listOf("עברית", "English", "123")[textMode] + "   (# להחלפה)", 15f, 0xFF1B7F96.toInt(), g = Gravity.START))
        col.addView(tv("הקשה חוזרת על 2–9 = אות הבאה · * מחק · OK אישור · Back ביטול", 12f, soft, g = Gravity.START).apply { setPadding(0, dp(14), 0, 0) })
        return col
    }

    private fun editMenuItems(): List<Item> {
        val l = ArrayList<Item>()
        l.add(Item("הוספת אפליקציה", "➕", 0xFFE8F5E9.toInt()) { pickAdd = true; open(Screen.PICKAPP) })
        for (e in menuEntries()) {
            val k = e.first; val b = e.second
            val t = if (k.startsWith("pkg:")) "" else targetOf(k)
            val sub = if (Prefs.hiddenMenu(this).contains(k)) "מוסתר" else if (k.startsWith("pkg:")) "אפליקציה"
                else if (t.isNotEmpty()) (appByPkg(t)?.label ?: "אפליקציה") else ""
            l.add(Item(mName(k, b), mIcon(k, b), b.tint, sub, b.ico) { editKey = k; open(Screen.EDITITEM) })
        }
        return l
    }
    private fun pickAppItems(): List<Item> {
        val l = ArrayList<Item>()
        if (!pickAdd) l.add(Item("מובנית (ברירת מחדל)", "↺", 0xFFFCE4EC.toInt()) { Prefs.setCustom(this, "t:" + editKey, null); toast("הוחזר"); back() })
        val extra = extraList()
        for (a in apps()) {
            if (pickAdd && a.pkg == packageName) continue
            l.add(Item(Prefs.custom(this, "an:" + a.pkg, a.label), sub = if (pickAdd && extra.contains(a.pkg)) "בתפריט" else "", ico = a.icon) {
                if (pickAdd) { if (!extra.contains(a.pkg)) setExtraList(extra + a.pkg); toast("נוסף לתפריט") }
                else { Prefs.setCustom(this, "t:" + editKey, a.pkg); toast("נשמר") }
                back()
            })
        }
        return l
    }
    private fun moveEntry(k: String, d: Int) {
        val keys = menuEntries().map { it.first }.toMutableList()
        val i = keys.indexOf(k); val j = i + d
        if (i < 0 || j < 0 || j >= keys.size) return
        keys[i] = keys[j].also { keys[j] = keys[i] }
        Prefs.setOrder(this, keys); render()
    }
    private fun editItemItems(): List<Item> {
        val ent = menuEntries().firstOrNull { it.first == editKey } ?: return emptyList()
        val k = ent.first; val b = ent.second
        val hid = Prefs.hiddenMenu(this).contains(k)
        val extra = k.startsWith("pkg:")
        val l = ArrayList<Item>()
        l.add(Item("שם", "✏", 0xFFFFF8E1.toInt(), mName(k, b)) {
            askText("שם הפריט", mName(k, b)) { v -> Prefs.setCustom(this, "n:" + k, v.ifEmpty { null }); render() }
        })
        if (!extra) l.add(Item("אייקון", "🎨", 0xFFE3F2FD.toInt(), mIcon(k, b)) { open(Screen.ICONPICK) })
        l.add(Item("הצגה בתפריט", "👁", 0xFFE8F5E9.toInt(), if (hid) "מוסתר" else "מוצג") {
            if (k == "ניהול מכשיר") toast("אי אפשר להסתיר את ניהול המכשיר")
            else { Prefs.toggleHiddenMenu(this, k); render() }
        })
        if (!extra && k != "ניהול מכשיר") l.add(Item("אפליקציה", "📱", 0xFFF3E5F5.toInt(),
            targetOf(k).let { t -> if (t.isEmpty()) "מובנית" else (appByPkg(t)?.label ?: "אפליקציה") }) { pickAdd = false; open(Screen.PICKAPP) })
        l.add(Item("הזזה למעלה", "▲", 0xFFE0F7FA.toInt()) { moveEntry(k, -1) })
        l.add(Item("הזזה למטה", "▼", 0xFFE0F7FA.toInt()) { moveEntry(k, 1) })
        if (extra) l.add(Item("הסרה מהתפריט", "🗑", 0xFFFCE4EC.toInt()) {
            setExtraList(extraList() - k.removePrefix("pkg:")); toast("הוסר"); back()
        })
        else l.add(Item("איפוס", "↺", 0xFFFCE4EC.toInt()) {
            Prefs.setCustom(this, "n:" + k, null); Prefs.setCustom(this, "i:" + k, null); Prefs.setCustom(this, "t:" + k, null)
            if (hid) Prefs.toggleHiddenMenu(this, k)
            toast("אופס"); render()
        })
        return l
    }
    private fun iconPickItems(): List<Item> = iconChoices.map { e ->
        Item("", e, 0xFFE3F2FD.toInt()) { Prefs.setCustom(this, "i:" + editKey, e); back() }
    }
    private fun editAppItems(): List<Item> = apps().map { a ->
        val cn = Prefs.custom(this, "an:" + a.pkg, "")
        Item(if (cn.isEmpty()) a.label else cn, sub = if (cn.isEmpty()) "" else "שונה", ico = a.icon) {
            askText("שם האפליקציה", cn.ifEmpty { a.label }) { v -> Prefs.setCustom(this, "an:" + a.pkg, v.ifEmpty { null }); render() }
        }
    }

    private val soundTypes = listOf(
        RingtoneManager.TYPE_RINGTONE to "צלצול", RingtoneManager.TYPE_NOTIFICATION to "הודעות", RingtoneManager.TYPE_ALARM to "שעון מעורר"
    )
    private fun soundName(type: Int): String = try {
        val u = RingtoneManager.getActualDefaultRingtoneUri(this, type)
        if (u == null) "שקט" else RingtoneManager.getRingtone(this, u)?.getTitle(this) ?: "ברירת מחדל"
    } catch (e: Exception) { "—" }
    private fun soundItems(): List<Item> = soundTypes.mapIndexed { i, tn ->
        Item(tn.second, "🔔", 0xFFFFF8E1.toInt(), soundName(tn.first)) { pickSound(i) }
    }
    private fun pickSound(i: Int) {
        if (!Settings.System.canWrite(this)) {
            toast("יש לאשר שינוי הגדרות מערכת")
            go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + packageName)))
            return
        }
        val t = soundTypes[i].first
        val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, t)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, soundTypes[i].second)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, RingtoneManager.getActualDefaultRingtoneUri(this, t))
        try { startActivityForResult(pick, 20 + i) } catch (e: Exception) { toast("לא זמין במכשיר") }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30 && resultCode == RESULT_OK && data?.data != null) importFrom(data.data!!)
        if (requestCode in 20..22 && resultCode == RESULT_OK && data != null) {
            val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            try { RingtoneManager.setActualDefaultRingtoneUri(this, soundTypes[requestCode - 20].first, uri); toast("נשמר") }
            catch (e: Exception) { toast("לא ניתן לשמור") }
        }
        render()
    }

    // ---------- Built-in SMS and calendar ----------
    private fun openMessages() {
        ensurePerms(arrayOf(Manifest.permission.READ_SMS)) { smsCache = loadSms(); open(Screen.MESSAGES) }
    }
    private fun loadSms(): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        try {
            contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < 200) {
                    val address = c.getString(1) ?: ""
                    val name = PhoneUtil.lookupName(this, address)
                    out.add(SmsRow(c.getLong(0), address, c.getString(2) ?: "", c.getLong(3), c.getInt(4), name))
                }
            }
        } catch (_: Exception) {}
        return out
    }
    private fun messageItems(): List<Item> {
        val latest = LinkedHashMap<String, SmsRow>()
        smsCache.forEach { if (!latest.containsKey(it.address)) latest[it.address] = it }
        val l = ArrayList<Item>()
        l.add(Item("הודעה חדשה", "✉", 0xFFE3F2FD.toInt()) { smsThreadNumber = ""; smsThreadName = "הודעה חדשה"; smsBody = ""; open(Screen.MESSAGE_COMPOSE) })
        if (latest.isEmpty()) l.add(Item("אין הודעות", "", tint))
        latest.values.forEach { r ->
            val title = r.name ?: r.address
            l.add(Item(title, "✉", 0xFFE3F2FD.toInt(), r.body.take(30), data = r.address) { smsThreadNumber = r.address; smsThreadName = title; open(Screen.MESSAGE_THREAD) })
        }
        return l
    }
    private fun messageThreadItems(): List<Item> = listOf(
        Item("הודעה חדשה", "✉", 0xFFE3F2FD.toInt()) { smsBody = ""; open(Screen.MESSAGE_COMPOSE) },
        Item("חיוג", "☎", 0xFFE8F5E9.toInt(), smsThreadNumber) { placeCall(smsThreadNumber) },
        Item("חזרה להודעות", "↩", tint) { back() }
    )
    private fun composeSms() {
        if (smsThreadNumber.isBlank()) {
            askText("מספר יעד", "") { n -> smsThreadNumber = n; render() }
            return
        }
        askText("תוכן ההודעה", smsBody) { body ->
            if (body.isBlank()) return@askText
            ensurePerms(arrayOf(Manifest.permission.SEND_SMS)) {
                try {
                    SmsManager.getDefault().sendTextMessage(smsThreadNumber, null, body, null, null)
                    smsBody = ""
                    smsCache = loadSms()
                    toast("ההודעה נשלחה")
                    if (screen != Screen.MESSAGE_THREAD) back()
                    render()
                } catch (_: Exception) { toast("לא ניתן לשלוח הודעה") }
            }
        }
    }
    private fun messageComposeView(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        c.addView(tv("הודעה חדשה", 22f, ink, bold = true, g = Gravity.START))
        c.addView(tv(if (smsThreadNumber.isBlank()) "ללא נמען" else (smsThreadName.ifBlank { smsThreadNumber }), 18f, soft, g = Gravity.START).apply { setPadding(0, dp(12), 0, dp(12)) })
        c.addView(tv(if (smsBody.isBlank()) "לחצו אישור לכתיבת הודעה" else smsBody, 18f, ink, g = Gravity.START).apply { setPadding(dp(8), dp(12), dp(8), dp(12)); background = GradientDrawable().apply { setColor(0xFFF3F7F9.toInt()); cornerRadius = dp(8).toFloat() } })
        c.addView(tv("OK = כתיבה ושליחה\nימני = חזרה", 13f, soft).apply { setPadding(0, dp(18), 0, 0) })
        return c
    }
    private fun openCalendar() {
        ensurePerms(arrayOf(Manifest.permission.READ_CALENDAR)) { open(Screen.CALENDAR) }
    }
    private fun calendarItems(): List<Item> {
        val first = Calendar.getInstance().apply { set(calendarYear, calendarMonth, 1) }
        val max = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDow = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val out = ArrayList<Item>()
        out.add(Item("חודש קודם", "‹", tint) { shiftMonth(-1) })
        out.add(Item("${monthName(calendarMonth)} $calendarYear", "", 0xFFE3F2FD.toInt()))
        out.add(Item("חודש הבא", "›", tint) { shiftMonth(1) })
        out.add(Item("א ב ג ד ה ו ש", "", tint))
        repeat(firstDow) { out.add(Item("", "", tint)) }
        for (d in 1..max) {
            val day = d
            out.add(Item(day.toString(), "", 0xFFE3F1FA.toInt(), calendarEventsLabel(calendarYear, calendarMonth, day)) { showDayEvents(day) })
        }
        return out
    }
    private fun monthName(m: Int) = listOf("ינואר","פברואר","מרץ","אפריל","מאי","יוני","יולי","אוגוסט","ספטמבר","אוקטובר","נובמבר","דצמבר")[m]
    private fun shiftMonth(delta: Int) { calendarMonth += delta; if (calendarMonth < 0) { calendarMonth = 11; calendarYear-- }; if (calendarMonth > 11) { calendarMonth = 0; calendarYear++ }; idx = 0; render() }
    private fun calendarEventsLabel(y: Int, m: Int, d: Int): String {
        if (!has(Manifest.permission.READ_CALENDAR)) return ""
        val start = Calendar.getInstance().apply { set(y,m,d,0,0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
        val end = start + 24*60*60*1000
        return try {
            contentResolver.query(android.provider.CalendarContract.Events.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Events.TITLE), "dtstart < ? AND dtend > ?", arrayOf(end.toString(), start.toString()), null)?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
    }
    private fun showDayEvents(day: Int) {
        val y=calendarYear; val m=calendarMonth
        val start = Calendar.getInstance().apply { set(y,m,day,0,0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
        val end = start + 24*60*60*1000
        val titles=ArrayList<String>()
        try { contentResolver.query(android.provider.CalendarContract.Events.CONTENT_URI, arrayOf(android.provider.CalendarContract.Events.TITLE), "dtstart < ? AND dtend > ?", arrayOf(end.toString(), start.toString()), null)?.use { c -> while(c.moveToNext()) titles.add(c.getString(0) ?: "") } } catch (_: Exception) {}
        AlertDialog.Builder(this).setTitle("$day ${monthName(m)}").setMessage(if (titles.isEmpty()) "אין אירועים" else titles.joinToString("\n")).setPositiveButton("אישור", null).show()
    }

    // ---------- 1.7: fallbacks, calculator, screen off, contacts sort ----------
    private var nameQuery = ""
    private fun sortLabel() = when (Prefs.sort(this)) { 1 -> "ת–א"; 2 -> "מועדפים תחילה"; else -> "א–ת" }
    private fun sortedContacts(): List<PhoneUtil.Contact> {
        val l = contacts()
        return when (Prefs.sort(this)) { 1 -> l.reversed(); 2 -> l.sortedByDescending { it.starred }; else -> l }
    }

    private class Tgt(val name: String, val intents: List<Intent>, val pkgs: List<String>, val builtin: (() -> Unit)? = null)
    private val tMsg by lazy { Tgt("הודעות", listOf(cat(Intent.CATEGORY_APP_MESSAGING), Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
        listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "com.android.messaging")) }
    private val tClock by lazy { Tgt("שעון", listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS), Intent(AlarmClock.ACTION_SET_ALARM)),
        listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage")) }
    private val tCal by lazy { Tgt("לוח שנה", listOf(cat(Intent.CATEGORY_APP_CALENDAR)),
        listOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar")) }
    private val tCalc by lazy { Tgt("מחשבון", listOf(cat(Intent.CATEGORY_APP_CALCULATOR)),
        listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator", "com.miui.calculator"),
        builtin = { calcReset(); open(Screen.CALC) }) }
    private val tRec by lazy { Tgt("רשמקול", listOf(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)),
        listOf("com.google.android.apps.recorder", "com.android.soundrecorder", "com.sec.android.app.voicenote")) }

    private fun resolveTarget(t: Tgt): Intent? {
        for (i in t.intents) if (packageManager.resolveActivity(i, 0) != null) return i
        for (p in t.pkgs) packageManager.getLaunchIntentForPackage(p)?.let { return it }
        return null
    }
    private fun launch(t: Tgt) {
        val i = resolveTarget(t)
        if (i != null) { try { startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { } }
        val b = t.builtin
        if (b != null) b()
        else AlertDialog.Builder(this).setTitle(t.name).setMessage("האפליקציה לא מותקנת או לא זמינה במכשיר")
            .setPositiveButton("אישור", null).show()
    }
    private fun checkItems(): List<Item> {
        fun st(t: Tgt) = if (resolveTarget(t) != null) "זמין ✓" else if (t.builtin != null) "מובנה ✓" else "חסר ✗"
        val settingsOk = packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0) != null
        return listOf(tMsg, tClock, tCal, tCalc, tRec).map { t -> Item(t.name, "", tint, st(t)) { launch(t) } } +
            Item("הגדרות מערכת", "", tint, if (settingsOk) "זמין ✓" else "חסר ✗") { go(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try { if (dpm.isAdminActive(adminComp())) { dpm.lockNow(); return } } catch (e: SecurityException) { }
        val b = Blocker.instance
        if (b != null && Build.VERSION.SDK_INT >= 28 &&
            b.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return
        AlertDialog.Builder(this).setTitle("כיבוי מסך")
            .setMessage("כדי לכבות את המסך מהלאנצ'ר, הפעילו 'הגנה מהסרה (מנהל מכשיר)' או 'חסימת אפליקציות' במסך 'הרשאות והגדרות'.")
            .setPositiveButton("להגדרות") { _, _ -> open(Screen.PERMS) }.setNegativeButton("ביטול", null).show()
    }
    private val timeouts = listOf(15000 to "15 שניות", 30000 to "30 שניות", 60000 to "דקה", 120000 to "2 דקות", 300000 to "5 דקות", 600000 to "10 דקות")
    private fun curTimeout(): Int = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) } catch (e: Exception) { -1 }
    private fun timeoutLabel(): String {
        val v = curTimeout()
        return timeouts.firstOrNull { it.first == v }?.second ?: if (v > 0) (v / 1000).toString() + " שנ'" else "—"
    }
    private fun cycleTimeout() {
        if (!Settings.System.canWrite(this)) {
            toast("יש לאשר שינוי הגדרות מערכת")
            go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + packageName)))
            return
        }
        val i = timeouts.indexOfFirst { it.first == curTimeout() }
        try { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeouts[(i + 1) % timeouts.size].first) }
        catch (e: Exception) { toast("לא ניתן לשנות") }
        render()
    }

    // built-in calculator: digits, * = point, arrows = operators, OK = equals, # = clear
    private val calcBuf = StringBuilder()
    private var calcAcc: Double? = null
    private var calcOp = ' '
    private fun calcReset() { calcBuf.setLength(0); calcAcc = null; calcOp = ' ' }
    private fun calcApply() {
        val b = calcBuf.toString().toDoubleOrNull() ?: return
        val a = calcAcc
        calcAcc = if (a == null || calcOp == ' ') b else when (calcOp) {
            '+' -> a + b
            '−' -> a - b
            '×' -> a * b
            else -> if (b == 0.0) Double.NaN else a / b
        }
        calcBuf.setLength(0)
    }
    private fun calcOpKey(ch: Char) {
        if (calcBuf.isNotEmpty()) { calcApply(); calcOp = ch } else if (calcAcc != null) calcOp = ch
    }
    private fun calcFmt(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "שגיאה"
        if (d == Math.floor(d) && Math.abs(d) < 1e12) return d.toLong().toString()
        return String.format(Locale.US, "%.8f", d).trimEnd('0').trimEnd('.')
    }
    private fun calcKey(code: Int, down: Boolean): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) { if (down) backAction(); return true }
        if (code == KeyEvent.KEYCODE_ENDCALL) { if (down) goHome(); return true }
        val handled = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in listOf(
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
        if (!handled) return false
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_STAR -> if (!calcBuf.contains('.')) { if (calcBuf.isEmpty()) calcBuf.append('0'); calcBuf.append('.') }
            KeyEvent.KEYCODE_POUND -> calcReset()
            KeyEvent.KEYCODE_DPAD_UP -> calcOpKey('+')
            KeyEvent.KEYCODE_DPAD_DOWN -> calcOpKey('−')
            KeyEvent.KEYCODE_DPAD_LEFT -> calcOpKey('÷')
            KeyEvent.KEYCODE_DPAD_RIGHT -> calcOpKey('×')
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { calcApply(); calcOp = ' ' }
            else -> if (calcBuf.length < 12) {
                if (calcOp == ' ' && calcBuf.isEmpty()) calcAcc = null
                calcBuf.append(code - KeyEvent.KEYCODE_0)
            }
        }
        render()
        return true
    }
    private fun calcView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        col.addView(tv("מחשבון", 22f, ink, bold = true, g = Gravity.START))
        val line1 = ((calcAcc?.let { calcFmt(it) } ?: "") + " " + (if (calcOp != ' ') calcOp.toString() else "")).trim()
        col.addView(tv(line1, 20f, soft, g = Gravity.END).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(0, dp(18), 0, 0) })
        col.addView(tv(if (calcBuf.isEmpty()) "0" else calcBuf.toString(), 44f, ink, bold = true, g = Gravity.END)
            .apply { layoutDirection = View.LAYOUT_DIRECTION_LTR })
        col.addView(tv("▲ +    ▼ −    ◀ ÷    ▶ ×\n*  נקודה      #  איפוס      OK  =", 15f, soft).apply { setPadding(0, dp(24), 0, 0) })
        return col
    }

    // ---------- 2.0: touch lock, crash report, drawable cache, scroll ----------
    private var listScroll = 0
    private val dcache = HashMap<Int, Drawable.ConstantState>()
    private fun cachedDrawable(id: Int): Drawable? {
        if (id == 0) return null
        val cs = dcache[id] ?: (resources.getDrawable(id, theme).constantState ?: return resources.getDrawable(id, theme)).also { dcache[id] = it }
        return cs.newDrawable(resources)
    }
    private fun applyTouchLock() {
        if (Prefs.touchLock(this)) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
    private fun toggleTouchLock() {
        val v = !Prefs.touchLock(this)
        Prefs.setTouchLock(this, v); applyTouchLock(); Blocker.instance?.applyGuard()
        if (v && Blocker.instance == null) {
            AlertDialog.Builder(this).setTitle("נעילת מגע")
                .setMessage("הנעילה פעילה בתוך הלאנצ'ר בלבד. כדי שתחול על כל המכשיר, הפעילו את שירות הנגישות של הלאנצ'ר (הגדרות נגישות ← M5S Launcher).")
                .setPositiveButton("להגדרות נגישות") { _, _ -> go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("סגירה", null).show()
        } else toast(if (v) "נעילת מגע פעילה בכל המכשיר. ביטול: ניהול מכשיר, או הקשה * * # # 0" else "נעילת מגע כבויה")
        render()
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Prefs.touchLock(this)) return true
        return try { super.dispatchTouchEvent(ev) } catch (t: Throwable) { CrashLog.record(this, t); true }
    }
    private fun showCrash() {
        val t = CrashLog.last(this)
        if (t.isEmpty()) { toast("אין דוח קריסה"); return }
        val tvw = TextView(this).apply { text = t; textSize = 11f; setPadding(dp(10), dp(6), dp(10), dp(6)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        AlertDialog.Builder(this).setTitle("דוח קריסה אחרון").setView(ScrollView(this).apply { addView(tvw) })
            .setPositiveButton("העתקה") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash", t))
                toast("הועתק")
            }
            .setNegativeButton("מחיקה") { _, _ -> CrashLog.clear(this); render() }
            .setNeutralButton("סגירה", null).show()
    }

    // ---------- 5.0: themes, shortcuts, options menu, recents, screen reader, contact editing ----------
    private class Theme(val name: String, val ink: Int, val soft: Int, val cyan: Int, val band: Int, val panel: Int, val sel: Int, val home: IntArray)
    private fun hx(v: Long) = v.toInt()
    private val themes by lazy { listOf(
        Theme("תכלת", hx(0xFF0F5360), hx(0xFF6C8A93), hx(0xFF1FB5D6), hx(0xFFC9DEE8), hx(0xFFFFFFFF), hx(0xFFE3F1FA), intArrayOf(hx(0xFF3D8FDB), hx(0xFF0A2552))),
        Theme("ירוק", hx(0xFF0B4A33), hx(0xFF5E8272), hx(0xFF2E9E6B), hx(0xFFCBE8D8), hx(0xFFFAFDFB), hx(0xFFDDF3E7), intArrayOf(hx(0xFF2E9E6B), hx(0xFF0B3D2E))),
        Theme("סגול", hx(0xFF3B2A5E), hx(0xFF7C6F94), hx(0xFF8E5BD6), hx(0xFFE0D6F2), hx(0xFFFCFAFF), hx(0xFFEBE2FA), intArrayOf(hx(0xFF7A4FD0), hx(0xFF251448))),
        Theme("כתום", hx(0xFF6B3A0B), hx(0xFF9A7B5E), hx(0xFFE8841A), hx(0xFFF4DDC2), hx(0xFFFFFCF8), hx(0xFFFFEBD6), intArrayOf(hx(0xFFE8841A), hx(0xFF5A2E06))),
        Theme("אפור", hx(0xFF222B30), hx(0xFF6F7B81), hx(0xFF37474F), hx(0xFFD5DADD), hx(0xFFFAFBFB), hx(0xFFE6EAEC), intArrayOf(hx(0xFF5C6A72), hx(0xFF14191C))),
        Theme("בורדו", hx(0xFF5A1A2B), hx(0xFF946B76), hx(0xFFC2334F), hx(0xFFF0D3DA), hx(0xFFFFFAFB), hx(0xFFFBE4E9), intArrayOf(hx(0xFFC2334F), hx(0xFF4A0E1C)))
    ) }
    private fun th(): Theme = themes[Prefs.theme(this).coerceIn(0, themes.size - 1)]
    private fun themeItems(): List<Item> = themes.mapIndexed { i, t ->
        Item(t.name, "🎨", t.band, if (i == Prefs.theme(this)) "✓ נבחרה" else "") { Prefs.setTheme(this, i); render() }
    }

    // shortcuts: OK and the four arrows on the home screen
    private val scKeys = listOf("ok" to "מקש OK", "up" to "חץ מעלה", "down" to "חץ מטה", "left" to "חץ שמאלה", "right" to "חץ ימינה")
    private fun scDefault(k: String) = when (k) { "ok" -> "apps"; "up" -> "menu"; else -> "none" }
    private fun scGet(k: String) = Prefs.custom(this, "sc:" + k, scDefault(k))
    private fun actionLabel(a: String): String = when {
        a == "none" || a.isEmpty() -> "ללא"
        a == "menu" -> "תפריט ראשי"
        a == "apps" -> "רשימת אפליקציות"
        a == "recents" -> "אפליקציות אחרונות"
        a.startsWith("m:") -> a.removePrefix("m:")
        a.startsWith("pkg:") -> appByPkg(a.removePrefix("pkg:"))?.label ?: "אפליקציה"
        else -> a
    }
    private var scKey = "ok"
    private fun shortcutItems(): List<Item> = scKeys.map { kn -> Item(kn.second, "⌨", 0xFFFFF8E1.toInt(), actionLabel(scGet(kn.first))) { scKey = kn.first; open(Screen.SHORTCUTPICK) } }
    private fun shortcutPickItems(): List<Item> {
        val l = ArrayList<Item>()
        fun add(id: String, label: String, icon: String) {
            l.add(Item(label, icon, 0xFFE3F2FD.toInt(), if (scGet(scKey) == id) "✓" else "") { Prefs.setCustom(this, "sc:" + scKey, id); back() })
        }
        add("none", "ללא", "∅"); add("menu", "תפריט ראשי", "▦"); add("apps", "רשימת אפליקציות", "📱"); add("recents", "אפליקציות אחרונות", "🕘")
        for (b in baseMenu) if (b.title != "אפליקציות" && b.title != "ניהול מכשיר") add("m:" + b.title, b.title, b.icon)
        for (a in apps()) l.add(Item(a.label, ico = a.icon, sub = if (scGet(scKey) == "pkg:" + a.pkg) "✓" else "") { Prefs.setCustom(this, "sc:" + scKey, "pkg:" + a.pkg); back() })
        return l
    }
    private fun runAction(a: String) {
        when {
            a == "none" || a.isEmpty() -> {}
            a == "menu" -> open(Screen.MENU)
            a == "apps" -> open(Screen.APPS)
            a == "recents" -> open(Screen.RECENT)
            a.startsWith("pkg:") -> launchPkg(a.removePrefix("pkg:"))
            a.startsWith("m:") -> baseMenu.firstOrNull { it.title == a.removePrefix("m:") }?.run?.invoke()
        }
    }

    private fun recentItems(): List<Item> {
        val inst = apps()
        val l = Prefs.recents(this).mapNotNull { pkg -> inst.firstOrNull { it.pkg == pkg } }
            .map { a -> Item(Prefs.custom(this, "an:" + a.pkg, a.label), ico = a.icon, data = a.pkg) { launchPkg(a.pkg) } }
        return if (l.isEmpty()) listOf(Item("אין אפליקציות אחרונות")) else l
    }

    // left key = options, right key = back; learnable from the device menu
    private var learnAction: String? = null
    private var optHandled = false
    private var keyedAt = 0L
    private fun screenUsesActKeys() = screen != Screen.PIN && screen != Screen.CALC && screen != Screen.TEXT && screen != Screen.DIALER && screen != Screen.MESSAGE_COMPOSE
    private fun keyAct(code: Int): String? {
        val m = Prefs.custom(this, "km:" + code, "")
        if (m.isNotEmpty()) return m
        return when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> "opt"
            KeyEvent.KEYCODE_SOFT_RIGHT -> "back"
            else -> null
        }
    }
    private fun learnKey(code: Int) {
        val a = learnAction ?: return
        Prefs.setCustom(this, "km:" + code, a)
        val l = Prefs.custom(this, "kmList", "").split(",").filter { it.isNotEmpty() }
        Prefs.setCustom(this, "kmList", (l + code.toString()).distinct().joinToString(","))
        learnAction = null; toast("נשמר: " + KeyEvent.keyCodeToString(code)); render()
    }
    private fun keymapItems(): List<Item> = listOf(
        Item("לימוד מקש אפשרויות", "🎛", 0xFFFFF3E0.toInt(), "לחיצה קצרה = אפשרויות, ארוכה = חזרה") { learnAction = "opt"; toast("לחץ על המקש הרצוי"); render() },
        Item("לימוד מקש חזרה", "↩", 0xFFFFF3E0.toInt()) { learnAction = "back"; toast("לחץ על המקש הרצוי"); render() },
        Item("איפוס מקשים", "↺", 0xFFFCE4EC.toInt()) {
            for (c in Prefs.custom(this, "kmList", "").split(",").filter { it.isNotEmpty() }) Prefs.setCustom(this, "km:" + c, null)
            Prefs.setCustom(this, "kmList", null); toast("אופס"); render()
        }
    )

    private var optTitle = ""
    private var optItems: List<Item> = emptyList()
    private fun hasOptions() = screen == Screen.HOME || screen == Screen.MENU || screen == Screen.APPS || screen == Screen.CONTACTS || screen == Screen.CALLLOG || screen == Screen.RECENT || screen == Screen.BT
    private fun optPress() { if (hasOptions()) openOptions() else backAction() }
    private fun leftSoft() { if (hasOptions()) openOptions() else activate() }
    private fun visibleEntries() = menuEntries().filter { !Prefs.hiddenMenu(this).contains(it.first) }
    private fun opt(title: String, icon: String = "", act: () -> Unit) = Item(title, icon, 0xFFE3F2FD.toInt()) { back(); act() }
    private fun openOptions() {
        val r = buildOptions()
        if (r == null) { backAction(); return }
        optTitle = r.first; optItems = r.second; open(Screen.OPTIONS)
    }
    private fun buildOptions(): Pair<String, List<Item>>? {
        val sel = rows().getOrNull(idx)
        when (screen) {
            Screen.HOME -> return "אפשרויות" to listOf(
                opt("תפריט ראשי", "▦") { open(Screen.MENU) },
                opt("רשימת אפליקציות", "📱") { open(Screen.APPS) },
                opt("אפליקציות אחרונות", "🕘") { open(Screen.RECENT) },
                opt("ערכת נושא", "🎨") { open(Screen.THEMES) },
                opt("פריסת מסך הבית", "🧩") { Prefs.setHomeLayout(this, (Prefs.homeLayout(this) + 1) % 3); render() },
                opt("רקע מסך הבית", "🖼") { open(Screen.HOMEBG) },
                opt("ניהול מכשיר", "⚙") { requirePin { open(Screen.DEVICE) } })
            Screen.MENU -> {
                val e = visibleEntries().getOrNull(idx) ?: return null
                val k = e.first
                return mName(k, e.second) to listOf(
                    opt("פתיחה", "▶") { e.second.run() },
                    opt("עריכת הפריט", "✏") { requirePin { editKey = k; open(Screen.EDITITEM) } },
                    opt("הזזה למעלה", "▲") { requirePin { moveEntry(k, -1) } },
                    opt("הזזה למטה", "▼") { requirePin { moveEntry(k, 1) } },
                    opt("הסתרה מהתפריט", "👁") { requirePin { if (k == "ניהול מכשיר") toast("אי אפשר להסתיר") else { Prefs.toggleHiddenMenu(this, k); render() } } })
            }
            Screen.APPS, Screen.RECENT -> {
                val pkg = sel?.data ?: return null
                if (pkg.isEmpty()) return null
                return (sel?.title ?: pkg) to appOptionItems(pkg, sel?.title ?: pkg, screen == Screen.RECENT)
            }
            Screen.CONTACTS -> {
                val num = sel?.data ?: ""
                val c = if (num.isEmpty()) null else contactsCache?.firstOrNull { it.numbers.firstOrNull() == num }
                if (c != null) return c.name.ifEmpty { num } to contactOptionItems(c)
                return "אנשי קשר" to listOf(
                    opt("איש קשר חדש", "➕") { newContact("") },
                    opt("חיפוש", "🔍") { askText("חיפוש (חלק משם או מספר)", nameQuery) { v -> nameQuery = v; idx = 0; render() } },
                    opt("ייבוא / ייצוא", "🗂") { open(Screen.CONTACTMGR) })
            }
            Screen.BT -> {
                val ad = btAdapter ?: return null
                val addr = sel?.data ?: ""
                if (addr.isEmpty()) return "בלוטות'" to listOf(
                    opt("הפעלה / כיבוי", "🔵") { btToggle() }, opt("חיפוש מכשירים", "🔍") { btScan() },
                    opt("הגדרות בלוטות' של המערכת", "⚙") { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
                val bonded = ad.bondedDevices.any { it.address == addr }
                return (sel?.title ?: addr) to (if (bonded) listOf(
                    opt(if (btConnectedAddrs().contains(addr)) "ניתוק" else "חיבור", "🔗") { btToggleConnect(addr) },
                    opt("ביטול התאמה", "✖") { confirm("לבטל את ההתאמה?") { btForget(addr) } },
                    opt("כתובת: " + addr, "ℹ") { })
                else listOf(opt("התאמה", "➕") { btPair(addr) }))
            }
            Screen.CALLLOG -> {
                val num = sel?.data ?: ""
                if (num.isEmpty()) return null
                return (sel?.title ?: num) to listOf(
                    opt("חיוג", "📞") { placeCall(num) },
                    opt("הודעה", "✉") { sms(num) },
                    opt("שמירה כאיש קשר", "➕") { askText("שם איש הקשר", "") { nm -> contactCreate(nm.ifEmpty { num }, num) } })
            }
            else -> return null
        }
    }
    private fun appOptionItems(pkg: String, label: String, recent: Boolean): List<Item> {
        val l = ArrayList<Item>()
        l.add(opt("פתיחה", "▶") { launchPkg(pkg) })
        l.add(opt("פרטי אפליקציה", "ℹ") { go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))) })
        if (recent) l.add(opt("הסרה מהרשימה", "✖") { Prefs.setRecents(this, Prefs.recents(this) - pkg); render() })
        l.add(opt("שינוי שם", "✏") { askText("שם האפליקציה", label) { v -> Prefs.setCustom(this, "an:" + pkg, v.ifEmpty { null }); render() } })
        l.add(opt("חסימה / ביטול חסימה", "🔒") { requirePin { Prefs.toggleBlocked(this, pkg); render() } })
        l.add(opt("הסתרה מהרשימה", "🙈") { requirePin { Prefs.toggleHiddenApp(this, pkg); render() } })
        l.add(opt("הוספה לתפריט הראשי", "➕") { requirePin { val ex = extraList(); if (!ex.contains(pkg)) setExtraList(ex + pkg); toast("נוסף") } })
        l.add(opt("הסרת התקנה", "🗑") { requirePin { go(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))) } })
        for (kn in scKeys) l.add(opt("קיצור: " + kn.second, "⌨") { requirePin { Prefs.setCustom(this, "sc:" + kn.first, "pkg:" + pkg); toast("נשמר") } })
        return l
    }

    private fun confirm(msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("אישור") { _, _ -> yes() }.setNegativeButton("ביטול", null).show()
    }
    private fun contactOptionItems(c: PhoneUtil.Contact): List<Item> {
        val l = ArrayList<Item>()
        val n0 = c.numbers.firstOrNull()
        if (n0 != null) { l.add(opt("חיוג", "📞") { placeCall(n0) }); l.add(opt("הודעה", "✉") { sms(n0) }) }
        l.add(opt("פרטים", "👤") { openContact(c) })
        l.addAll(contactEditItems(c))
        if (n0 != null) l.add(opt("הגדרה לחיוג מהיר", "⚡") {
            askText("מקש 2 עד 9", "") { v ->
                val d = v.trim().toIntOrNull()
                if (d != null && d in 2..9) { Prefs.setSpeed(this, d, PhoneUtil.digits(n0)); toast("נשמר במקש " + d) } else toast("ספרה 2 עד 9")
            }
        })
        return l
    }
    private fun contactEditItems(c: PhoneUtil.Contact): List<Item> {
        val l = ArrayList<Item>()
        l.add(opt("שינוי שם", "✏") { askText("שם איש הקשר", c.name) { v -> if (v.isNotEmpty()) contactRename(c, v) } })
        l.add(opt("הוספת מספר", "➕") { askText("מספר חדש", "") { v -> if (v.isNotEmpty()) contactAddNumber(c, v) } })
        for (n in c.numbers) {
            l.add(opt("עריכת מספר " + n, "☎") { askText("מספר", n) { v -> if (v.isNotEmpty()) contactEditNumber(c, n, v) } })
            if (c.numbers.size > 1) l.add(opt("מחיקת מספר " + n, "✖") { contactDeleteNumber(c, n) })
        }
        l.add(opt("מחיקת איש קשר", "🗑") { confirm("למחוק את " + c.name.ifEmpty { "איש הקשר" } + "?") { contactDelete(c) } })
        return l
    }
    private val WRITE_C = arrayOf(Manifest.permission.WRITE_CONTACTS)
    private fun contactsChanged() { contactsCache = null; contactsVer++; if (screen == Screen.CONTACTS) { contactLoading = true; loadContactsAsync() } }
    private fun contactCreate(name: String, number: String) = ensurePerms(WRITE_C) {
        try {
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            if (number.isNotEmpty()) ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            toast("נשמר"); contactsChanged()
        } catch (t: Throwable) { CrashLog.record(this, t); toast("השמירה נכשלה") }
    }
    private fun contactRename(c: PhoneUtil.Contact, name: String) = ensurePerms(WRITE_C) {
        try {
            val v = android.content.ContentValues().apply { put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name) }
            val n = contentResolver.update(ContactsContract.Data.CONTENT_URI, v,
                ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                arrayOf(c.id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            toast(if (n > 0) "נשמר" else "לא עודכן"); contactsChanged()
        } catch (t: Throwable) { CrashLog.record(this, t); toast("השמירה נכשלה") }
    }
    private fun contactAddNumber(c: PhoneUtil.Contact, number: String) = ensurePerms(WRITE_C) {
        try {
            var raw: Long? = null
            contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                ContactsContract.RawContacts.CONTACT_ID + "=?", arrayOf(c.id.toString()), null)?.use { if (it.moveToFirst()) raw = it.getLong(0) }
            val r = raw
            if (r == null) toast("איש הקשר לא נמצא") else {
                val v = android.content.ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, r)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
                contentResolver.insert(ContactsContract.Data.CONTENT_URI, v); toast("נשמר"); contactsChanged()
            }
        } catch (t: Throwable) { CrashLog.record(this, t); toast("השמירה נכשלה") }
    }
    private fun contactEditNumber(c: PhoneUtil.Contact, old: String, new: String) = ensurePerms(WRITE_C) {
        try {
            val v = android.content.ContentValues().apply { put(ContactsContract.CommonDataKinds.Phone.NUMBER, new) }
            val n = contentResolver.update(ContactsContract.Data.CONTENT_URI, v,
                ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DATA1 + "=?",
                arrayOf(c.id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, old))
            toast(if (n > 0) "נשמר" else "לא עודכן"); contactsChanged()
        } catch (t: Throwable) { CrashLog.record(this, t); toast("השמירה נכשלה") }
    }
    private fun contactDeleteNumber(c: PhoneUtil.Contact, number: String) = ensurePerms(WRITE_C) {
        try {
            contentResolver.delete(ContactsContract.Data.CONTENT_URI,
                ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DATA1 + "=?",
                arrayOf(c.id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, number))
            toast("נמחק"); contactsChanged()
        } catch (t: Throwable) { CrashLog.record(this, t); toast("המחיקה נכשלה") }
    }
    private fun contactDelete(c: PhoneUtil.Contact) = ensurePerms(WRITE_C) {
        try {
            contentResolver.delete(android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.id), null, null)
            toast("נמחק"); contactsChanged()
        } catch (t: Throwable) { CrashLog.record(this, t); toast("המחיקה נכשלה") }
    }

    // screen reader
    private var lastSpoken = ""
    private var lastScreenSpoken: Screen? = null
    private fun toggleTts() {
        val v = !Prefs.tts(this)
        Prefs.setTts(this, v)
        if (v) { Speech.init(this); Speech.say(this, "קריאת מסך פעילה"); if (!Speech.heOk) toast("אין קול עברית במכשיר - הגדרות דיבור") }
        render()
    }
    private fun speakCurrent() {
        if (!Prefs.tts(this) || screen == Screen.PIN) { lastScreenSpoken = screen; return }
        val entering = lastScreenSpoken != screen
        val s = when (screen) {
            Screen.HOME -> if (entering) "מסך הבית. " + SimpleDateFormat("HH:mm", Locale.US).format(Date()) else ""
            Screen.DIALER -> if (dialBuf.isEmpty()) "חיוג" else dialBuf.last().toString()
            Screen.TEXT -> if (textBuf.isEmpty() || entering) textTitle else textBuf.last().toString()
            Screen.CALC -> if (calcBuf.isEmpty()) "מחשבון" else calcBuf.toString()
            else -> {
                val r = rows().getOrNull(idx)
                val body = if (r != null) listOf(r.title, r.sub).filter { it.isNotEmpty() }.joinToString(", ") else ""
                if (entering) title() + ". " + body else body
            }
        }
        lastScreenSpoken = screen
        val fresh = SystemClock.uptimeMillis() - keyedAt < 500
        if (s.isNotEmpty() && (s != lastSpoken || fresh)) { lastSpoken = s; Speech.say(this, s) }
    }

    // ---------- 5.1: Bluetooth manager (keypad only) and class-management shortcut ----------
    private fun classUrl() = Prefs.custom(this, "classUrl", "https://classmanager-scoll-nm.base44.app")
    private fun tClass() = Tgt("ניהול כיתה", listOf(Intent(Intent.ACTION_VIEW, Uri.parse(classUrl()))), emptyList())

    private val btAdapter: android.bluetooth.BluetoothAdapter? get() = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    private val btFound = LinkedHashMap<String, String>()
    private var btScanning = false
    private val btProxies = HashMap<Int, android.bluetooth.BluetoothProfile>()
    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                val a = i?.action ?: return
                if (a == android.bluetooth.BluetoothDevice.ACTION_FOUND) {
                    val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (d.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) btFound[d.address] = d.name ?: d.address
                } else if (a == android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    btScanning = false
                } else if (a == android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    if (d != null && d.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) btFound.remove(d.address)
                }
                if (screen == Screen.BT) render()
            } catch (t: Throwable) { CrashLog.record(this@MainActivity, t) }
        }
    }
    private fun btOpen() { btEnsureProxies(); open(Screen.BT) }
    private fun btEnsureProxies() {
        val ad = btAdapter ?: return
        for (p in intArrayOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)) {
            if (btProxies.containsKey(p)) continue
            try {
                ad.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) { btProxies[profile] = proxy; if (screen == Screen.BT) render() }
                    override fun onServiceDisconnected(profile: Int) { btProxies.remove(profile) }
                }, p)
            } catch (t: Throwable) { }
        }
    }
    private fun btConnectedAddrs(): Set<String> =
        btProxies.values.flatMap { px -> try { px.connectedDevices.map { it.address } } catch (t: Throwable) { emptyList<String>() } }.toSet()
    private fun btToggle() {
        val ad = btAdapter ?: return
        try { if (ad.isEnabled) ad.disable() else ad.enable() } catch (t: Throwable) { go(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        toast("משנה מצב בלוטות'…")
    }
    private fun btScan() = ensurePerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) {
        val ad = btAdapter
        if (ad != null) {
            try {
                if (btScanning) { ad.cancelDiscovery(); btScanning = false } else { btFound.clear(); btScanning = ad.startDiscovery() }
            } catch (t: Throwable) { CrashLog.record(this, t); toast("החיפוש נכשל") }
        }
        render()
    }
    private fun btToggleConnect(addr: String) {
        val ad = btAdapter ?: return
        val d = try { ad.getRemoteDevice(addr) } catch (t: Throwable) { return }
        val on = btConnectedAddrs().contains(addr)
        var ok = false
        for (px in btProxies.values) {
            try {
                val m = px.javaClass.getMethod(if (on) "disconnect" else "connect", android.bluetooth.BluetoothDevice::class.java)
                m.isAccessible = true
                if (m.invoke(px, d) == true) ok = true
            } catch (t: Throwable) { }
        }
        toast(if (ok) (if (on) "מנתק…" else "מתחבר…") else "הפעולה לא נתמכת כאן - אפשרות: הגדרות בלוטות' של המערכת")
        render()
        uiHandler.postDelayed({ if (screen == Screen.BT) render() }, 2500)
    }
    private fun btPair(addr: String) {
        try { btAdapter?.getRemoteDevice(addr)?.createBond(); toast("מבקש התאמה - אשר במכשיר") } catch (t: Throwable) { toast("ההתאמה נכשלה") }
    }
    private fun btForget(addr: String) {
        try {
            val d = btAdapter?.getRemoteDevice(addr) ?: return
            d.javaClass.getMethod("removeBond").invoke(d); toast("ההתאמה בוטלה")
        } catch (t: Throwable) { toast("לא ניתן לבטל כאן - הגדרות בלוטות' של המערכת") }
        render()
    }
    private fun btItems(): List<Item> {
        val ad = btAdapter ?: return listOf(Item("אין בלוטות' במכשיר"))
        btEnsureProxies()
        val l = ArrayList<Item>()
        val on = ad.isEnabled
        l.add(Item("בלוטות'", "🔵", 0xFFE3F2FD.toInt(), if (on) "פעיל" else "כבוי") { btToggle() })
        if (on) {
            l.add(Item(if (btScanning) "מחפש… (OK לעצירה)" else "חיפוש מכשירים חדשים", "🔍", 0xFFE0F7FA.toInt()) { btScan() })
            val connected = btConnectedAddrs()
            val bonded = try { ad.bondedDevices.sortedBy { it.name ?: "" } } catch (t: Throwable) { emptyList() }
            for (d in bonded) {
                val conn = connected.contains(d.address)
                l.add(Item(d.name ?: d.address, if (conn) "🎧" else "🔗", 0xFFE8F5E9.toInt(), if (conn) "מחובר" else "מוכר", data = d.address) { btToggleConnect(d.address) })
            }
            for (e in btFound) l.add(Item(e.value, "➕", 0xFFFFF8E1.toInt(), "חדש - OK להתאמה", data = e.key) { btPair(e.key) })
            l.add(Item("הגדרות בלוטות' של המערכת", "⚙", 0xFFE0F2F1.toInt()) { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        }
        return l
    }

    // ---------- lifecycle ----------
    private fun handleDial(i: Intent?): Boolean {
        val a = i?.action ?: return false
        if (a != Intent.ACTION_DIAL && a != Intent.ACTION_VIEW) return false
        val d = i.data
        if (d == null && a == Intent.ACTION_VIEW) return false
        if (d != null && d.scheme != "tel") return false
        dialBuf.setLength(0); dialBuf.append(d?.schemeSpecificPart ?: "")
        stack.clear(); idxStack.clear(); screen = Screen.HOME; idx = 0
        open(Screen.DIALER)
        return true
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val reloadContacts = Runnable { if (screen == Screen.CONTACTS || screen == Screen.DIALER) { contactLoading = true; loadContactsAsync() } }
    private val contactObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsCache = null
            uiHandler.removeCallbacks(reloadContacts); uiHandler.postDelayed(reloadContacts, 800)
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { appCache = null } }
    private fun prewarmContacts() {
        if (contactsCache == null && !contactLoading && has(Manifest.permission.READ_CONTACTS)) { contactLoading = true; loadContactsAsync() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactObserver) } catch (e: Exception) { }
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
        try {
            registerReceiver(btRx, IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND); addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED); addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            })
        } catch (t: Throwable) { }
        if (!handleDial(intent)) render()
    }
    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(contactObserver); unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(btRx) } catch (e: Exception) { }
    }
    override fun onResume() {
        super.onResume(); logCache = null; applyTouchLock(); Blocker.instance?.applyGuard(); prewarmContacts()
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK)); render()
    }
    override fun onPause() { super.onPause(); unregisterReceiver(tick) }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (!handleDial(intent)) goHome() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val t = pendingPerm; pendingPerm = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) t?.invoke() else toast("חסרה הרשאה")
        } else if (requestCode == 11 || requestCode == 12 || requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) toast("ההרשאות אושרו") else toast("חסרה הרשאה")
        }
        render()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { backAction() }

    // ---------- keys ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        try {
            keyedAt = SystemClock.uptimeMillis()
            if (learnAction != null) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) learnKey(e.keyCode); return true }
            if (e.keyCode == KeyEvent.KEYCODE_CALL && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && InCallSvc.answerRinging()) return true
            val act = if (screenUsesActKeys()) keyAct(e.keyCode) else null
            if (act == "opt") {
                if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) optHandled = false else if (e.repeatCount == 1) { optHandled = true; backAction() } }
                else if (e.action == KeyEvent.ACTION_UP) { if (!optHandled) optPress() }
                return true
            }
            if (act == "back") { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) backAction(); return true }
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1 && longKey(e.keyCode)) return true
            val nav = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            repeating = e.repeatCount > 0
            val down = e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || nav)
            return if (key(e.keyCode, down)) true else super.dispatchKeyEvent(e)
        } catch (t: Throwable) { CrashLog.record(this, t); return true }
    }

    // Key map for the M5S (from KeyTester): D-pad 19-22, ENTER 66, BACK 4, CALL 5, digits 7-16, STAR 17, POUND 18.
    private fun key(code: Int, down: Boolean): Boolean {
        if (screen == Screen.PIN) return pinKey(code, down)
        if (screen == Screen.CALC) return calcKey(code, down)
        if (screen == Screen.TEXT) return textKey(code, down)
        if (screen == Screen.HOME) {
            val sc = when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> "ok"
                KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                else -> null
            }
            if (sc != null) { if (down) runAction(scGet(sc)); return true }
        }
        val isDial = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code == KeyEvent.KEYCODE_STAR || code == KeyEvent.KEYCODE_POUND
        if (screen == Screen.HOME && isDial) {
            if (down) {
                // if the user chose an external dialer for the "חיוג" item, typed digits go there too
                val ext = Prefs.custom(this, "t:חיוג", "")
                if (ext.isNotEmpty() && packageManager.getLaunchIntentForPackage(ext) != null) {
                    go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(dialChar(code).toString()))).setPackage(ext))
                } else { dialBuf.setLength(0); dialBuf.append(dialChar(code)); open(Screen.DIALER) }
            }
            return true
        }
        if (screen == Screen.DIALER) {
            when {
                isDial -> if (down && dialBuf.length < 30) { dialBuf.append(dialChar(code)); dialSel = -1; render() }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> if (down) { dialSel = minOf(dialSel + 1, dialSuggestions().size - 1); render() }
                code == KeyEvent.KEYCODE_DPAD_UP -> if (down) { dialSel = maxOf(dialSel - 1, -1); render() }
                code == KeyEvent.KEYCODE_CALL || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER -> if (down) callTyped()
                code == KeyEvent.KEYCODE_STAR -> if (down && dialBuf.isNotEmpty()) { dialBuf.setLength(dialBuf.length - 1); dialSel = -1; render() }
                code == KeyEvent.KEYCODE_POUND -> if (down) { dialBuf.setLength(0); render() }
                code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_SOFT_RIGHT -> if (down) backAction()
                code == KeyEvent.KEYCODE_ENDCALL -> if (down) goHome()
                else -> return false
            }
            return true
        }
        if (screen == Screen.CONTACTS) {
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                if (down && filterBuf.length < 12) { filterBuf.append(code - KeyEvent.KEYCODE_0); idx = 0; render() }
                return true
            }
            if (code == KeyEvent.KEYCODE_STAR) {
                if (down && filterBuf.isNotEmpty()) { filterBuf.setLength(filterBuf.length - 1); idx = 0; render() }
                return true
            }
        }
        if ((screen == Screen.CONTACTS || screen == Screen.CALLLOG) && code == KeyEvent.KEYCODE_CALL) {
            if (down) rows().getOrNull(idx)?.data?.takeIf { it.isNotEmpty() }?.let { placeCall(it) }
            return true
        }
        val n = rows().size
        val cols = if (isGrid()) 3 else 1
        fun move(d: Int) {
            if (down && n > 0) { idx = if (repeating) (idx + d).coerceIn(0, n - 1) else (idx + d + n) % n; render() }
        }
        fun page(d: Int) { val cap = listCap(); if (down && n > cap) { idx = (idx + d * cap).coerceIn(0, n - 1); render() } }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cols == 3) move(if (Prefs.arrowInv(this)) -1 else 1) else page(if (Prefs.arrowInv(this)) -1 else 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cols == 3) move(if (Prefs.arrowInv(this)) 1 else -1) else page(if (Prefs.arrowInv(this)) 1 else -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(cols)
            KeyEvent.KEYCODE_DPAD_UP -> move(-cols)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (down) activate()
            KeyEvent.KEYCODE_BACK -> if (down) backAction()
            KeyEvent.KEYCODE_CALL -> if (down) callLog()
            KeyEvent.KEYCODE_SOFT_LEFT -> if (down) activate()
            KeyEvent.KEYCODE_SOFT_RIGHT -> if (down) backAction()
            KeyEvent.KEYCODE_MENU -> if (down) activate()
            KeyEvent.KEYCODE_ENDCALL -> if (down) goHome()
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val k = code - KeyEvent.KEYCODE_1
                if (down && k < n) { idx = k; activate() }
            }
            else -> return false
        }
        return true
    }

    // ---------- views ----------
    private fun tv(t: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false, g: Int = Gravity.CENTER) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color); gravity = g
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0 || s <= 0) -1 else l * 100 / s
    }

    private fun batteryIcon(p: Int, col: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = GradientDrawable().apply { setStroke(dp(2), col); cornerRadius = dp(4).toFloat() }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF3D8FDB.toInt()); cornerRadius = dp(2).toFloat() }
        }
        val q = p.coerceIn(0, 100)
        box.addView(fill, LinearLayout.LayoutParams(0, -1, q.toFloat()))
        box.addView(View(this), LinearLayout.LayoutParams(0, -1, (100 - q).toFloat() + 0.001f))
        return box
    }

    private fun homeView(): View {
        val bg = Prefs.homeBg(this)
        val fg = if (bg == 3) ink else Color.WHITE
        val fg2 = if (bg == 3) soft else 0xFFBFE3FF.toInt()
        val compact = bg >= 10
        val lay = Prefs.homeLayout(this)
        val now = Date()
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        c.addView(tv(SimpleDateFormat("HH:mm", Locale.US).format(now), if (compact) 46f else when (lay) { 1 -> 96f; 2 -> 56f; else -> 72f }, fg, bold = true).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR })
        val day = SimpleDateFormat("EEEE", Locale.forLanguageTag("he-IL")).format(now).removePrefix("יום ")
        c.addView(tv(day + "   " + SimpleDateFormat("dd-MM-yyyy", Locale.US).format(now), if (compact) 14f else 18f, fg, bold = true))
        c.addView(tv(SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("he-u-ca-hebrew")).format(now), if (compact) 13f else 16f, fg2))
        val p = batteryPct()
        if (p >= 0 && !compact && lay == 0) {
            c.addView(tv("$p%", 16f, fg, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
            c.addView(batteryIcon(p, fg), LinearLayout.LayoutParams(dp(64), dp(30)))
        }
        if (compact) { c.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; c.setPadding(0, 0, 0, dp(10)) }
        if (!compact && lay == 2) { c.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; c.setPadding(0, dp(36), 0, 0) }
        if (bg >= 5) {
            for (k in 0 until c.childCount) (c.getChildAt(k) as? TextView)?.setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(cachedDrawable(resources.getIdentifier(wpNames[(bg - 5).coerceIn(0, wpNames.size - 1)], "drawable", packageName)))
            }
            val f = FrameLayout(this)
            f.addView(img, FrameLayout.LayoutParams(-1, -1))
            f.addView(c, FrameLayout.LayoutParams(-1, -1))
            return f
        }
        if (bg < 3) return c
        val m = resources.displayMetrics
        val side = (minOf(m.widthPixels, m.heightPixels) * 0.85f).toInt()
        val mark = sealView().apply { setImageDrawable(cachedDrawable(sealRes(bg == 4))); alpha = if (bg == 3) 0.14f else 0.22f }
        val f = FrameLayout(this)
        f.addView(mark, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        f.addView(c, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun menuView(items: List<Item>): View {
        val n = items.size
        val nRows = (n + 2) / 3
        val availPx = resources.displayMetrics.heightPixels - dp(24 + 48 + 16)
        val cellH = availPx / 4
        val badge = (cellH * 0.52f).toInt().coerceIn(dp(40), dp(76))
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        items.chunked(3).forEachIndexed { r, row ->
            val rl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { c, m ->
                val i = r * 3 + c
                val on = i == idx
                val ic: View = if (m.ico != null) ImageView(this).apply { setImageDrawable(m.ico) }
                else tv(m.icon, 24f, color = Color.BLACK).apply {
                    background = circle(m.tint); setTextSize(TypedValue.COMPLEX_UNIT_PX, badge * 0.5f)
                }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    if (on) background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat(); setColor(th().sel); setStroke(dp(2), cyan)
                    }
                    addView(ic, LinearLayout.LayoutParams(badge, badge))
                    addView(tv(m.title, 14f, ink, bold = on).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    setOnClickListener { idx = i; activate() }
                }
                rl.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(3 - row.size) { rl.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f)) }
            g.addView(rl, if (nRows <= 4) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, cellH))
        }
        if (nRows <= 4) return g
        val sv = ScrollView(this).apply { addView(g) }
        sv.post {
            if (sv.height > 0) {
                val rowTop = (idx / 3) * cellH; val rowBottom = rowTop + cellH + dp(6)
                var y = listScroll
                if (rowTop < y) y = rowTop else if (rowBottom > y + sv.height) y = rowBottom - sv.height
                y = maxOf(0, y)
                sv.scrollTo(0, y); listScroll = y
            }
        }
        return sv
    }

    private fun listCap(): Int = ((resources.displayMetrics.heightPixels - dp(170)) / dp(61)).coerceIn(4, 12)
    private fun listView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8)) }
        val all = rows()
        val nAll = all.size
        val cap = listCap()
        if (nAll > 0 && idx >= nAll) idx = nAll - 1
        if (recenter) { listTop = idx - cap / 2; recenter = false }
        else if (idx == 0) listTop = 0
        else if (idx < listTop) listTop = idx
        else if (idx >= listTop + cap) listTop = idx - cap + 1
        listTop = listTop.coerceIn(0, maxOf(0, nAll - cap))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(title(), 22f, ink, bold = true, g = Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
        if (nAll > cap) head.addView(tv((idx + 1).toString() + "/" + nAll, 14f, soft))
        col.addView(head)
        val bar = View(this).apply { background = GradientDrawable().apply { setColor(cyan); cornerRadius = dp(3).toFloat() } }
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { setMargins(0, dp(4), 0, dp(8)) })
        val zm = screen == Screen.ZMANIM
        var sel: View? = null
        for (i in listTop until minOf(nAll, listTop + cap)) {
            val m = all[i]
            val on = i == idx
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (on) background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(band) }
                setPadding(dp(8), dp(9), dp(8), dp(9))
                val d = m.ico
                if (d != null) {
                    addView(ImageView(this@MainActivity).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                } else if (m.icon.isNotEmpty()) {
                    addView(tv(m.icon, 16f, Color.BLACK).apply { background = circle(m.tint) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                }
                addView(tv(m.title, 18f, ink, bold = on, g = Gravity.START or Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), 0, dp(8), 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                if (m.sub.isNotEmpty()) addView(tv(m.sub, if (zm) 16f else 12f, if (zm) ink else soft, bold = zm))
                setOnClickListener { idx = i; activate() }
            }
            if (on) sel = row
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(3)) })
        }
        val sv = ScrollView(this).apply { addView(col) }
        sv.post {
            if (sv.height > 0) {
                sv.scrollTo(0, listScroll)
                sel?.let { s ->
                    var y = sv.scrollY; val vh = sv.height
                    if (s.top < y) y = s.top - dp(4)
                    else if (s.bottom > y + vh) y = if (s.bottom - y > vh * 2) s.top - (vh - s.height) / 2 else s.bottom - vh + dp(4)
                    y = maxOf(0, y)
                    if (y != sv.scrollY) sv.scrollTo(0, y)
                }
                listScroll = sv.scrollY
            }
        }
        return sv
    }

    private fun dialerView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val num = dialBuf.toString()
        val big = tv(if (num.isEmpty()) "הקלידו מספר" else fmtNum(num), if (num.length > 12) 25f else 34f,
            if (num.isEmpty()) soft else ink, bold = true, g = Gravity.CENTER)
            .apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(0, dp(8), 0, dp(3)); minHeight = dp(54) }
        root.addView(big)
        val sug = dialSuggestions()
        if (dialSel >= sug.size) dialSel = sug.size - 1
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumHeight = dp(44) }
        if (sug.isEmpty()) box.addView(tv(if (num.length >= 3 && num.all { it.isDigit() }) "מספר לא שמור" else "", 15f, 0xFF1B7F96.toInt(), g = Gravity.CENTER))
        sug.forEachIndexed { i, c ->
            val on = i == dialSel
            val n0 = c.numbers.firstOrNull() ?: ""
            box.addView(tv(c.name.ifEmpty { n0 } + "  " + fmtNum(PhoneUtil.digits(n0)), 15f, if (on) Color.WHITE else ink, bold = on, g = Gravity.CENTER).apply {
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = GradientDrawable().apply { setColor(if (on) 0xFF1B7F96.toInt() else 0x00000000); cornerRadius = dp(6).toFloat() }
                setOnClickListener { placeCall(n0) }
            })
        }
        root.addView(box)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val keys = listOf("1" to "", "2" to "דהו", "3" to "אבג", "4" to "מנ", "5" to "יכל", "6" to "זחט", "7" to "רשת", "8" to "צק", "9" to "סעפ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            rowKeys.forEach { pair ->
                val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = GradientDrawable().apply { setColor(0xFFF1F6F8.toInt()); cornerRadius = dp(9).toFloat(); setStroke(dp(1), 0xFFD0E0E6.toInt()) } }
                b.addView(tv(pair.first, 25f, ink, bold = true, g = Gravity.CENTER))
                if (pair.second.isNotEmpty()) b.addView(tv(pair.second, 10f, soft, g = Gravity.CENTER))
                b.setOnClickListener { dialSel = -1; when (pair.first) { "*" -> if (dialBuf.isNotEmpty()) dialBuf.setLength(dialBuf.length - 1); "#" -> dialBuf.setLength(0); else -> dialBuf.append(pair.first) }; render() }
                row.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(58)))
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val call = tv("חיוג", 20f, Color.WHITE, bold = true).apply { background = GradientDrawable().apply { setColor(0xFF2E9E6B.toInt()); cornerRadius = dp(10).toFloat() }; setPadding(dp(22), dp(10), dp(22), dp(10)); setOnClickListener { callTyped() } }
        val del = tv("⌫", 22f, ink, bold = true).apply { setPadding(dp(22), dp(10), dp(22), dp(10)); setOnClickListener { if (dialBuf.isNotEmpty()) { dialBuf.setLength(dialBuf.length - 1); render() } } }
        val clear = tv("נקה", 16f, ink, bold = true).apply { setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { dialBuf.setLength(0); render() } }
        bar.addView(clear); bar.addView(del); bar.addView(call)
        root.addView(bar)
        root.addView(tv("ירוק/OK חיוג · * מחק (ארוך: נקה) · ▲▼ בחירה · החזקת ספרה = חיוג מהיר", 11f, soft, g = Gravity.CENTER).apply { setPadding(0, dp(4), 0, dp(2)) })
        return root
    }

    private fun pinView(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val (t, hint) = when (pinMode) {
            0 -> "קוד מנהל" to "הקלידו את הקוד ולחצו אישור"
            1 -> "קוד מנהל חדש" to "בחרו קוד של 4 עד 8 ספרות"
            else -> "אימות הקוד" to "הקלידו שוב את הקוד"
        }
        c.addView(tv(t, 22f, ink, bold = true))
        val dots = if (pinBuf.isEmpty()) "–" else (1..pinBuf.length).joinToString(" ") { "●" }
        c.addView(tv(dots, 28f, ink).apply { setPadding(0, dp(14), 0, dp(10)) })
        c.addView(tv(hint, 13f, soft))
        c.addView(tv("כוכבית = מחיקה", 12f, soft).apply { setPadding(0, dp(4), 0, 0) })
        if (pinMsg.isNotEmpty()) c.addView(tv(pinMsg, 14f, 0xFFC62828.toInt(), bold = true).apply { setPadding(0, dp(8), 0, 0) })
        return c
    }

    private fun softBar(): View {
        val home = screen == Screen.HOME
        val onDark = home && Prefs.homeBg(this) != 3
        val color = if (onDark) Color.WHITE else ink
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(if (home) 0x00000000 else th().panel)
        }
        val l = tv(leftLabel(), 18f, color, bold = true, g = Gravity.LEFT or Gravity.CENTER_VERTICAL)
            .apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { leftSoft() } }
        val r = tv(rightLabel(), 18f, color, bold = true, g = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            .apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { backAction() } }
        val seal = sealView().apply { setImageDrawable(cachedDrawable(sealRes(onDark))) }
        bar.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(seal, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(r, LinearLayout.LayoutParams(0, -2, 1f))
        return bar
    }

    private fun render() {
        try { renderInner() } catch (t: Throwable) {
            CrashLog.record(this, t)
            if (screen != Screen.HOME) {
                stack.clear(); idxStack.clear(); screen = Screen.HOME; idx = 0
                try { renderInner() } catch (t2: Throwable) { CrashLog.record(this, t2) }
            }
        }
    }
    private fun renderInner() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = if (screen == Screen.HOME)
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, homeBgColors())
            else GradientDrawable().apply { setColor(th().panel) }
        }
        val body = when (screen) {
            Screen.HOME -> homeView()
            Screen.MENU, Screen.ICONPICK -> menuView(rows())
            Screen.APPS -> if (Prefs.drawer(this) == 1) menuView(rows()) else listView()
            Screen.PIN -> pinView()
            Screen.DIALER -> dialerView()
            Screen.MESSAGE_COMPOSE -> messageComposeView()
            Screen.CALC -> calcView()
            Screen.TEXT -> textView()
            else -> listView()
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(softBar())
        setContentView(root)
        speakCurrent()
    }
}
