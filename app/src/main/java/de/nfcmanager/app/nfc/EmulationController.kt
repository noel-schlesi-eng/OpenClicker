package de.nfcmanager.app.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import de.nfcmanager.app.core.hce.Type4Tag

/** Momentaufnahme der Emulation für die Oberfläche. */
data class EmulationStatus(
    val enabled: Boolean,
    val activeTagId: Long,
    val activeName: String?,
    val componentEnabled: Boolean,
    /** null = nicht feststellbar. false = andere App hat dieselbe AID und könnte nachfragen. */
    val isDefaultForAid: Boolean?,
    val readCount: Int,
    val lastReadAt: Long,
) {
    val running: Boolean get() = enabled && componentEnabled && activeTagId >= 0
}

/** Schaltet die Emulation ein/aus: Dienst-Komponente aktivieren und Inhalt setzen. */
class EmulationController(context: Context, val store: EmulationStore) {
    private val app = context.applicationContext
    private val component = ComponentName(app, NdefHostApduService::class.java)

    private fun setComponent(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    private fun componentEnabled(): Boolean =
        try {
            app.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            false
        }

    fun activate(tagId: Long, name: String, message: ByteArray) {
        require(message.size <= Type4Tag.MAX_NDEF_MESSAGE_BYTES) { "Nachricht zu groß" }
        store.setActive(tagId, name, message)
        store.setEnabled(true)
        setComponent(true)
        EmulationLog.add(LogKind.OK, "Emulation aktiviert: $name (${message.size} Bytes)")
    }

    fun deactivate() {
        store.setEnabled(false)
        setComponent(false)
        EmulationLog.add(LogKind.INFO, "Emulation beendet")
    }

    /** Wird der aktive Tag gelöscht, wird die Emulation abgeschaltet. */
    fun clear() {
        store.clearActive()
        setComponent(false)
    }

    fun status(): EmulationStatus {
        val enabled = store.isEnabled()
        val isDefault: Boolean? = try {
            val adapter = NfcAdapter.getDefaultAdapter(app)
            if (adapter == null) null else CardEmulation.getInstance(adapter).isDefaultServiceForAid(component, Type4Tag.AID_HEX)
        } catch (e: Exception) {
            null
        }
        return EmulationStatus(
            enabled = enabled,
            activeTagId = store.activeTagId(),
            activeName = store.activeName(),
            componentEnabled = componentEnabled(),
            isDefaultForAid = isDefault,
            readCount = store.readCount(),
            lastReadAt = store.lastReadAt(),
        )
    }

    /** Solange die App im Vordergrund ist, bevorzugt Android diesen Dienst bei AID-Konflikten. */
    fun preferWhileForeground(activity: Activity) {
        try {
            val adapter = NfcAdapter.getDefaultAdapter(app) ?: return
            CardEmulation.getInstance(adapter).setPreferredService(activity, component)
        } catch (e: Exception) {
            // nicht kritisch
        }
    }

    fun releasePreference(activity: Activity) {
        try {
            val adapter = NfcAdapter.getDefaultAdapter(app) ?: return
            CardEmulation.getInstance(adapter).unsetPreferredService(activity)
        } catch (e: Exception) {
            // nicht kritisch
        }
    }
}
