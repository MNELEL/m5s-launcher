package il.m5s.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** In-call screen: answer / reject / hang up, mute, speaker, DTMF digits. Keys work without touching the screen. */
class CallActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var nameTv: TextView
    private lateinit var numTv: TextView
    private lateinit var stateTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var leftTv: TextView
    private lateinit var rightTv: TextView
    private var cur: Call? = null
    private var ending = false
    private var shownNumber = ""
    private var shownName = ""
    private val started = System.currentTimeMillis()

    private val tickRun = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE, g: Int = Gravity.CENTER) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color); gravity = g
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0F4C5C.toInt(), 0xFF071E2B.toInt()))
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        stateTv = tv("", 16f, color = 0xFFBFE3FF.toInt())
        nameTv = tv("", 28f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        numTv = tv("", 20f).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        infoTv = tv("", 15f, color = 0xFFFFE082.toInt()).apply { setPadding(0, dp(12), 0, 0) }
        hintTv = tv("", 12f, color = 0xFF9CC3CF.toInt()).apply { setPadding(0, dp(14), 0, 0) }
        mid.addView(stateTv); mid.addView(nameTv); mid.addView(numTv); mid.addView(infoTv); mid.addView(hintTv)
        root.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        leftTv = tv("", 18f, bold = true, g = Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { leftAction() }
        }
        rightTv = tv("", 18f, bold = true, g = Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { hangup() }
        }
        val seal = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(resources.getIdentifier("seal_light", "drawable", packageName))
        }
        bar.addView(leftTv, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(seal, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(rightTv, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        InCallSvc.onChange = { runOnUiThread { refresh() } }
        h.post(tickRun)
    }
    override fun onPause() {
        super.onPause()
        InCallSvc.onChange = null
        h.removeCallbacks(tickRun)
    }

    private fun current(): Call? {
        val l = InCallSvc.active
        return l.firstOrNull { it.state == Call.STATE_RINGING } ?: l.lastOrNull()
    }
    private fun ringing() = cur?.state == Call.STATE_RINGING
    private fun muted() = InCallSvc.audio?.isMuted == true
    private fun speaker() = InCallSvc.audio?.route == CallAudioState.ROUTE_SPEAKER

    private var announced = "?"
    private fun refresh() {
        val call = current()
        cur = call
        if (call == null) {
            if (!ending) { ending = true; stateTv.text = "השיחה הסתיימה"; h.postDelayed({ finish() }, 700) }
            return
        }
        val number = call.details.handle?.schemeSpecificPart ?: ""
        if (number != shownNumber) {
            shownNumber = number
            shownName = PhoneUtil.lookupName(this, number) ?: (call.details.callerDisplayName ?: "")
        }
        nameTv.text = if (shownName.isNotEmpty()) shownName else if (number.isEmpty()) "מספר חסוי" else number
        numTv.text = if (shownName.isNotEmpty()) number else ""
        if (call.state == Call.STATE_RINGING && announced != number) {
            announced = number
            Speech.say(this, "שיחה נכנסת מ" + (if (shownName.isNotEmpty()) shownName else if (number.isEmpty()) "מספר חסוי" else number.toCharArray().joinToString(" ")))
        }
        stateTv.text = when (call.state) {
            Call.STATE_RINGING -> "שיחה נכנסת"
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> "מתקשר…"
            Call.STATE_HOLDING -> "בהמתנה"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "השיחה מסתיימת"
            else -> {
                val t0 = call.details.connectTimeMillis.let { if (it > 0) it else started }
                val s = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(0)
                "בשיחה  %02d:%02d".format(s / 60, s % 60)
            }
        }
        val flags = ArrayList<String>()
        if (muted()) flags.add("🔇 מושתק")
        if (speaker()) flags.add("🔊 רמקול")
        infoTv.text = flags.joinToString("   ")
        if (call.state == Call.STATE_RINGING) {
            leftTv.text = "מענה"; rightTv.text = "דחייה"
            hintTv.text = "מקש ירוק / אישור = מענה\nחזרה = דחייה"
        } else {
            leftTv.text = if (muted()) "בטל השתקה" else "השתקה"; rightTv.text = "ניתוק"
            hintTv.text = "אישור = רמקול   ספרות = חיוג בשיחה\nחזרה = ניתוק"
        }
    }

    private fun answer() { cur?.let { if (it.state == Call.STATE_RINGING) it.answer(VideoProfile.STATE_AUDIO_ONLY) } }
    private fun hangup() {
        val c = cur ?: return
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }
    private fun leftAction() { if (ringing()) answer() else toggleMute() }
    private fun toggleMute() { InCallSvc.instance?.setMuted(!muted()) }
    private fun toggleSpeaker() {
        InCallSvc.instance?.setAudioRoute(if (speaker()) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0
        val up = e.action == KeyEvent.ACTION_UP
        val code = e.keyCode
        val ch: Char? = when {
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
            code == KeyEvent.KEYCODE_STAR -> '*'
            code == KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }
        if (ch != null) {
            val c = cur
            if (c != null && c.state == Call.STATE_ACTIVE) {
                if (down) c.playDtmfTone(ch) else if (up) c.stopDtmfTone()
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_CALL -> if (down) answer()
            KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_BACK -> if (down) hangup()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (down) { if (ringing()) answer() else toggleSpeaker() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) toggleMute()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { hangup() }
}
