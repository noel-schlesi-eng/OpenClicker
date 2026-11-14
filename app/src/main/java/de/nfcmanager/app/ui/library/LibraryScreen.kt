package de.nfcmanager.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.R
import de.nfcmanager.app.core.ndef.NdefDecoder
import de.nfcmanager.app.data.SavedTag
import de.nfcmanager.app.ui.components.IconTextButton
import de.nfcmanager.app.ui.components.InfoBox
import de.nfcmanager.app.ui.components.TagAvatar
import de.nfcmanager.app.ui.components.TagMetaDialog
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(
    tags: List<SavedTag>?,
    total: Int,
    query: String,
    sort: SortOrder,
    contentPadding: PaddingValues,
    activeTagId: Long,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onOpen: (Long) -> Unit,
    onCreate: (name: String, symbol: String, color: Int, message: ByteArray, typeName: String) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExport(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    var newMessage by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                label = { Text("Suchen (Name, UID, Notiz)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Suche löschen") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTextButton(R.drawable.ic_upload, "Importieren", onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                })
                IconTextButton(R.drawable.ic_download, "Exportieren", onClick = {
                    exportLauncher.launch("nfc-manager-export.json")
                })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SortOrder.values().forEach { order ->
                    FilterChip(selected = sort == order, onClick = { onSort(order) }, label = { Text(order.label) })
                }
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = { showEditor = true }, label = { Text("Neu") }, leadingIcon = { Icon(Icons.Default.Add, null) })
            }
        }

        when {
            tags == null -> Unit
            tags.isEmpty() -> item {
                if (total == 0) {
                    InfoBox(
                        title = "Noch nichts gespeichert",
                        text = "Scanne einen Tag und speichere ihn, importiere eine Exportdatei oder erstelle mit „Neu“ einen eigenen Inhalt.",
                    )
                } else {
                    InfoBox(text = "Keine Treffer für „$query“.")
                }
            }
            else -> items(tags, key = { it.id }) { tag -> TagRow(tag, tag.id == activeTagId) { onOpen(tag.id) } }
        }
    }

    if (showEditor) {
        ContentEditorDialog(
            title = "Neuer Eintrag",
            initial = null,
            onConfirm = { message, typeName ->
                showEditor = false
                newMessage = message to typeName
            },
            onDismiss = { showEditor = false },
        )
    }
    val pending = newMessage
    if (pending != null) {
        TagMetaDialog(
            title = "Eintrag benennen",
            confirmText = "Erstellen",
            initialName = pending.second,
            onConfirm = { name, symbol, color, _ ->
                newMessage = null
                onCreate(name, symbol, color, pending.first, pending.second)
            },
            onDismiss = { newMessage = null },
        )
    }
}

@Composable
private fun TagRow(tag: SavedTag, active: Boolean, onClick: () -> Unit) {
    val e = tag.entity
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TagAvatar(e.symbol, e.colorIndex)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    NdefDecoder.summarize(e.ndefMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    e.tagType + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) Text("● aktiv", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}
