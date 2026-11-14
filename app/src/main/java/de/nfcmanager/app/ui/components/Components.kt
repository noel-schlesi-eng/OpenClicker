package de.nfcmanager.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nfcmanager.app.R
import de.nfcmanager.app.core.ndef.DecodedField
import de.nfcmanager.app.core.ndef.DecodedRecord
import de.nfcmanager.app.core.ndef.VCardInfo
import de.nfcmanager.app.core.tag.DetailSection
import de.nfcmanager.app.ui.theme.TagColors
import de.nfcmanager.app.ui.theme.TagSymbols

@Composable
fun TagAvatar(symbol: String, colorIndex: Int, size: Dp = 44.dp) {
    val color = TagColors[colorIndex.coerceIn(0, TagColors.size - 1)]
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol.ifEmpty { "🏷️" }, fontSize = (size.value * 0.5f).sp, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** Hinweisbox; [warning] färbt sie als Warnung. */
@Composable
fun InfoBox(text: String, modifier: Modifier = Modifier, title: String? = null, warning: Boolean = false) {
    val container = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(if (warning) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = false, sensitive: Boolean = false) {
    var shown by remember { mutableStateOf(!sensitive) }
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (shown) value else "••••••••",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f),
            )
            if (sensitive) {
                TextButton(onClick = { shown = !shown }) { Text(if (shown) "Verbergen" else "Anzeigen") }
            }
        }
    }
}

@Composable
fun DetailSectionCard(section: DetailSection, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            section.rows.forEach { KeyValueRow(it.label, it.value, monospace = looksLikeHex(it.value)) }
        }
    }
}

/** Zeigt einen ausgewerteten NDEF-Record samt Aktionen (Kopieren, Öffnen, Kontakt speichern). */
@Composable
fun DecodedRecordCard(record: DecodedRecord, modifier: Modifier = Modifier, nested: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (nested) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium)
            if (record.summary.isNotEmpty()) {
                Text(record.summary, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${record.typeInfo} · ${record.payloadSize} Bytes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            record.fields.forEach { f: DecodedField ->
                KeyValueRow(f.label, f.value, sensitive = f.sensitive)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val open = record.openUri
                if (open != null) {
                    OutlinedButton(onClick = { openUri(context, open) }) {
                        Icon(painterResource(R.drawable.ic_open), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Öffnen")
                    }
                }
                val copyText = record.fields.firstOrNull { !it.sensitive }?.value ?: record.summary
                if (copyText.isNotEmpty()) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(open ?: copyText)) }) {
                        Icon(painterResource(R.drawable.ic_copy), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kopieren")
                    }
                }
                record.contact?.let { card ->
                    OutlinedButton(onClick = { saveContact(context, card) }) { Text("Kontakt speichern") }
                }
            }
            record.children.forEach {
                Spacer(Modifier.height(8.dp))
                DecodedRecordCard(it, nested = true)
            }
        }
    }
}

fun openUri(context: Context, uri: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // keine passende App installiert
    } catch (e: SecurityException) {
        // vom System blockiert
    }
}

fun saveContact(context: Context, card: VCardInfo) {
    try {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, card.displayName)
            putExtra(ContactsContract.Intents.Insert.COMPANY, card.organization)
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.jobTitle)
            card.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it.value) }
            card.emails.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it.value) }
            if (card.note.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, card.note)
            if (card.addresses.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.POSTAL, card.addresses.first())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // keine Kontakte-App
    }
}

/** Auswahl von Symbol (Emoji) und Farbe. */
@Composable
fun SymbolColorPicker(symbol: String, colorIndex: Int, onSymbol: (String) -> Unit, onColor: (Int) -> Unit) {
    Column {
        Text("Symbol", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TagSymbols.forEach { s ->
                val selected = s == symbol
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onSymbol(s) },
                    contentAlignment = Alignment.Center,
                ) { Text(s, fontSize = 22.sp) }
            }
        }
        OutlinedTextField(
            value = symbol,
            onValueChange = { onSymbol(it.take(8)) },
            label = { Text("Eigenes Symbol (Emoji oder Zeichen)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Farbe", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TagColors.forEachIndexed { i, c ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            if (i == colorIndex) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(0.dp, c),
                            CircleShape,
                        )
                        .clickable { onColor(i) },
                )
            }
        }
    }
}

@Composable
fun IconTextButton(iconRes: Int, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(iconRes), null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

@Composable
fun RowIconButton(iconRes: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(iconRes), contentDescription = description) }
}

private fun looksLikeHex(value: String): Boolean =
    value.length >= 5 && value.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' || it == ':' || it == ' ' }
