# Photo Carousel

Eine Android-App für eine dauerhaft laufende Fotoshow auf Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen, ohne Dateien vorab passend drehen zu müssen.

## Funktionen

- **Lokale Bilder:** Mehrere Bilder aus dem Dateiauswahldialog wählen; Auswahl und Leseberechtigungen bleiben auf dem Gerät gespeichert.
- **Google Fotos:** Der Picker-Zugriff ist vorbereitet, derzeit aber deaktiviert. Google erlaubt den benötigten Photos-Picker-Scope nicht im OAuth-Gerätecode-Flow für Fernseher. Ambient ist ebenfalls deaktiviert.
- **Diashow:** Shuffle, Bilddauer von 3 bis 60 Sekunden, nächste/zurück-Steuerung, weiche Übergänge und optionaler langsamer Schwenk mit Zoom.
- **TV-Betrieb:** Bildschirm bleibt während der Wiedergabe aktiv; Vollbilddarstellung und Steuerung über Fernbedienung.
- **Displayausrichtung:** Beim ersten Start Quer- oder Hochformat wählen. Die gesamte Oberfläche einschließlich Einstellungen und QR-Auswahl wird gedreht; die Richtungstasten der Fernbedienung werden passend dazu umgelegt.
- **App-Symbol:** Eigenes Foto-Rahmen-Symbol im Launcher.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5); Releases enthalten zusätzlich die klassische APK-Signatur für ältere Android-Versionen.
- **Updates in der App:** Beim Start wird GitHub nach einem neueren stabilen Release gefragt. Die App kann dessen APK herunterladen, Paket und Versionsnummer prüfen und den Android-Installationsdialog öffnen.
- **Ausrichtung:** Die gesamte App auf 0°, 90°, 180° oder 270° drehen. Menü, Bildanzeige und Fernbedienungsrichtungen folgen derselben Ausrichtung.
- **Browser-Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/google-photos-carousel/) mit Landscape/Portrait, Übergängen, Dauer und Bildfüllung.

## Google Fotos einrichten

Die App enthält die **Google Photos Picker API**-Anbindung, startet sie aktuell jedoch nicht: Google listet den benötigten `photospicker.mediaitems.readonly`-Scope nicht für den OAuth-Gerätecode-Flow von TVs und Geräten mit eingeschränkter Eingabe ([zulässige Gerätescopes](https://developers.google.com/identity/protocols/oauth2/limited-input-device#allowed-scopes), [Picker-Scope](https://developers.google.com/photos/overview/authorization)). Ein Versuch scheitert deshalb bereits beim Anfordern des QR-/Gerätecodes. Die App zeigt stattdessen einen erklärenden Hinweis; lokale und bereits zwischengespeicherte Fotos lassen sich weiter verwenden. Eine künftige Google-Fotos-Verbindung braucht einen anderen unterstützten OAuth-Weg.

Die Ambient-Anbindung ist derzeit deaktiviert und wird von der App nicht aufgerufen. Sie kann später wieder aktiviert werden, falls Google das Projekt ins [Google Photos Partner Program](https://developers.google.com/photos/partner-program/overview) aufnimmt.

Für Google Fotos sind diese Schritte in Google Cloud erforderlich:

1. Der Picker benötigt `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`; Google unterstützt diesen Scope derzeit nicht im OAuth-Gerätecode-Flow für TVs. Das Aktivieren der API und ein TV-OAuth-Client allein beheben diese Einschränkung nicht.
2. Der Picker kann erst wieder angeboten werden, wenn ein anderer von Google unterstützter OAuth-Weg für Fire TV eingerichtet ist.
3. Ambient bleibt bis zur Google-Freigabe im Partnerprogramm deaktiviert. Die aktuelle App startet für Google Fotos weder OAuth noch Ambient-Aufrufe.

OAuth-Client-Secrets gehören nicht in Git oder öffentliche Workflow-Logs. Die Partnerprogramm-Freigabe für Ambient ist davon unabhängig; Ambient wird aktuell nicht verwendet.

Lokal ausgewählte Bilder und bereits übernommene Picker-Dateien werden im privaten App-Speicher wiederverwendet. Eine neue Picker-Auswahl ist momentan deaktiviert.

## Bauen und installieren

Benötigt JDK 17 und Android SDK Platform 36. Auf macOS liegt das SDK üblicherweise unter `~/Library/Android/sdk`.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ das Projekt in Android Studio öffnen und die `app`-Konfiguration starten. Mindestversion ist Android 5.1 (API 22, Fire OS 5). Fire-TV-Geräte können lokale Bilder verwenden.

## CI, APK und GitHub Pages

- Pushes auf `main` und Pull Requests erstellen ein Debug-APK-Artefakt in GitHub Actions.
- Ein Versionstag wie `v0.2.0` erstellt ein signiertes Release-APK und veröffentlicht es als öffentliches GitHub Release.
- Die [Download- und Demo-Seite](https://weisser-dev.github.io/google-photos-carousel/) verweist auf die neueste APK. Downloader-Adresse: `https://github.com/weisser-dev/google-photos-carousel/releases/latest/download/app-release.apk`.
- Die installierte App prüft beim Start automatisch auf neuere stabile GitHub-Releases. Bei einem Treffer kann man die APK direkt laden und den Systemdialog zur Installation öffnen. Android/Fire OS verlangt weiterhin eine Bestätigung; ab Android 8 muss gegebenenfalls die Installation unbekannter Apps für Photo Carousel erlaubt werden.
- Android Signatur-Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` und `ANDROID_STORE_PASSWORD`. Bei Folgeversionen immer denselben Keystore benutzen.

## Private lokale Dateien

APK-Dateien, dekompilierte Drittanbieterquellen, Build-Ausgaben, Signaturschlüssel und OAuth-JSON-Dateien sind durch `.gitignore` ausgeschlossen. Die fünf Demo-Fotos unter `docs/demo/` sind eigens für die öffentliche Demo generierte Landschaftsbilder; private Testbilder bleiben außerhalb dieses Repositories.

## Lizenz

MIT. Siehe [LICENSE](LICENSE).
