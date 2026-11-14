package de.nfcmanager.app.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build

/** Momentaufnahme dessen, was das Gerät in Sachen NFC kann. */
data class NfcCapabilities(
    val hasNfc: Boolean,
    val nfcEnabled: Boolean,
    /** Host Card Emulation mit ISO-DEP (HostApduService). */
    val hasHce: Boolean,
    /** Host Card Emulation für NFC-F (HostNfcFService). */
    val hasHceF: Boolean,
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
) {
    val isSamsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)

    /** Lesen und Schreiben von Tags ist möglich. */
    val canScan: Boolean get() = hasNfc && nfcEnabled

    /** Emulation per HCE ist möglich. */
    val canEmulate: Boolean get() = hasNfc && nfcEnabled && hasHce

    val deviceName: String
        get() {
            val maker = manufacturer.replaceFirstChar { it.uppercaseChar() }
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$maker $model"
        }
}

/** Prüft beim Start (und bei Rückkehr in die App), welche NFC-Funktionen verfügbar sind. */
object NfcCapabilityChecker {

    fun check(context: Context): NfcCapabilities {
        val pm = context.packageManager
        val adapter: NfcAdapter? = try {
            NfcAdapter.getDefaultAdapter(context)
        } catch (e: Exception) {
            null
        }
        val hasNfcFeature = try {
            pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        } catch (e: Exception) {
            false
        }
        val enabled = try {
            adapter?.isEnabled ?: false
        } catch (e: Exception) {
            false
        }
        val hce = try {
            pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        } catch (e: Exception) {
            false
        }
        val hceF = try {
            pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)
        } catch (e: Exception) {
            false
        }
        return NfcCapabilities(
            hasNfc = adapter != null && hasNfcFeature,
            nfcEnabled = enabled,
            hasHce = hce,
            hasHceF = hceF,
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
        )
    }
}
