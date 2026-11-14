package de.nfcmanager.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.nfcmanager.app.data.ThemeMode
import de.nfcmanager.app.nfc.NfcReaderController
import de.nfcmanager.app.ui.AppViewModel
import de.nfcmanager.app.ui.NfcManagerRoot
import de.nfcmanager.app.ui.Tab
import de.nfcmanager.app.ui.scan.ScanViewModel
import de.nfcmanager.app.ui.theme.NfcManagerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appVm: AppViewModel by viewModels()
    private val scanVm: ScanViewModel by viewModels()
    private lateinit var reader: NfcReaderController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reader = NfcReaderController(this)
        val container = (application as NfcManagerApp).container

        setContent {
            val mode by appVm.settings.themeMode.collectAsStateWithLifecycle()
            val dynamic by appVm.settings.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            NfcManagerTheme(darkTheme = dark, dynamicColor = dynamic) { NfcManagerRoot() }
        }

        // NFC-Modus folgt dem Tab: Scannen = Lese-Modus (pausiert die Emulation), sonst Emulation bevorzugen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appVm.refreshCapabilities()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        appVm.refreshCapabilities()
                        applyMode(appVm.tab.value, container)
                    }
                }
                ContextCompat.registerReceiver(
                    this@MainActivity, receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                try {
                    appVm.tab.collect { applyMode(it, container) }
                } finally {
                    unregisterReceiver(receiver)
                    reader.disable()
                    container.emulation.releasePreference(this@MainActivity)
                }
            }
        }
    }

    private fun applyMode(tab: Tab, container: AppContainer) {
        if (tab == Tab.SCAN) {
            container.emulation.releasePreference(this)
            reader.enable { scanVm.handleTag(it) }
        } else {
            reader.disable()
            container.emulation.preferWhileForeground(this)
        }
    }
}
