# Fire TV Diashow

Eine leichte Diashow für Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen – auch im Hochformat, ohne Fotos vorher drehen zu müssen.

## Funktionen

- **Dateien:** Bilder über den Dateiauswahldialog hinzufügen und einzeln aus der Vorschau löschen.
- **Handy-Upload:** QR-Code im selben WLAN öffnen, Bilder auswählen und in Alben auf dem Fernseher sortieren. Aufgaben und Notizen lassen sich ebenfalls vom Handy pflegen; Kalendertermine können nach Titel ein- oder ausgeblendet werden. Kalender-Einträge mit `TODO:`, `ToDo:`, `to do:` oder `to-do:` erscheinen als datierte Aufgaben. Uploads bleiben lokal im privaten App-Speicher.
- **Diashow:** Shuffle wählt zufällig aus den am wenigsten gezeigten Bildern. Die gespeicherten Wiedergabezahlen bleiben lokal über Neustarts hinweg erhalten. Ungesehene Bilder werden stärker gewichtet; die Auswahl darf innerhalb eines Unterschieds von bis zu drei Ansichten variieren. Die Bilddauer beträgt mindestens fünf Sekunden. Das aktuelle und das nächste Bild werden gepuffert; bei einem Decode-Fehler bleibt das aktuelle Bild stehen und das fehlerhafte Bild wird übersprungen. Das nächste Foto liegt während des Übergangs bereits unter dem aktuellen; nur das aktuelle blendet aus. Die Ken-Burns-Bewegung des neuen Bildes beginnt nach dem Übergang. Die Bilddarstellung bietet Einpassen, Füllen, Strecken oder Ken-Burns-Zoom/Schwenk.
- **Fernbedienung:** Mittlere Taste pausiert; das Kontextmenü verwaltet Bilder, Dauer, Übergang, Bilddarstellung, Alben und Schlaf-Timer. „Jetzt schlafen“ schaltet die Fotos aus; OK oder Zurück weckt die App wieder auf. Ein Wochenplan startet die Wiedergabe zum Beispiel Mo–Fr von 16:00 bis 21:00 Uhr und zeigt außerhalb dieser Zeit den Schlafbildschirm.
- **Uhr, Datum und Wetter:** Optional über dem Foto oder im Schlafmodus anzeigen. Im Schlafmodus stehen Uhrzeit, Datum und Wetter gemeinsam mittig; das Wetter erscheint mit einem Vektorsymbol direkt unter dem Datum. Liquid-Glass-Optik ergänzt transparente Verläufe, Lichtkante und Schatten. Auf älteren Fire TVs bleibt der Effekt leichtgewichtig und verzichtet auf teuren Hintergrund-Blur. Wetter-Ort über Land (ISO-Code) und Postleitzahl angeben, etwa `DE` und `10115`. Die Wetteranzeige nutzt öffentliche Daten von Open-Meteo. Zeitplan und Schlaf-Timer steuern den App-Bildschirm, sie wecken den Fire TV nicht aus einem ausgeschalteten oder tief schlafenden Systemzustand.
- **Ausrichtung:** Die App, Menüs und Fernbedienungsnavigation lassen sich gemeinsam um 0°, 90°, 180° oder 270° drehen.
- **Dauerbetrieb:** Bildschirm während der Wiedergabe aktiv halten und App optional nach dem Fire-TV-Neustart öffnen.
- **Updates:** Die App prüft GitHub Releases und bietet die aktuelle APK direkt zum Laden an.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5), inklusive klassischer APK-Signatur.
- **Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/fire-tv-diashow/) als 9:16-TV-Mockup mit der aktuellen Wiedergabeoberfläche, echten Fire-TV-Aufnahmen ohne Personen, Fernbedienungsnavigation, Bildformat, Ken-Burns-Bewegung, Vorladen, bildbasiertem Ausblend-Übergang ohne Schwarzblende, intelligentem Shuffle, Schlafzustand, Uhr/Datum/Wetter, Kalender, Aufgaben, Notizen und QR-Telefonsteuerung. Die Demo zeigt „Optimized for Fire TV“ und lädt keine Dateien hoch.

## Rahmen umziehen und Fotos ausblenden

Scanne den QR-Code des bisherigen Rahmens und wähle **Sicherung herunterladen**. Öffne auf dem Handy anschließend die QR-Seite des zweiten Rahmens, wähle die ZIP-Datei und bestätige **Rahmen wiederherstellen**. Sie ersetzt die Sammlung auf dem Zielgerät. Während des Imports benötigt der Stick zusätzlich Platz für die entpackten Bilder.

Die Sicherung enthält Originaldateien, Upload-Alben, ausgeblendete Bilder, Shuffle-Verlauf, Kalenderadresse und Filter, Aufgaben, Notizen und Anzeigeeinstellungen. Über den Android-Dateidialog ausgewählte Bilder werden auf dem Zielgerät in ein eigenes Album **Dateien** kopiert. Die ZIP-Datei enthält persönliche Daten im Klartext. Eine eingerichtete PIN gilt auf dem jeweiligen Gerät.

Drücke während der Wiedergabe die mittlere Taste und wähle **Dieses Bild ausblenden**. Das Bild bleibt im Album. Unter **Bilder verwalten** kannst du ausgeblendete Bilder markieren und **Auswahl wieder einblenden** wählen.

## Handy-Upload und lokale Alben

Wähle **Handy-Upload**, scanne den QR-Code und öffne die angezeigte Seite auf einem Smartphone im selben WLAN. Dort kannst du einen Albumname und mehrere Bilder angeben. Nur Bilder mit einer Kante über 1920 Pixel werden im Browser proportional verkleinert und als JPEG komprimiert. Bereits passende Fotos bleiben unverändert, damit sie nicht unnötig an Qualität verlieren. In der App lassen sich Upload-Alben filtern und einzelne Bilder löschen.

Der Upload-Server ist über den QR-Code erreichbar, solange die App läuft. Der zufällige Link gilt für diese Sitzung; Uploads sind auf lokale Netzwerkadressen beschränkt und pro Datei auf 50 MB begrenzt. Entwickleroptionen sind nicht erforderlich. Ein optionaler vierstelliger PIN ist für den Handy-Upload vorgesehen.

## Bauen und installieren

Benötigt JDK 17 und Android SDK Platform 36.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Mindestversion ist Android 5.1 (API 22, Fire OS 5).

## CI, APK und GitHub Pages

- Pushes auf `main` erstellen ein Debug-APK-Artefakt in GitHub Actions.
- Ein Versionstag wie `v0.11.0` erstellt ein signiertes Release-APK und veröffentlicht es öffentlich.
- [Download und Demo](https://weisser-dev.github.io/fire-tv-diashow/); Downloader-Adresse: `https://github.com/weisser-dev/fire-tv-diashow/releases/latest/download/app-release.apk`.
- Releases verwenden dieselben vier Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` und `ANDROID_STORE_PASSWORD`.

## Private Dateien

APK-Dateien, dekompilierte Drittanbieterquellen, Build-Ausgaben und Signaturschlüssel sind durch `.gitignore` ausgeschlossen. Die Demo-Fotos in `docs/demo/` sind eigens generierte Landschaftsbilder; private Testbilder bleiben außerhalb dieses Repositories.

## Lizenz

MIT. Siehe [LICENSE](LICENSE).
