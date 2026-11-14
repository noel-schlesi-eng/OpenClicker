package de.nfcmanager.app.ui.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.core.hce.Type4Tag
import de.nfcmanager.app.core.ndef.NdefDecoder
import de.nfcmanager.app.core.tag.EmulationAssessor
import de.nfcmanager.app.core.tag.TagFamily
import de.nfcmanager.app.data.SavedTag
import de.nfcmanager.app.nfc.EmulationLogEntry
import de.nfcmanager.app.nfc.EmulationStatus
import de.nfcmanager.app.nfc.LogKind
import de.nfcmanager.app.nfc.NfcCapabilities
import de.nfcmanager.app.ui.components.InfoBox
import de.nfcmanager.app.ui.components.SectionTitle
import de.nfcmanager.app.ui.components.TagAvatar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun EmulationScreen(
    caps: NfcCapabilities,
    status: EmulationStatus,
    candidates: List<SavedTag>,
    log: List<EmulationLogEntry>,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onActivate: (SavedTag) -> Unit,
    onDeactivate: () -> Unit,
    onClearLog: () -> Unit,
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(1000)
        }
    }
    var showLimits by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!caps.hasNfc) {
            item { InfoBox(title = "Kein NFC verfügbar", text = "Dieses Gerät hat keinen NFC-Chip. Emulation ist nicht möglich.", warning = true) }
        } else if (!caps.hasHce) {
            item {
                InfoBox(
                    title = "Keine Kartenemulation",
                    text = "Dieses Gerät meldet keine Host-basierte Kartenemulation (HCE). Die Emulation kann hier nicht funktionieren.",
                    warning = true,
                )
            }
        } else if (!caps.nfcEnabled) {
            item { InfoBox(title = "NFC ist ausgeschaltet", text = "Schalte NFC in den Systemeinstellungen ein, damit die Emulation funktioniert.", warning = true) }
        }

        item { StatusCard(status, onDeactivate) }

        if (status.running && status.isDefaultForAid == false) {
            item {
                InfoBox(
                    title = "Andere App für dieselbe Kennung",
                    text = "Eine weitere App hat sich für die NDEF-Kennung registriert. Solange diese App geöffnet ist, bevorzugt Android sie; " +
                        "sonst fragt das System beim Auflegen evtl. nach.",
                    warning = true,
                )
            }
        }
        if (status.running) {
            item {
                InfoBox(
                    title = "So funktioniert es",
                    text = "Schalte den Bildschirm ein, entsperre ihn bei Bedarf und halte die Rückseite des Smartphones an das Lesegerät. " +
                        "Die App muss nicht geöffnet sein. Im Tab „Scannen“ ist die Emulation pausiert.",
                )
            }
        }

        item { SectionTitle("Inhalt wählen") }
        if (candidates.isEmpty()) {
            item { InfoBox(text = "Es gibt noch keinen gespeicherten Tag mit NDEF-Inhalt. Scanne einen Tag oder erstelle in der Bibliothek einen Eintrag.") }
        } else {
            items(candidates, key = { it.id }) { tag ->
                CandidateRow(tag, active = status.running && status.activeTagId == tag.id, onActivate = { onActivate(tag) })
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Grenzen der Emulation", Modifier.weight(1f))
                TextButton(onClick = { showLimits = !showLimits }) { Text(if (showLimits) "Einklappen" else "Anzeigen") }
            }
        }
        if (showLimits) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmulationAssessor.GENERAL_LIMITS.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "Verwendet wird ausschließlich Androids dokumentierte HCE-Schnittstelle (HostApduService) mit der NFC-Forum-Kennung " +
                                "${Type4Tag.AID_HEX} (Type 4 Tag, Mapping Version 2.0). Lesegeräte, die nur Version 1.0 sprechen, erkennen sie nicht.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Gerät: ${caps.deviceName} · Android ${caps.androidRelease} (API ${caps.sdkInt})" +
                                if (caps.isSamsung) " · Samsung: Falls nichts erkannt wird, in den Einstellungen unter Verbindungen → NFC und kontaktlose Zahlungen prüfen, ob NFC aktiv ist." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Protokoll", Modifier.weight(1f))
                TextButton(onClick = onClearLog, enabled = log.isNotEmpty()) { Text("Leeren") }
            }
        }
        if (log.isEmpty()) {
            item { Text("Noch keine Zugriffe. Sobald ein Lesegerät den Tag anspricht, erscheinen hier die Schritte.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(log.takeLast(60).reversed()) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun StatusCard(status: EmulationStatus, onDeactivate: () -> Unit) {
    val running = status.running
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (running) "Emulation aktiv" else "Emulation aus", style = MaterialTheme.typography.titleLarge)
            if (running) {
                Text("„${status.activeName.orEmpty()}“ wird als NFC-Tag bereitgestellt.", style = MaterialTheme.typography.bodyMedium)
                val last = if (status.lastReadAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(status.lastReadAt)) else "noch nie"
                Text("Vollständig gelesen: ${status.readCount}× · zuletzt: $last", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDeactivate) { Text("Emulation beenden") }
            } else {
                Text("Wähle unten einen gespeicherten Inhalt, um ihn per NFC bereitzustellen.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CandidateRow(tag: SavedTag, active: Boolean, onActivate: () -> Unit) {
    val e = tag.entity
    val assessment = EmulationAssessor.assess(TagFamily.fromName(e.tagFamily), e.ndefMessage)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TagAvatar(e.symbol, e.colorIndex)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(NdefDecoder.summarize(e.ndefMessage), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when {
                active -> Text("● aktiv", color = MaterialTheme.colorScheme.primary)
                assessment.possible -> FilledTonalButton(onClick = onActivate) { Text("Aktivieren") }
                else -> OutlinedButton(onClick = {}, enabled = false) { Text(assessment.headline) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: EmulationLogEntry) {
    val color = when (entry.kind) {
        LogKind.OK -> MaterialTheme.colorScheme.primary
        LogKind.WARN -> MaterialTheme.colorScheme.tertiary
        LogKind.ERROR -> MaterialTheme.colorScheme.error
        LogKind.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.time)) + "  " + entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        if (entry.detail != null) {
            Text(entry.detail, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
