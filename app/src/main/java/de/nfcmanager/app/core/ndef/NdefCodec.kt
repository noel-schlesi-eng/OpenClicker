package de.nfcmanager.app.core.ndef

import java.io.ByteArrayOutputStream

/** Wird geworfen, wenn Bytes keine gültige NDEF-Nachricht sind. */
class NdefFormatException(message: String) : Exception(message)

/**
 * Parser und Encoder für NDEF-Nachrichten (NFC Forum NDEF Technical Specification 1.0).
 *
 * - Unterstützt Short Records (SR), ID-Feld (IL) und Chunked Records (CF).
 * - Prüft alle Längen gegen die tatsächlich vorhandenen Bytes, damit fehlerhafte oder
 *   manipulierte Tag-Daten weder Abstürze noch riesige Speicherzuweisungen auslösen.
 */
object NdefCodec {

    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_CF = 0x20
    private const val FLAG_SR = 0x10
    private const val FLAG_IL = 0x08

    /** Parst eine NDEF-Nachricht. Wirft [NdefFormatException] bei ungültigen Daten. */
    fun parse(data: ByteArray): List<NdefRecordData> {
        if (data.isEmpty()) throw NdefFormatException("Keine NDEF-Daten vorhanden")
        val records = ArrayList<NdefRecordData>()
        var pos = 0
        var first = true

        // Zustand für Chunked Records
        var chunkTnf = 0
        var chunkType = ByteArray(0)
        var chunkId = ByteArray(0)
        var chunkPayload: ByteArrayOutputStream? = null

        while (pos < data.size) {
            val header = data[pos++].toInt() and 0xFF
            val mb = header and FLAG_MB != 0
            val me = header and FLAG_ME != 0
            val cf = header and FLAG_CF != 0
            val sr = header and FLAG_SR != 0
            val il = header and FLAG_IL != 0
            val tnf = header and 0x07

            if (first && !mb) throw NdefFormatException("Erster Record ohne Message-Begin-Flag")
            if (!first && mb) throw NdefFormatException("Unerwartetes Message-Begin-Flag mitten in der Nachricht")

            if (pos >= data.size) throw NdefFormatException("Record-Header abgeschnitten")
            val typeLength = data[pos++].toInt() and 0xFF

            val payloadLength: Long
            if (sr) {
                if (pos >= data.size) throw NdefFormatException("Payload-Länge fehlt")
                payloadLength = (data[pos++].toInt() and 0xFF).toLong()
            } else {
                if (pos + 4 > data.size) throw NdefFormatException("Payload-Länge abgeschnitten")
                payloadLength = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
                pos += 4
            }

            var idLength = 0
            if (il) {
                if (pos >= data.size) throw NdefFormatException("ID-Länge fehlt")
                idLength = data[pos++].toInt() and 0xFF
            }

            val remaining = (data.size - pos).toLong()
            if (typeLength.toLong() + idLength.toLong() + payloadLength > remaining) {
                throw NdefFormatException("Record-Längen überschreiten die Datenmenge")
            }

            val type = data.copyOfRange(pos, pos + typeLength); pos += typeLength
            val id = data.copyOfRange(pos, pos + idLength); pos += idLength
            val payload = data.copyOfRange(pos, pos + payloadLength.toInt()); pos += payloadLength.toInt()

            val active = chunkPayload
            if (active != null) {
                // Wir befinden uns mitten in einem Chunked Record.
                if (tnf != NdefRecordData.TNF_UNCHANGED) throw NdefFormatException("Chunk ohne TNF 'Unchanged'")
                if (typeLength != 0 || idLength != 0) throw NdefFormatException("Chunk mit Typ- oder ID-Feld")
                active.write(payload)
                if (!cf) {
                    records.add(NdefRecordData(chunkTnf, chunkType, chunkId, active.toByteArray()))
                    chunkPayload = null
                }
            } else {
                if (tnf == NdefRecordData.TNF_UNCHANGED) throw NdefFormatException("Unerwarteter 'Unchanged'-Record")
                if (cf) {
                    chunkTnf = tnf
                    chunkType = type
                    chunkId = id
                    chunkPayload = ByteArrayOutputStream().also { it.write(payload) }
                } else {
                    records.add(NdefRecordData(tnf, type, id, payload))
                }
            }

            first = false
            if (me) break
        }

        if (chunkPayload != null) throw NdefFormatException("Unvollständiger Chunked Record")
        if (records.isEmpty()) throw NdefFormatException("Keine Records gefunden")
        return records
    }

    /** Wie [parse], liefert bei ungültigen Daten aber null. */
    fun tryParse(data: ByteArray): List<NdefRecordData>? =
        try {
            parse(data)
        } catch (e: NdefFormatException) {
            null
        }

    /** Serialisiert Records zu einer NDEF-Nachricht. */
    fun encode(records: List<NdefRecordData>): ByteArray {
        require(records.isNotEmpty()) { "Eine NDEF-Nachricht braucht mindestens einen Record" }
        val out = ByteArrayOutputStream()
        for ((index, r) in records.withIndex()) {
            require(r.type.size <= 255) { "Typfeld zu lang" }
            require(r.id.size <= 255) { "ID-Feld zu lang" }
            val sr = r.payload.size <= 255
            val il = r.id.isNotEmpty()
            var header = r.tnf and 0x07
            if (index == 0) header = header or FLAG_MB
            if (index == records.lastIndex) header = header or FLAG_ME
            if (sr) header = header or FLAG_SR
            if (il) header = header or FLAG_IL
            out.write(header)
            out.write(r.type.size)
            if (sr) {
                out.write(r.payload.size)
            } else {
                val n = r.payload.size
                out.write((n ushr 24) and 0xFF)
                out.write((n ushr 16) and 0xFF)
                out.write((n ushr 8) and 0xFF)
                out.write(n and 0xFF)
            }
            if (il) out.write(r.id.size)
            out.write(r.type)
            out.write(r.id)
            out.write(r.payload)
        }
        return out.toByteArray()
    }

    /** True, wenn die Nachricht nur aus einem leeren Record besteht (typisch für formatierte, leere Tags). */
    fun isEffectivelyEmpty(records: List<NdefRecordData>): Boolean =
        records.isEmpty() || (records.size == 1 && records[0].isEmpty)
}
