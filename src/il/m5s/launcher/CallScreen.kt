package il.m5s.launcher

import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService

/** Screens incoming calls according to the mode chosen in "ניהול מכשיר": 0 off, 1 contacts only, 2 block hidden numbers. */
class CallScreen : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        var block = false
        try {
            val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
            val mode = Prefs.screenMode(this)
            if (incoming && mode != 0) {
                val number = details.handle?.schemeSpecificPart ?: ""
                block = when (mode) {
                    1 -> number.isEmpty() || !isContact(number)
                    2 -> number.isEmpty()
                    else -> false
                }
            }
        } catch (e: Exception) { block = false }
        val r = CallResponse.Builder()
        if (block) r.setDisallowCall(true).setRejectCall(true).setSkipNotification(false)
        respondToCall(details, r.build())
    }

    // Fails open: if contacts can't be read the call is allowed.
    private fun isContact(n: String): Boolean = try {
        val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
        contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: true
    } catch (e: SecurityException) { true }
}
