package de.nfcmanager.app.core.ndef

import java.io.ByteArrayOutputStream

/** Sicherheitsarten, die der Editor für WLAN-Tags anbietet. */
enum class WifiSecurity(val label: String, val authType: Int, val encType: Int) {
    OPEN("Offen (ohne Passwort)", 0x0001, 0x0001),
    WPA2_PSK("WPA2-Personal", 0x0020, 0x0008),
    WPA_WPA2_PSK("WPA/WPA2-Personal (gemischt)", 0x0022, 0x000C),
}

data class WifiInfo(
    val ssid: String,
    val password: String,
    val authType: Int,
    val encType: Int,
) {
    val securityLabel: String get() = WifiConfig.securityLabel(authType, encType)
}

/**
 * Wi-Fi Simple Configuration (WSC) in NDEF: MIME-Typ "application/vnd.wfa.wsc".
 * Die Nutzlast besteht aus TLV-Attributen (2 Byte ID, 2 Byte Länge, Wert).
 */
object WifiConfig {
    const val MIME_TYPE = "application/vnd.wfa.wsc"

    private const val ATTR_CREDENTIAL = 0x100E
    private const val ATTR_NETWORK_INDEX = 0x1026
    private const val ATTR_SSID = 0x1045
    private const val ATTR_AUTH_TYPE = 0x1003
    private const val ATTR_ENC_TYPE = 0x100F
    private const val ATTR_NETWORK_KEY = 0x1027
    private const val ATTR_MAC_ADDRESS = 0x1020
    private const val ATTR_VERSION = 0x104A

    /** Liest SSID, Passwort und Sicherheitstyp. Null, wenn keine SSID gefunden wurde. */
    fun parse(payload: ByteArray): WifiInfo? {
        val attrs = HashMap<Int, ByteArray>()
        collect(payload, attrs, 0)
        val ssidBytes = attrs[ATTR_SSID] ?: return null
        val key = attrs[ATTR_NETWORK_KEY]?.let { String(it, Charsets.UTF_8) } ?: ""
        val auth = attrs[ATTR_AUTH_TYPE]?.let { u16(it, 0) } ?: 0
        val enc = attrs[ATTR_ENC_TYPE]?.let { u16(it, 0) } ?: 0
        return WifiInfo(String(ssidBytes, Charsets.UTF_8), key, auth, enc)
    }

    private fun collect(data: ByteArray, into: MutableMap<Int, ByteArray>, depth: Int) {
        if (depth > 3) return
        var p = 0
        while (p + 4 <= data.size) {
            val id = u16(data, p)
            val len = u16(data, p + 2)
            p += 4
            if (len < 0 || p + len > data.size) return
            val value = data.copyOfRange(p, p + len)
            if (id == ATTR_CREDENTIAL) {
                collect(value, into, depth + 1)
            } else if (!into.containsKey(id)) {
                into[id] = value
            }
            p += len
        }
    }

    /** Erzeugt die WSC-Nutzlast für einen WLAN-Tag. */
    fun build(ssid: String, password: String, security: WifiSecurity): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val keyBytes = if (security == WifiSecurity.OPEN) ByteArray(0) else password.toByteArray(Charsets.UTF_8)

        val credential = ByteArrayOutputStream()
        attr(credential, ATTR_NETWORK_INDEX, byteArrayOf(0x01))
        attr(credential, ATTR_SSID, ssidBytes)
        attr(credential, ATTR_AUTH_TYPE, u16bytes(security.authType))
        attr(credential, ATTR_ENC_TYPE, u16bytes(security.encType))
        attr(credential, ATTR_NETWORK_KEY, keyBytes)
        attr(credential, ATTR_MAC_ADDRESS, ByteArray(6) { 0xFF.toByte() })

        val out = ByteArrayOutputStream()
        attr(out, ATTR_VERSION, byteArrayOf(0x10))
        attr(out, ATTR_CREDENTIAL, credential.toByteArray())
        return out.toByteArray()
    }

    fun securityLabel(auth: Int, enc: Int): String {
        val authText = when (auth) {
            0x0001 -> "Offen"
            0x0002 -> "WPA-Personal"
            0x0004 -> "WEP (Shared)"
            0x0008 -> "WPA-Enterprise"
            0x0010 -> "WPA2-Enterprise"
            0x0020 -> "WPA2-Personal"
            0x0022 -> "WPA/WPA2-Personal"
            else -> "Typ 0x%04X".format(auth)
        }
        val encText = when (enc) {
            0x0001 -> ""
            0x0002 -> "WEP"
            0x0004 -> "TKIP"
            0x0008 -> "AES"
            0x000C -> "TKIP/AES"
            else -> "Verschlüsselung 0x%04X".format(enc)
        }
        return if (encText.isEmpty()) authText else "$authText ($encText)"
    }

    private fun attr(out: ByteArrayOutputStream, id: Int, value: ByteArray) {
        out.write(u16bytes(id))
        out.write(u16bytes(value.size))
        out.write(value)
    }

    private fun u16bytes(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u16(b: ByteArray, off: Int): Int =
        if (off + 1 < b.size) ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF) else 0
}

/** Bluetooth-Handover (Classic): MIME-Typ "application/vnd.bluetooth.ep.oob". */
data class BluetoothOob(val address: String, val name: String?, val deviceClass: String?)

object BluetoothOobParser {
    const val MIME_TYPE_CLASSIC = "application/vnd.bluetooth.ep.oob"

    fun parse(payload: ByteArray): BluetoothOob? {
        if (payload.size < 8) return null
        val total = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val address = (7 downTo 2).joinToString(":") { i ->
            (payload[i].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
        var name: String? = null
        var cod: String? = null
        val end = minOf(total, payload.size)
        var p = 8
        while (p + 1 < end) {
            val len = payload[p].toInt() and 0xFF
            // Eine EIR-Struktur belegt len + 1 Byte: Längenbyte, Typ und len - 1 Datenbytes.
            if (len == 0 || p + 1 + len > end) break
            val type = payload[p + 1].toInt() and 0xFF
            val dataStart = p + 2
            val dataEnd = p + 1 + len
            if (dataEnd > dataStart) {
                val data = payload.copyOfRange(dataStart, dataEnd)
                when (type) {
                    0x08, 0x09 -> name = String(data, Charsets.UTF_8)
                    0x0D -> cod = data.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
                }
            }
            p += len + 1
        }
        return BluetoothOob(address, name, cod)
    }
}
