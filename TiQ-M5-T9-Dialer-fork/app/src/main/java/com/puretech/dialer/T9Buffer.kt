package com.puretech.dialer

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Multi-tap keypad text entry (Hebrew / English / digits), matching the M5S launcher's own
 * text-entry screen key-for-key: repeated presses of a digit cycle through its letters, `#`
 * switches language/mode, `*` deletes the last character. Used everywhere in this app that
 * needs real letters typed from the hardware keypad instead of relying on an on-screen IME
 * (which this half-touch device doesn't have).
 */
class T9Buffer {
    val text = StringBuilder()
    var mode = 0 // 0=Hebrew, 1=English, 2=digits
        private set
    private var lastKey = -1
    private var lastIdx = 0
    private var lastTime = 0L

    private fun chars(d: Int): String {
        val he = arrayOf(" 0", ".,?!-1", "דהו2", "אבג3", "מםנן4", "יכךל5", "זחט6", "רשת7", "צקץ8", "סעפף9")
        val en = arrayOf(" 0", ".,@?!'1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")
        return when (mode) { 0 -> he[d]; 1 -> en[d]; else -> d.toString() }
    }

    fun modeLabel(): String = when (mode) { 0 -> "עברית"; 1 -> "English"; else -> "123" }

    fun clear() { text.setLength(0); lastKey = -1 }

    /** Feeds one key-down event. Returns true if it was a text-entry key (handled here). */
    fun onKeyDown(code: Int): Boolean {
        when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val d = code - KeyEvent.KEYCODE_0
                val set = chars(d)
                val now = SystemClock.uptimeMillis()
                if (set.length > 1 && code == lastKey && now - lastTime < 1000 && text.isNotEmpty()) {
                    lastIdx = (lastIdx + 1) % set.length
                    text.setCharAt(text.length - 1, set[lastIdx])
                } else {
                    lastIdx = 0; text.append(set[0]); lastKey = code
                }
                lastTime = now
            }
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_DEL -> { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1); lastKey = -1 }
            KeyEvent.KEYCODE_POUND -> { mode = (mode + 1) % 3; lastKey = -1 }
            else -> return false
        }
        return true
    }
}
