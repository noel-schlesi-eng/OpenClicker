package de.nfcmanager.app.ui.scan

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.R
import de.nfcmanager.app.core.ndef.NdefDecoder
import de.nfcmanager.app.core.tag.EmulationAssessor
import de.nfcmanager.app.core.tag.TagSnapshot
import de.nfcmanager.app.nfc.NfcCapabilities
import de.nfcmanager.app.ui.components.DecodedRecordCard
import de.nfcmanager.app.ui.components.DetailSectionCard
import de.nfcmanager.app.ui.components.InfoBox
import de.nfcmanager.app.ui.components.KeyValueRow
import de.nfcmanager.app.ui.components.SectionTitle
import de.nfcmanager.app.ui.components.TagMetaDialog

@Composable
fun ScanScreen(
    caps: NfcCapabilities,
    state: ScanUiState,
    contentPadding: PaddingValues,
    onSave: (name: String, symbol: String, color: Int) -> Unit,
    onCancelWrite: () -> Unit,
    onDismissWriteMessage: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var showSave by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!caps.hasNfc) {
            item {
                InfoBox(
                    title = "Kein NFC verfügbar",
                    text = "Dieses Gerät hat keinen NFC-Chip oder das System meldet keinen. Tags lesen und Emulation sind nicht möglich; gespeicherte Tags kannst du trotzdem verwalten, exportieren und importieren.",
                    warning = true,
                )
            }
        } else if (!caps.nfcEnabled) {
            item {
                InfoBox(title = "NFC ist ausgeschaltet", text = "Schalte NFC in den Systemeinstellungen ein, damit Tags gelesen werden können.", warning = true)
            }
            item {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("NFC-Einstellungen öffnen")
                }
            }
        }

        val write = state.write
        if (write != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Schreib-Modus", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Halte einen beschreibbaren, eigenen NFC-Tag an die Rückseite. „${write.name}“ (${write.message.size} Bytes) " +
                                "wird darauf geschrieben und ersetzt den bisherigen Inhalt.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onCancelWrite) { Text("Abbrechen") }
                    }
                }
            }
        }
        val writeMessage = state.writeMessage
        if (writeMessage != null) {
            item {
                InfoBox(text = writeMessage, warning = state.writeOk == false, title = if (state.writeOk == true) "Geschrieben" else "Schreiben fehlgeschlagen")
                TextButton(onClick = onDismissWriteMessage) { Text("Schließen") }
            }
        }

        val snapshot = state.snapshot
        if (snapshot == null) {
            if (write == null && caps.canScan) {
                item { WaitingCard() }
            }
        } else {
            resultItems(snapshot, state.savedId, onSaveClick = { showSave = true }, onClear = onClear)
        }
    }

    if (showSave && state.snapshot != null) {
        TagMetaDialog(
            title = "Tag speichern",
            confirmText = "Speichern",
            initialName = state.snapshot.tagType,
            onConfirm = { name, symbol, color, _ ->
                showSave = false
                onSave(name, symbol, color)
            },
            onDismiss = { showSave = false },
        )
    }
}

@Composable
private fun WaitingCard() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_nfc), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Text("Bereit zum Scannen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "Halte einen NFC-Sticker, -Anhänger oder eine Karte an die Rückseite deines Smartphones (meist oben, in der Nähe der Kamera). Bewege ihn nicht, bis der Ton erklingt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(
    snapshot: TagSnapshot,
    savedId: Long?,
    onSaveClick: () -> Unit,
    onClear: () -> Unit,
) {
    item {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(snapshot.tagType, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    "UID " + snapshot.uidHex,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    snapshot.techList.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedId == null) {
                        FilledTonalButton(onClick = onSaveClick) { Text("In Bibliothek speichern") }
                    } else {
                        Text("✓ Gespeichert", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    }
                    OutlinedButton(onClick = onClear) { Text("Verwerfen") }
                }
            }
        }
    }
    snapshot.notes.forEach { note -> item { InfoBox(text = note, warning = true) } }

    item { SectionTitle("Inhalt (NDEF)") }
    val bytes = snapshot.ndefMessage
    if (bytes == null) {
        item { InfoBox(text = "Dieser Tag enthält keinen lesbaren NDEF-Inhalt. Die technischen Daten stehen weiter unten.") }
    } else {
        val decoded = NdefDecoder.decodeMessage(bytes)
        if (decoded.error != null) {
            item { InfoBox(text = "Der NDEF-Inhalt ist beschädigt: ${decoded.error}", warning = true) }
        }
        decoded.records.forEach { r -> item { DecodedRecordCard(r) } }
        item { KeyValueRow("NDEF-Größe", "${bytes.size} Bytes") }
    }

    val assessment = EmulationAssessor.assess(snapshot.family, snapshot.ndefMessage)
    item { SectionTitle("Emulation") }
    item { InfoBox(title = assessment.headline, text = assessment.explanation, warning = !assessment.possible) }

    item { SectionTitle("Technische Details") }
    snapshot.sections.forEach { s -> item { DetailSectionCard(s) } }

    val dump = snapshot.rawDump
    if (dump != null) {
        item { RawDumpCard(dump) }
    }
}

@Composable
fun RawDumpCard(dump: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Speicherabbild (Hex)", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Einklappen" else "Anzeigen") }
            }
            if (expanded) {
                Text(dump, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
