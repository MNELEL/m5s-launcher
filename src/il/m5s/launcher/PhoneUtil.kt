package il.m5s.launcher

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Contacts / call-log access and T9 matching (Hebrew + Latin letters on the keypad, as printed on the M5S keys). */
object PhoneUtil {
    class Contact(val id: Long, val lookup: String, val name: String, val numbers: MutableList<String>, var starred: Boolean = false) {
        // Precomputed once per load so typing/searching never recomputes them per key press.
        var norm = ""
        var t9 = ""
        var nd: List<String> = emptyList()
        fun prep() { norm = norm(name); t9 = t9(name); nd = numbers.map { digits(it) } }
    }
    class Rec(val number: String, val name: String?, val type: Int, val date: Long)

    private val t9 = HashMap<Char, Char>().apply {
        fun reg(d: Char, letters: String) { for (ch in letters) this[ch] = d }
        reg('2', "abcדהו"); reg('3', "defאבג"); reg('4', "ghiמנםן"); reg('5', "jklיכלך")
        reg('6', "mnoזחט"); reg('7', "pqrsרשת"); reg('8', "tuvצקץ"); reg('9', "wxyzסעפף")
    }

    fun t9(name: String): String {
        val sb = StringBuilder()
        for (ch in name.lowercase()) {
            val d = t9[ch]
            if (d != null) sb.append(d) else if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
    }

    fun digits(s: String) = s.filter { it in '0'..'9' }

    /** Lower-case, strip punctuation/niqqud, fold Hebrew final letters, collapse spaces. */
    fun norm(s: String): String {
        val sb = StringBuilder()
        var lastSpace = true
        for (ch0 in s.lowercase()) {
            val ch = when (ch0) { 'ך' -> 'כ'; 'ם' -> 'מ'; 'ן' -> 'נ'; 'ף' -> 'פ'; 'ץ' -> 'צ'; else -> ch0 }
            if (ch in '\u0591'..'\u05C7') continue
            if (ch.isLetterOrDigit()) { sb.append(ch); lastSpace = false }
            else if (!lastSpace && (ch == ' ' || ch == '-' || ch == '.' || ch == ',' || ch == '_')) { sb.append(' '); lastSpace = true }
        }
        return sb.toString().trim()
    }

    /** Partial-name search: every word typed must appear somewhere in the name (any order), or the digits in a number. */
    fun matchesName(c: Contact, q: String): Boolean {
        if (q.isEmpty()) return true
        val n = digits(q)
        if (n.length >= 3 && c.nd.any { it.contains(n) }) return true
        val words = norm(q).split(' ').filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { c.norm.contains(it) }
    }

    fun matches(c: Contact, q: String): Boolean =
        q.isEmpty() || c.t9.contains(q) || c.nd.any { it.contains(q) }

    fun lookupName(c: Context, number: String): String? {
        if (number.isEmpty()) return null
        return try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            c.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    fun contacts(c: Context): List<Contact> = load(c, true).ifEmpty { load(c, false) }

    private fun load(c: Context, star: Boolean): List<Contact> {
        val map = LinkedHashMap<Long, Contact>()
        try {
            val cols = if (star) arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME, Phone.NUMBER, "starred")
                       else arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME, Phone.NUMBER)
            c.contentResolver.query(Phone.CONTENT_URI, cols, null, null, Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val ct = map.getOrPut(id) { Contact(id, cur.getString(1) ?: "", cur.getString(2) ?: "", ArrayList(), star && cur.getInt(4) == 1) }
                    val n = cur.getString(3) ?: continue
                    if (ct.numbers.none { digits(it) == digits(n) }) ct.numbers.add(n)
                }
            }
        } catch (e: Exception) { }
        val out = map.values.toList()
        out.forEach { it.prep() }
        return out
    }

    // ---------- vCard export / import ----------
    /** Writes every contact to a .vcf file in Downloads. Returns a Hebrew status message. */
    fun exportVcf(c: Context): String {
        return try {
            val keys = ArrayList<String>()
            c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)
                ?.use { while (it.moveToNext()) it.getString(0)?.let { k -> keys.add(k) } }
            if (keys.isEmpty()) return "אין אנשי קשר לייצוא"
            val out = ByteArrayOutputStream()
            for (chunk in keys.chunked(60)) {
                val u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI, Uri.encode(chunk.joinToString(":")))
                c.contentResolver.openAssetFileDescriptor(u, "r")?.use { fd -> fd.createInputStream().use { it.copyTo(out) } }
            }
            val name = "contacts-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".vcf"
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return "הייצוא נכשל"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/x-vcard")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return "הייצוא נכשל"
                c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "יוצאו ${keys.size} אנשי קשר לתיקיית הורדות: $name"
            } else {
                val dir = c.getExternalFilesDir(null) ?: return "אין אחסון זמין"
                java.io.File(dir, name).writeBytes(bytes)
                "יוצאו ${keys.size} אנשי קשר: ${dir.absolutePath}/$name"
            }
        } catch (e: Exception) { "הייצוא נכשל" }
    }

    class Card(val name: String, val numbers: List<String>, val emails: List<String>)

    private fun qpDecode(s: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { bytes.write(h); i += 3; continue }
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    fun parseVcf(text: String): List<Card> {
        // unfold: folded lines start with space/tab; quoted-printable soft breaks end with '='
        val lines = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t') && lines.isNotEmpty()) lines[lines.size - 1] = lines.last() + raw.substring(1)
            else if (lines.isNotEmpty() && lines.last().endsWith("=") && lines.last().uppercase().contains("QUOTED-PRINTABLE")) lines[lines.size - 1] = lines.last().dropLast(1) + raw
            else lines.add(raw)
        }
        val cards = ArrayList<Card>()
        var fn = ""; var n = ""; var tels = ArrayList<String>(); var mails = ArrayList<String>(); var inCard = false
        for (line in lines) {
            val up = line.uppercase()
            if (up.startsWith("BEGIN:VCARD")) { inCard = true; fn = ""; n = ""; tels = ArrayList(); mails = ArrayList(); continue }
            if (up.startsWith("END:VCARD")) {
                val nm = fn.ifEmpty { n }.trim()
                if (inCard && (nm.isNotEmpty() || tels.isNotEmpty())) cards.add(Card(nm, tels, mails))
                inCard = false; continue
            }
            if (!inCard) continue
            val colon = line.indexOf(':'); if (colon < 0) continue
            val head = line.substring(0, colon); var value = line.substring(colon + 1)
            val key = head.substringBefore(';').uppercase().substringAfterLast('.')
            if (head.uppercase().contains("QUOTED-PRINTABLE")) value = qpDecode(value)
            when (key) {
                "FN" -> fn = value.replace("\\,", ",").trim()
                "N" -> { val a = value.split(';'); n = (a.getOrElse(1) { "" } + " " + a.getOrElse(0) { "" }).trim() }
                "TEL" -> { val t = value.trim(); if (digits(t).isNotEmpty()) tels.add(t) }
                "EMAIL" -> if (value.isNotBlank()) mails.add(value.trim())
            }
        }
        return cards
    }

    /** Inserts cards that are not already present (same number). Returns Hebrew status. */
    fun importCards(c: Context, cards: List<Card>, existing: List<Contact>): String {
        val known = HashSet<String>()
        for (e in existing) for (d in e.nd) if (d.length >= 7) known.add(d.takeLast(9))
        var added = 0; var skipped = 0
        val ops = ArrayList<ContentProviderOperation>()
        fun flush() {
            if (ops.isEmpty()) return
            try { c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) } catch (e: Exception) { }
            ops.clear()
        }
        for (card in cards) {
            val keys = card.numbers.map { digits(it) }.filter { it.length >= 7 }.map { it.takeLast(9) }
            if (keys.isNotEmpty() && keys.all { known.contains(it) }) { skipped++; continue }
            keys.forEach { known.add(it) }
            val r = ops.size
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, r)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, card.name).build())
            for (t in card.numbers) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, r)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, t).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build())
            for (m in card.emails) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, r)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, m).build())
            added++
            if (ops.size > 400) flush()
        }
        flush()
        return "נוספו $added אנשי קשר, דולגו $skipped (כבר קיימים)"
    }

    fun setStarred(c: Context, id: Long, on: Boolean) {
        try {
            c.contentResolver.update(ContactsContract.Contacts.CONTENT_URI, ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) },
                ContactsContract.Contacts._ID + "=?", arrayOf(id.toString()))
        } catch (e: Exception) { }
    }

    fun callLog(c: Context): List<Rec> {
        val out = ArrayList<Rec>()
        try {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { cur ->
                while (cur.moveToNext() && out.size < 100) {
                    val name = cur.getString(1)
                    out.add(Rec(cur.getString(0) ?: "", if (name.isNullOrEmpty()) null else name, cur.getInt(2), cur.getLong(3)))
                }
            }
        } catch (e: Exception) { }
        return out
    }
}
