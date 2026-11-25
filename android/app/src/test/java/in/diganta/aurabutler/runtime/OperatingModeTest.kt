package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.model.ButlerSettings
import org.junit.Assert.*
import org.junit.Test

class OperatingModeTest {

    @Test
    fun testOperatingModeFromWireName() {
        assertEquals(OperatingMode.DEVICE_ONLY, OperatingMode.fromWireName("device_only"))
        assertEquals(OperatingMode.PRIVATE_CLOUD, OperatingMode.fromWireName("private_cloud"))
        assertEquals(OperatingMode.PUBLIC_CLOUD, OperatingMode.fromWireName("public_cloud"))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.fromWireName("automatic"))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.fromWireName("unknown"))
    }

    @Test
    fun testLegacyMigration() {
        assertEquals(OperatingMode.DEVICE_ONLY, OperatingMode.migrateFromLegacy("local"))
        assertEquals(OperatingMode.DEVICE_ONLY, OperatingMode.migrateFromLegacy("device_only"))
        assertEquals(OperatingMode.PRIVATE_CLOUD, OperatingMode.migrateFromLegacy("private"))
        assertEquals(OperatingMode.PRIVATE_CLOUD, OperatingMode.migrateFromLegacy("private_cloud"))
        assertEquals(OperatingMode.PUBLIC_CLOUD, OperatingMode.migrateFromLegacy("public"))
        assertEquals(OperatingMode.PUBLIC_CLOUD, OperatingMode.migrateFromLegacy("public_cloud"))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.migrateFromLegacy("automatic"))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.migrateFromLegacy(""))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.migrateFromLegacy(null))
        assertEquals(OperatingMode.AUTOMATIC, OperatingMode.migrateFromLegacy("unknown_legacy"))
    }

    @Test
    fun testButlerSettingsOperatingModeExtension() {
        val settings = ButlerSettings()
        assertEquals(OperatingMode.AUTOMATIC, settings.operatingMode)

        val localSettings = settings.copy(aiProvider = "local")
        assertEquals(OperatingMode.DEVICE_ONLY, localSettings.operatingMode)

        val privateSettings = settings.copy(aiProvider = "private")
        assertEquals(OperatingMode.PRIVATE_CLOUD, privateSettings.operatingMode)

        val publicSettings = settings.copy(aiProvider = "gemini")
        assertEquals(OperatingMode.AUTOMATIC, publicSettings.operatingMode) // gemini -> automatic fallback
    }

    @Test
    fun testExecutionModeFromWireName() {
        assertEquals(ExecutionMode.LOCAL_COMMAND, ExecutionMode.fromWireName("local_command"))
        assertEquals(ExecutionMode.PRIVATE_AI_STREAMING, ExecutionMode.fromWireName("private_ai_streaming"))
        assertEquals(ExecutionMode.WEB_SEARCH_FALLBACK, ExecutionMode.fromWireName("web_search_fallback"))
    }
}
