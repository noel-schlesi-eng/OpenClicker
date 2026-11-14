package de.nfcmanager.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.R
import de.nfcmanager.app.core.ndef.NdefDecoder
import de.nfcmanager.app.core.tag.EmulationAssessor
import de.nfcmanager.app.core.tag.TagFamily
import de.nfcmanager.app.data.SavedTag
import de.nfcmanager.app.ui.components.DecodedRecordCard
import de.nfcmanager.app.ui.components.DetailSectionCard
import de.nfcmanager.app.ui.components.IconTextButton
import de.nfcmanager.app.ui.components.InfoBox
import de.nfcmanager.app.ui.components.KeyValueRow
import de.nfcmanager.app.ui.components.SectionTitle
import de.nfcmanager.app.ui.components.TagAvatar
import de.nfcmanager.app.ui.components.TagMetaDialog
import de.nfcmanager.app.ui.scan.RawDumpCard
import java.text.DateFormat
import java.util.Date

@Composable
fun TagDetailScreen(
    tag: SavedTag?,
    contentPadding: PaddingValues,
    isActive: Boolean,
    onEmulate: () -> Unit,
    onWrite: () -> Unit,
    onUpdateMeta: (name: String, symbol: String, color: Int, note: String) -> Unit,
    onReplaceMessage: (ByteArray) -> Unit,
    onExportOne: () -> Unit,
    onDelete: () -> Unit,
) {
    if (tag == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) { Text("Eintrag nicht gefunden.") }
        return
    }
    val e = tag.entity
    var showMeta by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val assessment = EmulationAssessor.assess(TagFamily.fromName(e.tagFamily), e.ndefMessage)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagAvatar(e.symbol, e.colorIndex, size = 64.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(e.name, style = MaterialTheme.typography.headlineSmall)
                    Text(e.tagType, style = MaterialTheme.typography.bodyMedium)
                    if (e.uidHex.isNotEmpty()) {
                        Text("UID ${e.uidHex}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    Text("● Wird gerade emuliert", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                } else {
                    Button(onClick = onEmulate, enabled = assessment.possible) { Text("Emulieren") }
                }
                IconTextButton(R.drawable.ic_write, "Auf Tag schreiben", onWrite)
                OutlinedButton(onClick = { showMeta = true }) { Text("Name & Symbol") }
                OutlinedButton(onClick = { showEditor = true }) { Text("Inhalt bearbeiten") }
                IconTextButton(R.drawable.ic_download, "Exportieren", onExportOne)
                TextButton(onClick = { showDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (e.note.isNotBlank()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Notiz", style = MaterialTheme.typography.titleSmall)
                        Text(e.note)
                    }
                }
            }
        }

        item { SectionTitle("Inhalt (NDEF)") }
        val bytes = e.ndefMessage
        if (bytes == null || bytes.isEmpty()) {
            item { InfoBox(text = "Kein NDEF-Inhalt gespeichert. Über „Inhalt bearbeiten“ kannst du einen Inhalt erstellen.") }
        } else {
            val decoded = NdefDecoder.decodeMessage(bytes)
            if (decoded.error != null) item { InfoBox(text = "Der NDEF-Inhalt ist beschädigt: ${decoded.error}", warning = true) }
            decoded.records.forEach { r -> item { DecodedRecordCard(r) } }
            item { KeyValueRow("NDEF-Größe", "${bytes.size} Bytes") }
        }

        item { SectionTitle("Emulation") }
        item { InfoBox(title = assessment.headline, text = assessment.explanation, warning = !assessment.possible) }

        item { SectionTitle("Technische Details") }
        item {
            val rows = ArrayList<Pair<String, String>>()
            rows.add("Quelle" to when (e.source) { "scan" -> "Gescannt"; "import" -> "Importiert"; else -> "Selbst erstellt" })
            rows.add("Erstellt" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.createdAt)))
            rows.add("Geändert" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.updatedAt)))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) { rows.forEach { (k, v) -> KeyValueRow(k, v) } }
            }
        }
        tag.sections.forEach { s -> item { DetailSectionCard(s) } }
        e.rawDump?.let { item { RawDumpCard(it) } }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showMeta) {
        TagMetaDialog(
            title = "Name und Symbol",
            confirmText = "Speichern",
            initialName = e.name,
            initialSymbol = e.symbol,
            initialColor = e.colorIndex,
            initialNote = e.note,
            onConfirm = { name, symbol, color, note ->
                showMeta = false
                onUpdateMeta(name, symbol, color, note)
            },
            onDismiss = { showMeta = false },
        )
    }
    if (showEditor) {
        ContentEditorDialog(
            title = "Inhalt bearbeiten",
            initial = e.ndefMessage,
            onConfirm = { message, _ ->
                showEditor = false
                onReplaceMessage(message)
            },
            onDismiss = { showEditor = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Eintrag löschen?") },
            text = { Text("„${e.name}“ wird dauerhaft aus der Bibliothek entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Abbrechen") } },
        )
    }
}
