package de.nfcmanager.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import de.nfcmanager.app.core.json.JsonException
import de.nfcmanager.app.data.SavedTag
import de.nfcmanager.app.ui.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class SortOrder(val label: String) { NEWEST("Zuletzt geändert"), NAME("Name A–Z") }

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : BaseViewModel(app) {
    private val repository = container.repository

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(SortOrder.NEWEST)
    private val openState = MutableStateFlow<Long?>(null)
    val openId: StateFlow<Long?> = openState.asStateFlow()

    val tags: StateFlow<List<SavedTag>?> = combine(repository.observeAll(), query, sort) { all, q, order ->
        val needle = q.trim()
        val filtered = if (needle.isEmpty()) all else all.filter {
            it.entity.name.contains(needle, ignoreCase = true) ||
                it.entity.uidHex.contains(needle, ignoreCase = true) ||
                it.entity.note.contains(needle, ignoreCase = true) ||
                it.entity.tagType.contains(needle, ignoreCase = true)
        }
        if (order == SortOrder.NAME) filtered.sortedBy { it.entity.name.lowercase() } else filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val total: StateFlow<Int> = repository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val detail: StateFlow<SavedTag?> = openState
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun open(id: Long?) {
        openState.value = id
    }

    fun updateMeta(id: Long, name: String, symbol: String, color: Int, note: String) {
        viewModelScope.launch {
            repository.updateMeta(id, name, symbol, color, note)
            container.post("Änderungen gespeichert")
        }
    }

    fun createEntry(name: String, symbol: String, color: Int, message: ByteArray, typeName: String) {
        viewModelScope.launch {
            val id = repository.createFromMessage(name, symbol, color, message, typeName)
            container.post("Eintrag erstellt")
            openState.value = id
        }
    }

    fun replaceMessage(id: Long, message: ByteArray) {
        viewModelScope.launch {
            repository.updateMessage(id, message)
            if (container.emulationStore.activeTagId() == id) {
                val updated = repository.get(id)
                if (updated != null) container.emulation.activate(id, updated.entity.name, message)
            }
            container.post("Inhalt aktualisiert")
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            if (container.emulationStore.activeTagId() == id) container.emulation.clear()
            repository.delete(id)
            if (openState.value == id) openState.value = null
            container.post("Tag gelöscht")
        }
    }

    fun activateEmulation(tag: SavedTag): Boolean {
        val message = tag.entity.ndefMessage ?: return false
        return try {
            container.emulation.activate(tag.id, tag.entity.name, message)
            true
        } catch (e: IllegalArgumentException) {
            container.post("Der Inhalt ist zu groß für die Emulation")
            false
        }
    }

    fun exportTo(uri: Uri, ids: Set<Long>?) {
        viewModelScope.launch {
            try {
                val text = repository.exportJson(ids)
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Datei nicht beschreibbar")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                container.post("Export gespeichert")
            } catch (e: Exception) {
                container.post("Export fehlgeschlagen")
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IOException("Datei nicht lesbar")
                    input.use {
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = it.read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                            if (buffer.size() > MAX_IMPORT_BYTES) throw IOException("Datei zu groß")
                        }
                        String(buffer.toByteArray(), Charsets.UTF_8)
                    }
                }
                val result = repository.importJson(text)
                val parts = ArrayList<String>()
                parts.add("${result.imported} importiert")
                if (result.skipped > 0) parts.add("${result.skipped} bereits vorhanden")
                if (result.invalid > 0) parts.add("${result.invalid} ungültig")
                container.post(parts.joinToString(", "))
            } catch (e: JsonException) {
                container.post("Import nicht möglich: ${e.message}")
            } catch (e: Exception) {
                container.post("Import fehlgeschlagen")
            }
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
