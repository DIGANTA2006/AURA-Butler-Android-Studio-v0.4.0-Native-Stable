package `in`.diganta.aurabutler.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles only explicit user actions attached to AURA's call notification. */
class AuraCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> AuraCallRegistry.answer(id)
            ACTION_REJECT -> AuraCallRegistry.reject(id)
            ACTION_DISCONNECT -> AuraCallRegistry.disconnect(id)
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "aura_call_id"
        const val ACTION_ANSWER = "in.diganta.aurabutler.telephony.ANSWER"
        const val ACTION_REJECT = "in.diganta.aurabutler.telephony.REJECT"
        const val ACTION_DISCONNECT = "in.diganta.aurabutler.telephony.DISCONNECT"
    }
}
