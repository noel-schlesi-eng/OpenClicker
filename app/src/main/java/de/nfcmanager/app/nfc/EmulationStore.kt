package de.nfcmanager.app.nfc

import android.content.Context
import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.hce.Type4Tag

/**
 * Dauerhafter Zustand der Emulation. Liegt in SharedPreferences, damit der HCE-Dienst den Inhalt auch dann
 * kennt, wenn Android ihn bei geschlossener App in einem frischen Prozess startet.
 */
class EmulationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun activeTagId(): Long = prefs.getLong(KEY_TAG_ID, -1L)

    fun activeName(): String? = prefs.getString(KEY_NAME, null)

    /** Aktive NDEF-Nachricht oder null, wenn keine (gültige) gesetzt ist. */
    fun activeMessage(): ByteArray? {
        val hex = prefs.getString(KEY_MESSAGE, null) ?: return null
        val bytes = Hex.tryDecode(hex) ?: return null
        return if (bytes.size <= Type4Tag.MAX_NDEF_MESSAGE_BYTES) bytes else null
    }

    fun readCount(): Int = prefs.getInt(KEY_READS, 0)

    fun lastReadAt(): Long = prefs.getLong(KEY_LAST_READ, 0L)

    fun setActive(tagId: Long, name: String, message: ByteArray) {
        prefs.edit()
            .putLong(KEY_TAG_ID, tagId)
            .putString(KEY_NAME, name)
            .putString(KEY_MESSAGE, Hex.encode(message))
            .commit()
    }

    fun clearActive() {
        prefs.edit().remove(KEY_TAG_ID).remove(KEY_NAME).remove(KEY_MESSAGE).putBoolean(KEY_ENABLED, false).commit()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun recordRead() {
        prefs.edit().putInt(KEY_READS, readCount() + 1).putLong(KEY_LAST_READ, System.currentTimeMillis()).apply()
    }

    private companion object {
        const val FILE = "emulation"
        const val KEY_ENABLED = "enabled"
        const val KEY_TAG_ID = "tag_id"
        const val KEY_NAME = "name"
        const val KEY_MESSAGE = "message_hex"
        const val KEY_READS = "reads"
        const val KEY_LAST_READ = "last_read"
    }
}
