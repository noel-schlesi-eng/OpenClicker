package de.nfcmanager.app.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/** Ergebnis eines Schreibvorgangs; [message] ist ein fertiger, deutscher Text für die Anzeige. */
sealed class WriteOutcome(val message: String) {
    class Success(message: String) : WriteOutcome(message)
    class Failure(message: String) : WriteOutcome(message)
}

/**
 * Schreibt NDEF-Inhalt auf eigene, beschreibbare NFC-Tags (NTAG, Ultralight, Type 4 usw.).
 *
 * Es werden ausschließlich die dokumentierten Klassen [Ndef] und [NdefFormatable] verwendet.
 * Passwortgeschützte oder gesperrte Tags werden nicht angetastet und es gibt bewusst keine Funktion
 * zum dauerhaften Sperren (makeReadOnly), weil diese nicht rückgängig zu machen ist.
 */
object TagWriter {

    fun write(tag: Tag, message: ByteArray): WriteOutcome {
        val ndefMessage = try {
            NdefMessage(message)
        } catch (e: FormatException) {
            return WriteOutcome.Failure("Die NDEF-Nachricht ist ungültig und kann nicht geschrieben werden.")
        } catch (e: Exception) {
            return WriteOutcome.Failure("Die NDEF-Nachricht konnte nicht aufbereitet werden.")
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) return writeNdef(ndef, ndefMessage, message.size)

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) return format(formatable, ndefMessage)

        return WriteOutcome.Failure("Dieser Tag-Typ unterstützt kein Schreiben von NDEF-Inhalt.")
    }

    private fun writeNdef(ndef: Ndef, message: NdefMessage, size: Int): WriteOutcome {
        return try {
            ndef.connect()
            if (!ndef.isWritable) {
                return WriteOutcome.Failure("Dieser Tag ist schreibgeschützt.")
            }
            if (size > ndef.maxSize) {
                return WriteOutcome.Failure("Zu wenig Speicher: benötigt $size Bytes, der Tag fasst ${ndef.maxSize} Bytes.")
            }
            ndef.writeNdefMessage(message)
            WriteOutcome.Success("Inhalt geschrieben ($size Bytes).")
        } catch (e: TagLostException) {
            WriteOutcome.Failure("Der Tag wurde zu früh entfernt. Bitte erneut anlegen und ruhig halten.")
        } catch (e: FormatException) {
            WriteOutcome.Failure("Der Tag hat das Schreiben abgelehnt (ungültiges Format).")
        } catch (e: IOException) {
            WriteOutcome.Failure("Schreiben fehlgeschlagen. Möglicherweise ist der Tag gesperrt oder passwortgeschützt.")
        } catch (e: SecurityException) {
            WriteOutcome.Failure("Der Zugriff auf den Tag wurde vom System verweigert.")
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }

    private fun format(formatable: NdefFormatable, message: NdefMessage): WriteOutcome {
        return try {
            formatable.connect()
            formatable.format(message)
            WriteOutcome.Success("Tag formatiert und beschrieben.")
        } catch (e: TagLostException) {
            WriteOutcome.Failure("Der Tag wurde zu früh entfernt. Bitte erneut anlegen und ruhig halten.")
        } catch (e: FormatException) {
            WriteOutcome.Failure("Der Tag konnte nicht formatiert werden (Inhalt zu groß oder ungültig).")
        } catch (e: IOException) {
            WriteOutcome.Failure("Formatieren fehlgeschlagen. Möglicherweise ist der Tag gesperrt oder geschützt.")
        } catch (e: SecurityException) {
            WriteOutcome.Failure("Der Zugriff auf den Tag wurde vom System verweigert.")
        } finally {
            try {
                formatable.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }
}
