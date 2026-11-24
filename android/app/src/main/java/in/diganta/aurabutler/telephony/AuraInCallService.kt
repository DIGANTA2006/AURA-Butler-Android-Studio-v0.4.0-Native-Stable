package `in`.diganta.aurabutler.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.annotation.RequiresApi

/** Android binds this service only when AURA is selected as the default phone app. */
class AuraInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        AuraCallNotifications.createChannel(this)
        AuraCallRegistry.attach(this)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        // Telecom calls this when the active in-call UI must return to the foreground.
        runCatching {
            startActivity(Intent(this, AuraPhoneActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(AuraPhoneActivity.EXTRA_SHOW_DIALPAD, showDialpad)
            })
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        AuraCallRegistry.onLegacyCallAudioStateChanged(audioState)
    }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        AuraCallRegistry.onAvailableCallEndpointsChanged(availableEndpoints)
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        AuraCallRegistry.onCallEndpointChanged(callEndpoint)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // Registry publishes the matching call by its opaque ID and owns notification lifecycle.
        AuraCallRegistry.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        AuraCallRegistry.onCallRemoved(call)
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        AuraCallRegistry.detach(this)
        super.onDestroy()
    }
}
