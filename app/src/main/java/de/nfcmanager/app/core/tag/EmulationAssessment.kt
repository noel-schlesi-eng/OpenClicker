package de.nfcmanager.app.core.tag

import de.nfcmanager.app.core.hce.Type4Tag

/** Ergebnis der Bewertung, ob und wie ein gespeicherter Tag emuliert werden kann. */
data class EmulationAssessment(
    val possible: Boolean,
    /** Kurzes Etikett für Chips und Listen. */
    val headline: String,
    /** Konkrete technische Begründung bzw. Hinweise für Anwender. */
    val explanation: String,
)

/**
 * Bewertet nach den Möglichkeiten der dokumentierten Android-Schnittstellen (HostApduService / ISO-DEP),
 * was sich aus einem gespeicherten Tag nachbilden lässt. Es wird nichts vorgetäuscht:
 * emuliert wird ausschließlich der NDEF-Inhalt als NFC-Forum-Type-4-Tag.
 */
object EmulationAssessor {

    fun assess(family: TagFamily, ndefMessage: ByteArray?): EmulationAssessment {
        if (ndefMessage != null && ndefMessage.isNotEmpty()) {
            if (ndefMessage.size > Type4Tag.MAX_NDEF_MESSAGE_BYTES) {
                return EmulationAssessment(
                    possible = false,
                    headline = "Zu groß",
                    explanation = "Die NDEF-Nachricht hat ${ndefMessage.size} Bytes. Ein Type-4-Tag kann höchstens " +
                        "${Type4Tag.MAX_NDEF_MESSAGE_BYTES} Bytes bereitstellen.",
                )
            }
            return EmulationAssessment(
                possible = true,
                headline = "Als Type-4-Tag emulierbar",
                explanation = contentEmulationText(family),
            )
        }
        return EmulationAssessment(possible = false, headline = "Nicht emulierbar", explanation = noContentText(family))
    }

    private fun contentEmulationText(family: TagFamily): String {
        val tail = " Das Lesegerät erkennt den Inhalt wie bei einem normalen NFC-Tag; die UID ist jedoch zufällig " +
            "und unterscheidet sich vom Original."
        return when (family) {
            TagFamily.TYPE4 ->
                "Das Original ist bereits ein Type-4-Tag (ISO-DEP). Der NDEF-Inhalt wird 1:1 über " +
                    "Host Card Emulation als Type-4-Tag bereitgestellt.$tail"
            TagFamily.TYPE2 ->
                "NTAG und MIFARE Ultralight (Type 2) arbeiten direkt auf NFC-A-Ebene (ISO 14443-3) ohne ISO-DEP. " +
                    "Android-HCE kann nur ISO-DEP bedienen, deshalb wird der NDEF-Inhalt als Type-4-Tag bereitgestellt " +
                    "(gleicher Inhalt, anderer Chip-Typ).$tail"
            TagFamily.TYPE5 ->
                "ISO 15693 (NFC-V) kann Android nicht emulieren. Der NDEF-Inhalt wird stattdessen als " +
                    "Type-4-Tag bereitgestellt.$tail"
            TagFamily.TYPE3, TagFamily.NFC_F ->
                "FeliCa (NFC-F) wird von dieser App nicht emuliert. Der NDEF-Inhalt wird stattdessen als " +
                    "Type-4-Tag bereitgestellt.$tail"
            TagFamily.MIFARE_CLASSIC ->
                "MIFARE Classic nutzt ein eigenes, verschlüsseltes Funkprotokoll (Crypto-1), das Android-HCE nicht " +
                    "nachbilden kann. Nur der NDEF-Inhalt wird als Type-4-Tag bereitgestellt.$tail"
            else ->
                "Das Original-Funkprotokoll lässt sich per HCE nicht nachbilden. Der NDEF-Inhalt wird stattdessen " +
                    "als Type-4-Tag bereitgestellt.$tail"
        }
    }

    private fun noContentText(family: TagFamily): String = when (family) {
        TagFamily.MIFARE_CLASSIC ->
            "MIFARE Classic verwendet ein proprietäres, durch Schlüssel geschütztes Protokoll (Crypto-1) auf Ebene von " +
                "ISO 14443-3. Android-HCE unterstützt nur ISO-DEP (ISO 14443-4). Geschützte Sektoren werden bewusst " +
                "weder gelesen noch nachgebildet."
        TagFamily.TYPE2, TagFamily.NFC_A, TagFamily.TYPE1 ->
            "Dieser Tag enthält keinen NDEF-Inhalt. Rohspeicher von NFC-A-Tags (z. B. NTAG, Ultralight) liegt unterhalb " +
                "von ISO-DEP; dafür bietet Android keine Emulation. Ohne NDEF-Inhalt gibt es nichts, was als " +
                "Type-4-Tag bereitgestellt werden könnte."
        TagFamily.TYPE5 ->
            "ISO 15693 (NFC-V) kann Android nicht emulieren, und der Tag enthält keinen lesbaren NDEF-Inhalt."
        TagFamily.TYPE3, TagFamily.NFC_F ->
            "FeliCa (NFC-F) wird von dieser App nicht emuliert, und der Tag enthält keinen lesbaren NDEF-Inhalt. " +
                "Android bietet NFC-F-Emulation nur über HostNfcFService mit festen System-Codes."
        TagFamily.TYPE4, TagFamily.ISO_DEP ->
            "ISO-DEP-Anwendungen (z. B. DESFire, Zahlungs-, Ausweis- oder Zutrittskarten) sind kryptografisch " +
                "geschützt. Diese App liest und kopiert sie nicht; es gibt keine Umgehung von Zugriffsschutz."
        TagFamily.NFC_B ->
            "NFC-B (ISO 14443-3B) wird per HCE nur auf wenigen Geräten unterstützt, und ohne NDEF-Inhalt gibt es " +
                "nichts Öffentliches zum Nachbilden."
        TagFamily.UNKNOWN ->
            "Der Tag-Typ ist unbekannt und enthält keinen lesbaren NDEF-Inhalt."
    }

    /** Allgemeine Einschränkungen der Host Card Emulation, die für jeden emulierten Datensatz gelten. */
    val GENERAL_LIMITS: List<String> = listOf(
        "Die UID kann nicht gesetzt werden: Android erzeugt bei HCE eine zufällige UID (4 Byte, beginnt mit 08). " +
            "Systeme, die nur anhand der UID prüfen, erkennen die Emulation nicht.",
        "HCE bietet nur ISO-DEP (ISO 14443-4) und NFC-F. NFC-A-Tags ohne ISO-DEP (NTAG, Ultralight, MIFARE Classic) " +
            "und NFC-V lassen sich nicht auf Funkebene nachbilden; emuliert wird deshalb der NDEF-Inhalt als Type-4-Tag.",
        "Der emulierte Tag ist schreibgeschützt: Lesegeräte können ihn nicht überschreiben.",
        "Die Emulation läuft nur bei eingeschaltetem Bildschirm (auch gesperrt) und wenn NFC aktiviert ist. " +
            "Im Lese-Modus dieser App (Tab „Scannen“) ist sie vorübergehend pausiert.",
        "Geschützte Karten (Zahlung, Zutritt, Ausweise) werden weder gelesen noch kopiert.",
    )
}
