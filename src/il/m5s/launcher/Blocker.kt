package il.m5s.launcher

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 1) Sends the user back home when a blocked app comes to the foreground; remembers recent apps.
 * 2) Touch guard: transparent full-screen accessibility overlay that swallows every touch / hover on every app.
 *    Panic sequence from any screen: * * # # 0
 * 3) Global keys: green key answers a ringing call, hold Back in another app opens the recent-apps screen.
 */
class Blocker : AccessibilityService() {
    companion object { @JvmStatic var instance: Blocker? = null }
    private var overlay: View? = null
    private val seq = ArrayList<Int>()
    private var seqT = 0L
    private var fgPkg = ""
    private var backLong = false
    private val screenRx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { applyGuard() } }

    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this; applyGuard()
        try { registerReceiver(screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }) } catch (t: Throwable) { }
    }
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() { hideOverlay(); try { unregisterReceiver(screenRx) } catch (t: Throwable) { }; instance = null }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) { fgPkg = pkg; return }
        if (pkg != "com.android.systemui" && pkg != "android") {
            fgPkg = pkg
            try { if (packageManager.getLaunchIntentForPackage(pkg) != null) Prefs.pushRecent(this, pkg) } catch (t: Throwable) { }
        }
        if (Prefs.blocked(this).contains(pkg)) performGlobalAction(GLOBAL_ACTION_HOME)
        if (Prefs.touchLock(this) && overlay == null) applyGuard()
    }
    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        try {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (e.keyCode == KeyEvent.KEYCODE_CALL && InCallSvc.answerRinging()) return true
                if (e.keyCode == KeyEvent.KEYCODE_ENDCALL && InCallSvc.rejectRinging()) return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_BACK && fgPkg.isNotEmpty() && fgPkg != packageName && Prefs.custom(this, "hbr", "1") == "1") {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { backLong = true; performGlobalAction(GLOBAL_ACTION_RECENTS); return true }
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 1 && backLong) return true
                if (e.action == KeyEvent.ACTION_UP && backLong) { backLong = false; return true }
            }
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Prefs.touchLock(this)) {
                val now = SystemClock.uptimeMillis()
                if (now - seqT > 2500) seq.clear()
                seqT = now
                seq.add(e.keyCode); while (seq.size > 5) seq.removeAt(0)
                if (seq == listOf(KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_0)) {
                    seq.clear(); Prefs.setTouchLock(this, false); applyGuard()
                    Toast.makeText(this, "נעילת המגע בוטלה", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) { CrashLog.record(this, t) }
        return false
    }

    fun applyGuard() {
        val on = Prefs.touchLock(this)
        try { Root.applyTouch(this, on) } catch (t: Throwable) { }
        if (on) showOverlay() else hideOverlay()
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) dpm.setStatusBarDisabled(ComponentName(this, AdminReceiver::class.java), on)
        } catch (t: Throwable) { }
    }
    private fun showOverlay() {
        if (overlay != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(this).apply {
                isClickable = true
                setOnTouchListener { _, _ -> true }
                setOnGenericMotionListener { _, _ -> true }
                setOnHoverListener { _, _ -> true }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp); overlay = v
        } catch (t: Throwable) { CrashLog.record(this, t) }
    }
    private fun hideOverlay() {
        val v = overlay ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (t: Throwable) { }
        overlay = null
    }
}
