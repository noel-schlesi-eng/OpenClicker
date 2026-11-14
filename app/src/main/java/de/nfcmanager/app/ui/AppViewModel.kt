package de.nfcmanager.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.nfcmanager.app.NfcManagerApp
import de.nfcmanager.app.R
import de.nfcmanager.app.nfc.NfcCapabilities
import de.nfcmanager.app.nfc.NfcCapabilityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Tab(val label: String, val icon: Int) {
    SCAN("Scannen", R.drawable.ic_nfc),
    LIBRARY("Gespeicherte Tags", R.drawable.ic_library),
    EMULATE("Emulation", R.drawable.ic_emulate),
}

abstract class BaseViewModel(app: Application) : AndroidViewModel(app) {
    protected val container = (app as NfcManagerApp).container
}

class AppViewModel(app: Application) : BaseViewModel(app) {
    val settings = container.settings

    private val tabState = MutableStateFlow(Tab.SCAN)
    val tab: StateFlow<Tab> = tabState.asStateFlow()

    private val capsState = MutableStateFlow(NfcCapabilityChecker.check(app))
    val capabilities: StateFlow<NfcCapabilities> = capsState.asStateFlow()

    fun selectTab(tab: Tab) {
        tabState.value = tab
    }

    fun refreshCapabilities() {
        capsState.value = NfcCapabilityChecker.check(getApplication())
    }

    val messages get() = container.messages
}
