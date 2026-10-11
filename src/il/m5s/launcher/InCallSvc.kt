package il.m5s.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/** Active only when the launcher is chosen as the phone app (default dialer). Shows [CallActivity] for every call. */
class InCallSvc : InCallService() {
    companion object {
        val active = ArrayList<Call>()
        var audio: CallAudioState? = null
        var onChange: (() -> Unit)? = null
        var instance: InCallSvc? = null
        fun answerRinging(): Boolean {
            val c = active.firstOrNull { it.state == Call.STATE_RINGING } ?: return false
            c.answer(VideoProfile.STATE_AUDIO_ONLY); return true
        }
        fun rejectRinging(): Boolean {
            val c = active.firstOrNull { it.state == Call.STATE_RINGING } ?: return false
            c.reject(false, null); return true
        }
    }

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { onChange?.invoke() }
        override fun onDetailsChanged(call: Call, details: Call.Details) { onChange?.invoke() }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        active.add(call)
        call.registerCallback(cb)
        val i = Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startActivity(i) } catch (e: Exception) { }
        if (call.state == Call.STATE_RINGING) notifyIncoming(i)
        onChange?.invoke()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        active.remove(call)
        try { getSystemService(NotificationManager::class.java).cancel(77) } catch (e: Exception) { }
        onChange?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) { audio = state; onChange?.invoke() }

    // Backup for devices that don't allow starting the activity from here: a full-screen notification.
    private fun notifyIncoming(i: Intent) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("calls", "שיחות", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            }
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "calls") else Notification.Builder(this)
            b.setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("שיחה נכנסת")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
                .setFullScreenIntent(pi, true).setContentIntent(pi)
            nm.notify(77, b.build())
        } catch (e: Exception) { }
    }
}
