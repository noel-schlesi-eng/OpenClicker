package de.nfcmanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Dialog für Name, Symbol, Farbe (und optional eine Notiz) eines Tags. */
@Composable
fun TagMetaDialog(
    title: String,
    confirmText: String,
    initialName: String,
    initialSymbol: String = "🏷️",
    initialColor: Int = 0,
    initialNote: String? = null,
    onConfirm: (name: String, symbol: String, color: Int, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var symbol by remember { mutableStateOf(initialSymbol) }
    var color by remember { mutableIntStateOf(initialColor) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                SymbolColorPicker(symbol, color, { symbol = it }, { color = it })
                if (initialNote != null) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(2000) },
                        label = { Text("Notiz") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, symbol, color, note) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
