# Photo Carousel

Eine Android-App für eine dauerhaft laufende Fotoshow auf Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen, ohne Dateien vorab passend drehen zu müssen.

## Funktionen

- **Lokale Bilder:** Mehrere Bilder aus dem Dateiauswahldialog wählen; Auswahl und Leseberechtigungen bleiben auf dem Gerät gespeichert.
- **Google Fotos:** Verbindung über die Google Photos Ambient API. Der TV-Login zeigt einen QR-Code und Gerätecode; danach konfiguriert man die freigegebenen Alben in Google Fotos. Die App lädt die ausgewählten Bilder für eine stabile Wiedergabe lokal in ihren privaten Speicher.
- **Diashow:** Shuffle, Bilddauer von 3 bis 60 Sekunden, nächste/zurück-Steuerung, weiche Übergänge und optionaler langsamer Schwenk mit Zoom.
- **TV-Betrieb:** Bildschirm bleibt während der Wiedergabe aktiv; Vollbilddarstellung und Steuerung über Fernbedienung.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5); Releases enthalten zusätzlich die klassische APK-Signatur für ältere Android-Versionen.
- **Updates in der App:** Beim Start wird GitHub nach einem neueren stabilen Release gefragt. Die App kann dessen APK herunterladen, Paket und Versionsnummer prüfen und den Android-Installationsdialog öffnen.
- **Ausrichtung:** Bilder auf 0°, 90°, 180° oder 270° drehen, ohne Originaldateien zu verändern. So lassen sich auch quer ausgebende Fire-TV-Geräte an einen hochkant montierten Bildschirm anpassen.
- **Browser-Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/google-photos-carousel/) mit Landscape/Portrait, Übergängen, Dauer und Bildfüllung.

## Google Fotos einrichten

Die App verwendet die **Ambient API**, die für verbundene Bilderrahmen und TV-Geräte entwickelt wurde und die Auswahl von Medienquellen wie Alben unterstützt. Die frühere Picker-Integration ist entfernt.

Für Google Fotos sind diese Schritte in Google Cloud erforderlich:

1. Die **Google Photos Ambient API** im Projekt aktivieren.
2. Einen OAuth-Client des Typs **TVs and Limited Input devices** anlegen. Ein Android-Client ist für den QR-/Gerätecode-Flow nicht geeignet.
3. OAuth-Zustimmungsbildschirm und angeforderten Bereich `https://www.googleapis.com/auth/photosambient.mediaitems` konfigurieren.
4. Für lokale Builds `AMBIENT_CLIENT_ID` und `AMBIENT_CLIENT_SECRET` als Umgebungsvariablen oder Gradle-Properties `ambientClientId` / `ambientClientSecret` bereitstellen.
5. Für Release-Builds dieselben Werte als GitHub Actions Secrets `AMBIENT_CLIENT_ID` und `AMBIENT_CLIENT_SECRET` speichern. Signatur-Secrets sind zusätzlich nötig (siehe unten).

Die Ambient API ist in der aktuell geprüften Cloud Console noch nicht aktiviert und dort ist noch kein TV-OAuth-Client eingerichtet. Solange das fehlt, funktionieren lokale Bilder; Google Fotos meldet in der App klar die fehlende Konfiguration. Google verlangt eine Prüfung, bevor eine öffentliche Ambient-Anwendung breit gestartet werden kann. Ein Client-Secret in einer Android-App lässt sich technisch aus dem APK extrahieren; es ist daher kein Geheimnis gegenüber App-Nutzern. Das Secret gehört dennoch nicht in Git oder öffentliche Workflow-Logs.

Ambient verknüpft ein Gerät mit ausgewählten Medienquellen und erlaubt danach die Liste der freigegebenen Bilder abzurufen. Die App fragt die Gerätefreigabe ab und lädt die Medien während des Verbindungsablaufs. Für spätere Änderungen an der Albumauswahl lässt sich die Verbindung erneut starten.

## Bauen und installieren

Benötigt JDK 17 und Android SDK Platform 36. Auf macOS liegt das SDK üblicherweise unter `~/Library/Android/sdk`.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ das Projekt in Android Studio öffnen und die `app`-Konfiguration starten. Mindestversion ist Android 5.1 (API 22, Fire OS 5). Fire-TV-Geräte ohne Google Play-Dienste können lokale Bilder verwenden; der Ambient Geräte-Login läuft über Google OAuth.

## CI, APK und GitHub Pages

- Pushes auf `main` und Pull Requests erstellen ein Debug-APK-Artefakt in GitHub Actions.
- Ein Versionstag wie `v0.2.0` erstellt ein signiertes Release-APK und veröffentlicht es als öffentliches GitHub Release.
- Die [Download- und Demo-Seite](https://weisser-dev.github.io/google-photos-carousel/) verweist auf die neueste APK. Downloader-Adresse: `https://github.com/weisser-dev/google-photos-carousel/releases/latest/download/app-release.apk`.
- Die installierte App prüft beim Start automatisch auf neuere stabile GitHub-Releases. Bei einem Treffer kann man die APK direkt laden und den Systemdialog zur Installation öffnen. Android/Fire OS verlangt weiterhin eine Bestätigung; ab Android 8 muss gegebenenfalls die Installation unbekannter Apps für Photo Carousel erlaubt werden.
- Android Signatur-Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` und `ANDROID_STORE_PASSWORD`. Bei Folgeversionen immer denselben Keystore benutzen.
- Ambient OAuth Secrets: `AMBIENT_CLIENT_ID` und `AMBIENT_CLIENT_SECRET`.

## Private lokale Dateien

APK-Dateien, dekompilierte Drittanbieterquellen, Build-Ausgaben, Signaturschlüssel und OAuth-JSON-Dateien sind durch `.gitignore` ausgeschlossen. Die fünf Demo-Fotos unter `docs/demo/` sind eigens für die öffentliche Demo generierte Landschaftsbilder; private Testbilder bleiben außerhalb dieses Repositories.

## Lizenz

MIT. Siehe [LICENSE](LICENSE).
