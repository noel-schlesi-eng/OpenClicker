package de.nfcmanager.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

/**
 * Schaltet den Lese-Modus (NfcAdapter.enableReaderMode) ein und aus. Solange er aktiv ist, pausiert
 * Android die Karten-Emulation; deshalb läuft er nur im Tab „Scannen“.
 */
class NfcReaderController(private val activity: Activity) {

    /** @return true, wenn der Lese-Modus aktiv ist. */
    fun enable(onTag: (Tag) -> Unit): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        return try {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
            adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disable() {
        try {
            NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        } catch (e: Exception) {
            // Adapter nicht mehr verfügbar
        }
    }
}
