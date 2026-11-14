package de.nfcmanager.app.ui.emulation

import android.app.Application
import androidx.lifecycle.viewModelScope
import de.nfcmanager.app.data.SavedTag
import de.nfcmanager.app.nfc.EmulationLog
import de.nfcmanager.app.nfc.EmulationLogEntry
import de.nfcmanager.app.nfc.EmulationStatus
import de.nfcmanager.app.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class EmulationViewModel(app: Application) : BaseViewModel(app) {
    private val controller = container.emulation

    private val statusState = MutableStateFlow(controller.status())
    val status: StateFlow<EmulationStatus> = statusState.asStateFlow()

    val candidates: StateFlow<List<SavedTag>> = container.repository.observeAll()
        .map { list -> list.filter { it.hasNdef } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val log: StateFlow<List<EmulationLogEntry>> = EmulationLog.entries

    fun refresh() {
        statusState.value = controller.status()
    }

    fun activate(tag: SavedTag) {
        val message = tag.entity.ndefMessage ?: return
        try {
            controller.activate(tag.id, tag.entity.name, message)
        } catch (e: IllegalArgumentException) {
            container.post("Der Inhalt ist zu groß für die Emulation")
        }
        refresh()
    }

    fun deactivate() {
        controller.deactivate()
        refresh()
    }

    fun clearLog() = EmulationLog.clear()
}
