package de.nfcmanager.app.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.data.ThemeMode

@Composable
fun SettingsDialog(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Darstellung", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                ThemeMode.values().forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onThemeMode(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onThemeMode(mode) })
                        Text(mode.label)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Systemfarben verwenden", Modifier.weight(1f))
                        Switch(checked = dynamicColor, onCheckedChange = onDynamicColor)
                    }
                }
                Text("Über diese App", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp))
                Text(
                    "Die App dient zur Verwaltung eigener, ungeschützter NFC-Tags und zur Bereitstellung von NDEF-Inhalten. " +
                        "Sie umgeht keine Zugriffsschutz-, Verschlüsselungs- oder Authentifizierungsverfahren und kopiert keine " +
                        "geschützten Zutritts- oder Zahlungskarten.\n\n" +
                        "Alle Daten bleiben lokal auf diesem Gerät. Es gibt kein Konto, keine Werbung, keine Käufe und kein Tracking; " +
                        "die App benötigt keine Internetberechtigung.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
