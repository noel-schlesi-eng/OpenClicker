package de.nfcmanager.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.nfcmanager.app.core.hce.Type4Tag
import de.nfcmanager.app.core.ndef.DraftException
import de.nfcmanager.app.core.ndef.DraftField
import de.nfcmanager.app.core.ndef.DraftKind
import de.nfcmanager.app.core.ndef.FieldInput
import de.nfcmanager.app.core.ndef.NdefDraft
import de.nfcmanager.app.core.ndef.NdefDrafts

/** Bearbeitet NDEF-Inhalt über Formulare (URL, Text, Kontakt, WLAN, ...). Gibt die fertige Nachricht zurück. */
@Composable
fun ContentEditorDialog(
    title: String,
    initial: ByteArray?,
    onConfirm: (message: ByteArray, typeName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var drafts by remember {
        mutableStateOf(
            NdefDrafts.fromMessage(initial).ifEmpty { listOf(NdefDraft.empty(DraftKind.URL)) },
        )
    }

    val built: Result<ByteArray> = try {
        Result.success(NdefDrafts.toMessage(drafts))
    } catch (e: DraftException) {
        Result.failure(e)
    }
    val message: ByteArray? = built.getOrNull()
    val error: String? = built.exceptionOrNull()?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                drafts.forEachIndexed { index, draft ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    DraftEditor(
                        draft = draft,
                        onChange = { changed -> drafts = drafts.toMutableList().also { it[index] = changed } },
                        onRemove = if (drafts.size > 1) ({ drafts = drafts.toMutableList().also { it.removeAt(index) } }) else null,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { drafts = drafts + NdefDraft.empty(DraftKind.TEXT) }) { Text("Weiteren Datensatz hinzufügen") }
                Spacer(Modifier.height(8.dp))
                val size = message?.size
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (size != null) {
                    Text(
                        "Größe: $size von höchstens ${Type4Tag.MAX_NDEF_MESSAGE_BYTES} Bytes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = message != null,
                onClick = { message?.let { onConfirm(it, drafts.first().kind.label) } },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun DraftEditor(draft: NdefDraft, onChange: (NdefDraft) -> Unit, onRemove: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
            OutlinedButton(onClick = { menuOpen = true }) { Text(draft.kind.label) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DraftKind.values().forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = {
                            menuOpen = false
                            if (kind != draft.kind) onChange(NdefDraft.empty(kind))
                        },
                    )
                }
            }
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Entfernen") }
    }
    draft.kind.fields.forEach { field ->
        Spacer(Modifier.height(8.dp))
        FieldEditor(field, draft.get(field.key)) { onChange(draft.with(field.key, it)) }
    }
}

@Composable
private fun FieldEditor(field: DraftField, value: String, onValue: (String) -> Unit) {
    if (field.choices.isNotEmpty()) {
        Text(field.label, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field.choices.forEach { (stored, shown) ->
                FilterChip(selected = value == stored, onClick = { onValue(stored) }, label = { Text(shown) })
            }
        }
        return
    }
    val keyboard = when (field.input) {
        FieldInput.PHONE -> KeyboardType.Phone
        FieldInput.EMAIL -> KeyboardType.Email
        FieldInput.URL -> KeyboardType.Uri
        FieldInput.DECIMAL -> KeyboardType.Decimal
        FieldInput.PASSWORD -> KeyboardType.Password
        FieldInput.HEX -> KeyboardType.Ascii
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(if (field.required) field.label + " *" else field.label) },
        placeholder = if (field.hint.isNotEmpty()) ({ Text(field.hint) }) else null,
        singleLine = field.input != FieldInput.MULTILINE,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (field.input == FieldInput.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
