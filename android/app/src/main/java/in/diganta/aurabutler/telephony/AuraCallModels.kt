package `in`.diganta.aurabutler.telephony

/** Metadata exposed by Android Telecom. This model deliberately contains no call audio. */
data class AuraCallSnapshot(
    val id: String,
    val phoneNumber: String?,
    val state: Int,
    val incoming: Boolean,
    val createdAtEpochMs: Long,
    val connectedAtEpochMs: Long,
    val canHold: Boolean
) {
    val isRinging: Boolean get() = state == android.telecom.Call.STATE_RINGING
    val isActive: Boolean get() = state == android.telecom.Call.STATE_ACTIVE
    val isHolding: Boolean get() = state == android.telecom.Call.STATE_HOLDING

    val stateLabel: String
        get() = when (state) {
            android.telecom.Call.STATE_NEW -> "New"
            android.telecom.Call.STATE_CONNECTING -> "Connecting"
            android.telecom.Call.STATE_DIALING -> "Dialling"
            android.telecom.Call.STATE_RINGING -> "Incoming call"
            android.telecom.Call.STATE_ACTIVE -> "Connected"
            android.telecom.Call.STATE_HOLDING -> "On hold"
            android.telecom.Call.STATE_DISCONNECTING -> "Ending"
            android.telecom.Call.STATE_DISCONNECTED -> "Ended"
            android.telecom.Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
            else -> "Call state unavailable"
        }
}

data class AuraCallHistoryRecord(
    val id: String,
    val phoneNumber: String?,
    val incoming: Boolean,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val connectedAtEpochMs: Long,
    val disconnectCauseCode: Int?,
    val transport: String = "CELLULAR",
    val contactName: String? = null,
    val languageTag: String? = null,
    val summary: String? = null,
    val transcript: String? = null
) {
    val durationMs: Long
        get() = if (connectedAtEpochMs > 0L) {
            (endedAtEpochMs - connectedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
}

/** A route advertised by the active Telecom call; IDs are opaque and never manufactured. */
data class AuraAudioEndpoint(
    val id: String,
    val name: String,
    val type: Int,
    val isCurrent: Boolean
)
