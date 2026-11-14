# NFC-Manager (Android)

Native Kotlin-App (Jetpack Compose, Material 3, Room) zum Auslesen, dauerhaften Speichern und Bereitstellen **eigener, ungeschützter** NFC-Tags.
Alles läuft offline; kein Konto, keine Werbung, keine Käufe, kein Tracking, keine Internetberechtigung.

> Die App dient ausschließlich der Verwaltung eigener, ungeschützter NFC-Tags. Sie umgeht keinen Zugriffsschutz, keine
> Verschlüsselung und keine Authentifizierung und kopiert keine Zutritts- oder Zahlungskarten.

## APK bauen

**Android Studio:** Ordner öffnen → Gradle-Sync → *Build → Build APK(s)* (Android Studio liefert Gradle und SDK mit).
Die APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions:** Projekt in ein GitHub-Repo pushen. Der Workflow `.github/workflows/android.yml` führt die Unit-Tests aus und
baut die Debug-APK; Download unter *Actions → letzter Lauf → Artifacts → NfcManager-debug-apk*.

**Kommandozeile:** JDK 17 + Android SDK (Plattform 35), dann `gradle :app:assembleDebug` (Gradle ≥ 8.9).

## Funktionen

* **Scannen:** NFC-A/B/F/V, ISO-DEP, NTAG21x, MIFARE Ultralight/Classic (nur Kennung), Type 1–5. Zeigt UID, Technologien, Chip-Typ
  (u. a. per GET_VERSION), Speichergröße, Capability Container, Sperrbits, NTAG-Konfiguration, Speicherabbild und den NDEF-Inhalt
  (URL, Text, Smart Poster, Kontakt/vCard, WLAN, Bluetooth, Android-App-Record, MIME, extern).
* **Gespeicherte Tags:** Room-Datenbank, unbegrenzt viele Einträge, Name/Symbol/Farbe/Notiz, Suche, Sortierung, Bearbeiten des Inhalts
  per Formular, Löschen, Export/Import als JSON (Dateiauswahl des Systems), Schreiben des Inhalts auf einen eigenen beschreibbaren Tag.
* **Emulation:** HCE (`HostApduService`) stellt den gewählten NDEF-Inhalt als **NFC-Forum-Type-4-Tag** (AID `D2760000850101`, Mapping 2.0)
  bereit – auch bei geschlossener App. Mit Protokoll der Lesegeräte-Zugriffe und Lesezähler.
* Start-Check für NFC/HCE, Hinweise für Samsung/Android-Versionen, Dark Mode, Systemfarben.

## Grenzen der Emulation (technisch bedingt)

* Android erzeugt bei HCE eine zufällige UID; sie kann nicht gesetzt werden.
* HCE kennt nur ISO-DEP und NFC-F. NTAG/Ultralight (NFC-A ohne ISO-DEP), MIFARE Classic und NFC-V lassen sich daher nicht auf Funkebene
  nachbilden – emuliert wird der **NDEF-Inhalt** als Type-4-Tag. Die App zeigt das pro Tag konkret an.
* Der emulierte Tag ist schreibgeschützt; Bildschirm muss an sein, NFC aktiv. Im Tab „Scannen“ pausiert Android die Emulation (Lese-Modus).
* Lesegeräte, die nur Type-4-Mapping 1.0 sprechen, erkennen den Tag nicht.
* Geschützte Bereiche (MIFARE-Schlüssel, ISO-DEP-Anwendungen) werden nicht gelesen.

## Aufbau

```
app/src/main/java/de/nfcmanager/app/
  core/   reine Kotlin-Logik (JVM-getestet): NDEF-Codec/-Decoder, vCard, WLAN, Type-4-Emulator, Tag-Erkennung, JSON-Export
  nfc/    Android-NFC: TagReader, TagWriter, HCE-Dienst, EmulationController/-Store/-Log, Fähigkeitsprüfung
  data/   Room (Entity, DAO, Datenbank), Repository, Einstellungen
  ui/     Compose-Oberfläche (Scannen, Bibliothek, Emulation), ViewModels
app/src/test/  90 Unit-Tests (NDEF, Emulator-Protokoll, Tag-Erkennung, Export/Import)
```

## Prüfstand

Die reine Kotlin-Logik und die Tests wurden mit Kotlin 2.0.21 kompiliert und ausgeführt; der NFC-Code gegen die Android-35-API
kompiliert. Das Gradle-Projekt selbst und die Compose-Oberfläche konnten in der Entwicklungsumgebung nicht gebaut werden
(Google-Maven nicht erreichbar) – der erste Lauf der GitHub-Action bzw. von Android Studio ist die maßgebliche Prüfung.
Die Emulation wurde nicht an echter Hardware getestet.
