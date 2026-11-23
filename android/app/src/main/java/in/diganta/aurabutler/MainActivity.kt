package `in`.diganta.aurabutler

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import `in`.diganta.aurabutler.background.WakeWordService
import `in`.diganta.aurabutler.ui.AuraApp
import `in`.diganta.aurabutler.model.AppTab
import `in`.diganta.aurabutler.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ButlerViewModel by viewModels()
    private var receiverRegistered = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.refreshFromStore()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuraTheme {
                AuraApp(viewModel)
            }
        }
        handleAnalyzerIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnalyzerIntent(intent)
    }

    private fun handleAnalyzerIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("aura_open_analyzer", false) == true) {
            viewModel.selectTab(AppTab.ANALYZER)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(WakeWordService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        viewModel.refreshFromStore()
        if (
            viewModel.uiState.value.settings.wakeWordEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            WakeWordService.setEnabled(this, true)
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }
}
