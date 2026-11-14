package de.nfcmanager.app.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogKind { INFO, OK, WARN, ERROR }

data class EmulationLogEntry(
    val time: Long,
    val kind: LogKind,
    val text: String,
    val detail: String? = null,
)

/** Protokoll der Emulation (nur im Arbeitsspeicher, höchstens [MAX] Einträge). */
object EmulationLog {
    private const val MAX = 300
    private val state = MutableStateFlow<List<EmulationLogEntry>>(emptyList())
    val entries: StateFlow<List<EmulationLogEntry>> = state.asStateFlow()

    fun add(kind: LogKind, text: String, detail: String? = null) {
        val entry = EmulationLogEntry(System.currentTimeMillis(), kind, text, detail)
        state.update { (it + entry).takeLast(MAX) }
    }

    fun clear() {
        state.value = emptyList()
    }
}
