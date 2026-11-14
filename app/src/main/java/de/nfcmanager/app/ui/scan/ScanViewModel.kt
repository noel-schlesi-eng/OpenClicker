package de.nfcmanager.app.ui.scan

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.viewModelScope
import de.nfcmanager.app.core.tag.TagSnapshot
import de.nfcmanager.app.nfc.TagReader
import de.nfcmanager.app.nfc.TagWriter
import de.nfcmanager.app.nfc.WriteOutcome
import de.nfcmanager.app.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WriteRequest(val name: String, val message: ByteArray)

data class ScanUiState(
    val snapshot: TagSnapshot? = null,
    val savedId: Long? = null,
    val write: WriteRequest? = null,
    val writeMessage: String? = null,
    val writeOk: Boolean? = null,
)

class ScanViewModel(app: Application) : BaseViewModel(app) {
    private val repository = container.repository
    private val state = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = state.asStateFlow()

    /** Wird vom Reader-Mode-Thread aufgerufen; Lesen/Schreiben blockiert bewusst auf diesem Thread. */
    fun handleTag(tag: Tag) {
        val request = state.value.write
        if (request != null) {
            when (val outcome = TagWriter.write(tag, request.message)) {
                is WriteOutcome.Success -> state.update { it.copy(write = null, writeMessage = outcome.message, writeOk = true) }
                is WriteOutcome.Failure -> state.update { it.copy(writeMessage = outcome.message, writeOk = false) }
            }
            return
        }
        val snapshot = TagReader.read(tag)
        state.update { it.copy(snapshot = snapshot, savedId = null) }
    }

    fun armWrite(name: String, message: ByteArray) {
        state.update { it.copy(write = WriteRequest(name, message), writeMessage = null, writeOk = null) }
    }

    fun cancelWrite() {
        state.update { it.copy(write = null, writeMessage = null, writeOk = null) }
    }

    fun clearWriteMessage() {
        state.update { it.copy(writeMessage = null, writeOk = null) }
    }

    fun clearResult() {
        state.update { it.copy(snapshot = null, savedId = null) }
    }

    fun save(name: String, symbol: String, colorIndex: Int) {
        val snapshot = state.value.snapshot ?: return
        viewModelScope.launch {
            val id = repository.saveSnapshot(snapshot, name, symbol, colorIndex)
            state.update { it.copy(savedId = id) }
            container.post("Tag gespeichert")
        }
    }
}
