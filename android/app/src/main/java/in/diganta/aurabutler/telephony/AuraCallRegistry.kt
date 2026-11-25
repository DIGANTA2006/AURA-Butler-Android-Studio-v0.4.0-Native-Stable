package `in`.diganta.aurabutler.telephony

import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap
import java.util.UUID

/** Process-local bridge between Telecom's bound InCallService and AURA's phone UI. */
object AuraCallRegistry {
    private val lock = Any()
    private val callIds = IdentityHashMap<Call, String>()
    private val callCallbacks = IdentityHashMap<Call, Call.Callback>()
    private val calls = LinkedHashMap<String, Call>()
    private val notificationIds = LinkedHashMap<String, Int>()
    private var nextNotificationId = 10_000
    private var service: AuraInCallService? = null
    private var appContext: Context? = null
    private var historyStore: EncryptedCallHistoryStore? = null
    private val mutableSnapshots = MutableStateFlow<List<AuraCallSnapshot>>(emptyList())
    val snapshots: StateFlow<List<AuraCallSnapshot>> = mutableSnapshots.asStateFlow()
    private val endpointObjects = LinkedHashMap<String, CallEndpoint>()
    private val mutableAudioEndpoints = MutableStateFlow<List<AuraAudioEndpoint>>(emptyList())
    val audioEndpoints: StateFlow<List<AuraAudioEndpoint>> = mutableAudioEndpoints.asStateFlow()

    internal fun initializeStorage(context: Context) {
        synchronized(lock) {
            if (historyStore == null) {
                historyStore = runCatching { EncryptedCallHistoryStore(context.applicationContext) }.getOrNull()
            }
            if (appContext == null) appContext = context.applicationContext
        }
    }

    internal fun attach(inCallService: AuraInCallService) {
        synchronized(lock) { service = inCallService }
        initializeStorage(inCallService.applicationContext)
        refreshPublishedState()
    }

    internal fun detach(inCallService: AuraInCallService) = synchronized(lock) {
        if (service === inCallService) {
            service = null
            endpointObjects.clear()
            mutableAudioEndpoints.value = emptyList()
        }
    }

    /** Persists an AURA VoIP session in the same encrypted local history as SIM metadata. */
    internal fun appendVoipHistory(record: AuraCallHistoryRecord): Boolean = synchronized(lock) {
        val store = historyStore ?: return@synchronized false
        runCatching { store.append(record) }.isSuccess
    }

    /** Adds a Telecom Call and returns its exact snapshot; callers must not re-identify by number. */
    internal fun onCallAdded(call: Call): AuraCallSnapshot {
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = refreshPublishedState()
            override fun onDetailsChanged(call: Call, details: Call.Details) = refreshPublishedState()
            override fun onCallDestroyed(call: Call) = refreshPublishedState()
        }
        val snapshot = synchronized(lock) {
            val existing = callIds[call]
            if (existing != null) return@synchronized snapshot(existing, call)
            val id = UUID.randomUUID().toString()
            callIds[call] = id
            callCallbacks[call] = callback
            calls[id] = call
            notificationIds[id] = allocateNotificationIdLocked()
            runCatching { call.registerCallback(callback) }
            snapshot(id, call)
        }
        refreshPublishedState()
        return snapshot
    }

    internal fun onCallRemoved(call: Call) {
        val removed = synchronized(lock) {
            val id = callIds.remove(call) ?: return
            callCallbacks.remove(call)?.let { callback ->
                runCatching { call.unregisterCallback(callback) }
            }
            val callSnapshot = snapshot(id, call)
            val details = call.details
            val record = AuraCallHistoryRecord(
                id = id,
                phoneNumber = callSnapshot.phoneNumber,
                incoming = callSnapshot.incoming,
                startedAtEpochMs = details.creationTimeMillis.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
                endedAtEpochMs = System.currentTimeMillis(),
                connectedAtEpochMs = details.connectTimeMillis,
                disconnectCauseCode = details.disconnectCause?.code
            )
            runCatching { historyStore?.append(record) }
            calls.remove(id)
            val notificationId = notificationIds.remove(id)
            Pair(appContext, notificationId)
        }
        removed.second?.let { notificationId ->
            removed.first?.let { context -> AuraCallNotifications.cancel(context, notificationId) }
        }
        refreshPublishedState()
    }

    fun answer(id: String): Boolean = withCall(id) { call ->
        if (call.state != Call.STATE_RINGING) return@withCall false
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        true
    }

    fun reject(id: String): Boolean = withCall(id) { call ->
        if (call.state != Call.STATE_RINGING) return@withCall false
        call.reject(false, null)
        true
    }

    fun disconnect(id: String): Boolean = withCall(id) { call ->
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) {
            return@withCall false
        }
        call.disconnect()
        true
    }

    fun hold(id: String): Boolean = withCall(id) { call ->
        if (call.state != Call.STATE_ACTIVE || !call.details.can(Call.Details.CAPABILITY_HOLD)) {
            return@withCall false
        }
        call.hold()
        true
    }

    fun unhold(id: String): Boolean = withCall(id) { call ->
        if (call.state != Call.STATE_HOLDING) return@withCall false
        call.unhold()
        true
    }

    fun setMuted(muted: Boolean): Boolean = synchronized(lock) {
        val current = service ?: return false
        runCatching { current.setMuted(muted) }.isSuccess
    }

    /**
     * Requests one of the exact endpoints Telecom advertised. AURA never constructs
     * a CallEndpoint or guesses a Bluetooth device identifier.
     */
    fun requestAudioEndpoint(endpointId: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            val route = endpointId.removePrefix(LEGACY_ROUTE_PREFIX).toIntOrNull() ?: return false
            return setLegacyAudioRoute(route)
        }
        val pair = synchronized(lock) {
            val current = service ?: return false
            val endpoint = endpointObjects[endpointId] ?: return false
            current to endpoint
        }
        return runCatching {
            requestEndpointChange(pair.first, pair.second)
            true
        }.getOrDefault(false)
    }

    @RequiresApi(34)
    private fun requestEndpointChange(current: AuraInCallService, endpoint: CallEndpoint) {
        current.requestCallEndpointChange(
            endpoint,
            current.mainExecutor,
            object : OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(error: android.telecom.CallEndpointException) {
                    android.util.Log.w(TAG, "Telecom rejected the requested audio endpoint.", error)
                }
            }
        )
    }

    /** API 26–33 fallback; Android 14+ must use requestCallEndpointChange. */
    @Suppress("DEPRECATION")
    private fun setLegacyAudioRoute(route: Int): Boolean = synchronized(lock) {
        val current = service ?: return false
        if (Build.VERSION.SDK_INT >= 34) return false
        runCatching { current.setAudioRoute(route) }.isSuccess
    }

    @RequiresApi(34)
    internal fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) {
        if (Build.VERSION.SDK_INT < 34) return
        synchronized(lock) {
            endpointObjects.clear()
            endpoints.forEach { endpoint ->
                endpointObjects[endpoint.identifier.toString()] = endpoint
            }
            publishAudioEndpointsLocked()
        }
    }

    @RequiresApi(34)
    internal fun onCallEndpointChanged(endpoint: CallEndpoint?) {
        if (Build.VERSION.SDK_INT < 34) return
        synchronized(lock) {
            // The current endpoint is included in the API's available endpoint list;
            // keep the callback-provided object if the list has not arrived yet.
            if (endpoint != null && endpoint.identifier.toString() !in endpointObjects) {
                endpointObjects[endpoint.identifier.toString()] = endpoint
            }
            publishAudioEndpointsLocked(currentId = endpoint?.identifier?.toString())
        }
    }

    internal fun onLegacyCallAudioStateChanged(state: CallAudioState?) {
        if (Build.VERSION.SDK_INT >= 34 || state == null) return
        synchronized(lock) {
            val routes = listOf(
                CallAudioState.ROUTE_EARPIECE to "Earpiece",
                CallAudioState.ROUTE_BLUETOOTH to "Bluetooth",
                CallAudioState.ROUTE_WIRED_HEADSET to "Wired headset",
                CallAudioState.ROUTE_SPEAKER to "Speaker"
            ).filter { (route, _) -> (state.supportedRouteMask and route) != 0 }
            mutableAudioEndpoints.value = routes.map { (route, name) ->
                AuraAudioEndpoint(
                    id = LEGACY_ROUTE_PREFIX + route,
                    name = name,
                    type = route,
                    isCurrent = state.route == route
                )
            }
        }
    }

    @RequiresApi(34)
    private fun publishAudioEndpointsLocked(currentId: String? = runCatching {
        service?.currentCallEndpoint?.identifier?.toString()
    }.getOrNull()) {
        mutableAudioEndpoints.value = endpointObjects.map { (id, endpoint) ->
            AuraAudioEndpoint(
                id = id,
                name = endpoint.endpointName.toString(),
                type = endpoint.endpointType,
                isCurrent = id == currentId
            )
        }
    }

    fun recentHistory(limit: Int = 30): List<AuraCallHistoryRecord> =
        runCatching { synchronized(lock) { historyStore?.recent(limit).orEmpty() } }.getOrDefault(emptyList())

    fun historyStorageAvailable(): Boolean = synchronized(lock) {
        val store = historyStore ?: return@synchronized false
        runCatching { store.recent(1) }.isSuccess
    }

    fun deleteHistoryRecord(id: String): Boolean = synchronized(lock) {
        val store = historyStore ?: return@synchronized false
        runCatching { store.delete(id) }.isSuccess
    }

    fun clearHistory(): Boolean = synchronized(lock) {
        val store = historyStore ?: return@synchronized false
        runCatching { store.clear() }.isSuccess
    }

    fun historyRetentionDays(): Int = synchronized(lock) {
        runCatching { historyStore?.retentionDays() }.getOrNull() ?: 90
    }

    fun setHistoryRetentionDays(days: Int): Boolean = synchronized(lock) {
        val store = historyStore ?: return@synchronized false
        runCatching { store.setRetentionDays(days) }.isSuccess
    }

    internal fun snapshotsNow(): List<AuraCallSnapshot> = mutableSnapshots.value

    private fun allocateNotificationIdLocked(): Int {
        if (nextNotificationId == Int.MAX_VALUE) nextNotificationId = 10_000
        return nextNotificationId++
    }

    private fun withCall(id: String, action: (Call) -> Boolean): Boolean {
        val call = synchronized(lock) { calls[id] } ?: return false
        return runCatching { action(call) }.getOrDefault(false)
    }

    private fun refreshPublishedState() {
        val (context, updates) = synchronized(lock) {
            val currentSnapshots = calls.map { (id, call) -> id to snapshot(id, call) }
            mutableSnapshots.value = currentSnapshots.map { it.second }
            val currentContext = appContext
            val notificationUpdates = currentSnapshots.mapNotNull { (id, callSnapshot) ->
                notificationIds[id]?.let { notificationId -> callSnapshot to notificationId }
            }
            currentContext to notificationUpdates
        }
        if (context != null) {
            updates.forEach { (snapshot, notificationId) ->
                AuraCallNotifications.show(context, snapshot, notificationId)
            }
        }
    }

    private fun snapshot(id: String, call: Call): AuraCallSnapshot {
        val details = call.details
        val direction = if (android.os.Build.VERSION.SDK_INT >= 29) {
            details.callDirection
        } else {
            Call.Details.DIRECTION_UNKNOWN
        }
        return AuraCallSnapshot(
            id = id,
            phoneNumber = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() },
            state = call.state,
            incoming = when (direction) {
                Call.Details.DIRECTION_INCOMING -> true
                Call.Details.DIRECTION_OUTGOING -> false
                else -> call.state == Call.STATE_RINGING
            },
            createdAtEpochMs = details.creationTimeMillis,
            connectedAtEpochMs = details.connectTimeMillis,
            canHold = details.can(Call.Details.CAPABILITY_HOLD)
        )
    }

    private const val TAG = "AuraCallRegistry"
    private const val LEGACY_ROUTE_PREFIX = "legacy:"

}
