package il.m5s.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Screen reader: speaks the selected item, screen titles and the caller name when "קריאת מסך" is on. */
object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    @JvmStatic var heOk = false
    fun init(c: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(c.applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.forLanguageTag("he-IL"))
                    heOk = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    ready = true
                }
            }
        } catch (t: Throwable) { }
    }
    fun say(c: Context, text: String, flush: Boolean = true) {
        if (!Prefs.tts(c) || text.isBlank()) return
        init(c)
        if (!ready) return
        try { tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "m5s") } catch (t: Throwable) { }
    }
}
