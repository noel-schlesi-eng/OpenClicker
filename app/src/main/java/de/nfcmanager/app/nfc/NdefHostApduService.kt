package de.nfcmanager.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.hce.ApduEvent
import de.nfcmanager.app.core.hce.Type4Tag
import de.nfcmanager.app.core.hce.Type4TagEmulator

/**
 * HCE-Dienst: stellt den aktiven NDEF-Inhalt als NFC-Forum-Type-4-Tag bereit.
 * Android startet ihn bei Bedarf selbst, sobald ein Lesegerät die AID D2760000850101 wählt –
 * die App muss dafür nicht geöffnet sein.
 */
class NdefHostApduService : HostApduService() {

    private lateinit var store: EmulationStore
    private lateinit var emulator: Type4TagEmulator

    override fun onCreate() {
        super.onCreate()
        store = EmulationStore(applicationContext)
        emulator = Type4TagEmulator {
            try {
                if (store.isEnabled()) store.activeMessage() else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (commandApdu == null) return Type4Tag.sw(Type4Tag.SW_WRONG_LENGTH)
        val result = emulator.process(commandApdu)
        try {
            val kind = when (result.event) {
                ApduEvent.NDEF_READ_COMPLETE -> LogKind.OK
                ApduEvent.APPLICATION_SELECTED, ApduEvent.FILE_SELECTED, ApduEvent.CC_READ,
                ApduEvent.NDEF_LENGTH_READ, ApduEvent.NDEF_READ -> LogKind.INFO
                ApduEvent.WRITE_DENIED, ApduEvent.APPLICATION_REJECTED, ApduEvent.FILE_REJECTED -> LogKind.WARN
                ApduEvent.UNSUPPORTED, ApduEvent.ERROR -> LogKind.ERROR
            }
            val shown = if (commandApdu.size > 24) commandApdu.copyOf(24) else commandApdu
            EmulationLog.add(
                kind,
                result.description,
                "Befehl: " + Hex.encode(shown, " ") + (if (commandApdu.size > 24) " …" else "") +
                    "  Antwort-Status: %04X".format(result.statusWord),
            )
            if (result.event == ApduEvent.NDEF_READ_COMPLETE) store.recordRead()
        } catch (e: Exception) {
            // Protokollfehler dürfen die Antwort an das Lesegerät nie beeinflussen.
        }
        return result.response
    }

    override fun onDeactivated(reason: Int) {
        emulator.reset()
        EmulationLog.add(
            LogKind.INFO,
            if (reason == DEACTIVATION_LINK_LOSS) "Verbindung zum Lesegerät beendet" else "Lesegerät hat eine andere Anwendung gewählt",
        )
    }
}
