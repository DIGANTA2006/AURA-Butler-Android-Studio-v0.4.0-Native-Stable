package `in`.diganta.aurabutler.telephony

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the WSS endpoint and signed device token using Android Keystore-backed encryption. */
@Suppress("DEPRECATION")
class AuraVoipConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        "aura_encrypted_voip_config",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun load(): AuraVoipConfig = AuraVoipConfig(
        signalingUrl = preferences.getString(KEY_URL, "").orEmpty(),
        clientId = preferences.getString(KEY_CLIENT, "").orEmpty(),
        accessToken = preferences.getString(KEY_TOKEN, "").orEmpty(),
        preferredLanguage = preferences.getString(KEY_LANGUAGE, "auto").orEmpty()
    )

    @Synchronized
    fun save(config: AuraVoipConfig) {
        require(config.validate() == null) { config.validate() ?: "Invalid VoIP configuration." }
        check(preferences.edit()
            .putString(KEY_URL, config.signalingUrl.trim())
            .putString(KEY_CLIENT, config.clientId.trim())
            .putString(KEY_TOKEN, config.accessToken.trim())
            .putString(KEY_LANGUAGE, config.preferredLanguage.lowercase())
            .commit()) { "Encrypted VoIP settings could not be committed." }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Encrypted VoIP settings could not be cleared." }
    }

    private companion object {
        const val KEY_URL = "wss_url_v1"
        const val KEY_CLIENT = "client_id_v1"
        const val KEY_TOKEN = "device_token_v1"
        const val KEY_LANGUAGE = "language_v1"
    }
}
