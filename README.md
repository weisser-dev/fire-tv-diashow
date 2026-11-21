# Fire TV Diashow

Eine leichte Diashow für Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen – auch im Hochformat, ohne Fotos vorher drehen zu müssen.

## Funktionen

- **Dateien:** Bilder über den Dateiauswahldialog hinzufügen und einzeln aus der Vorschau löschen.
- **Handy-Upload:** QR-Code im selben WLAN öffnen, Bilder auswählen und in Alben auf dem Fernseher sortieren. Uploads bleiben lokal im privaten App-Speicher.
- **Diashow:** Zufallswiedergabe, Bilddauer, Vor-/Zurück, Einpassen/Füllen/Strecken und fünf Übergänge: langsame Überblendung, weiche Überblendung, direkt, Gleiten und Zoom.
- **Fernbedienung:** Mittlere Taste pausiert; das Kontextmenü verwaltet Bilder, Dauer, Übergang, Bilddarstellung, Alben und Schlaf-Timer. „Jetzt schlafen“ schaltet die Fotos aus; OK oder Zurück weckt die App wieder auf. Ein Wochenplan startet die Wiedergabe zum Beispiel Mo–Fr von 16:00 bis 21:00 Uhr und zeigt außerhalb dieser Zeit den Schlafbildschirm.
- **Uhr und Wetter:** Im Schlafmodus optional Uhr, Datum und aktuelle Wetterdaten einblenden. Wetter-Ort über Land (ISO-Code) und Postleitzahl angeben, etwa `DE` und `10115`. Die Wetteranzeige nutzt öffentliche Daten von Open-Meteo. Zeitplan und Schlaf-Timer steuern den App-Bildschirm, sie wecken den Fire TV nicht aus einem ausgeschalteten oder tief schlafenden Systemzustand.
- **Ausrichtung:** Die App, Menüs und Fernbedienungsnavigation lassen sich gemeinsam um 0°, 90°, 180° oder 270° drehen.
- **Dauerbetrieb:** Bildschirm während der Wiedergabe aktiv halten und App optional nach dem Fire-TV-Neustart öffnen.
- **Updates:** Die App prüft GitHub Releases und bietet die aktuelle APK direkt zum Laden an.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5), inklusive klassischer APK-Signatur.
- **Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/fire-tv-diashow/) mit Bildformat, Übergängen, Schlafzustand, Uhr/Datum/Wetter und Fernbedienungs-Kontextmenü. Es werden keine Dateien über die Demo hochgeladen.

## Handy-Upload und lokale Alben

Wähle **Handy-Upload**, scanne den QR-Code und öffne die angezeigte Seite auf einem Smartphone im selben WLAN. Dort kannst du einen Albumname und mehrere Bilder angeben. Bilder, die größer als 1920 × 1920 Pixel oder 8 MB sind, werden im Browser proportional verkleinert und als JPEG komprimiert, bevor sie übertragen werden. In der App lassen sich Upload-Alben filtern und einzelne Bilder löschen.

Der Upload-Server läuft nur, solange sein QR-Dialog offen ist. Der zufällige Link gilt für diese Sitzung; Uploads sind auf lokale Netzwerkadressen beschränkt und pro Datei auf 50 MB begrenzt. Entwickleroptionen sind nicht erforderlich. Ein optionaler vierstelliger PIN ist für den Handy-Upload vorgesehen.

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
