# Photo Carousel

Eine Android-App für eine dauerhaft laufende Fotoshow auf Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen, ohne Dateien vorab passend drehen zu müssen.

## Funktionen

- **Lokale Bilder:** Mehrere Bilder aus dem Dateiauswahldialog wählen; Auswahl und Leseberechtigungen bleiben auf dem Gerät gespeichert.
- **Google Fotos Picker:** Fotos in Google Fotos am Handy auswählen; der Fernseher übernimmt die Auswahl und lädt Bilder für eine zuverlässige, offlinefähige Wiedergabe lokal in seinen privaten Speicher. Videos werden übersprungen.
- **Google Fotos Ambient:** Der QR-/Gerätecode-Flow und die Album-Verknüpfung bleiben als zweite Option erhalten. Ambient erfordert die Aufnahme des Cloud-Projekts ins Google Photos Partner Program; solange Google den Zugriff nicht freigibt, funktioniert dieser Weg nicht.
- **Diashow:** Shuffle, Bilddauer von 3 bis 60 Sekunden, nächste/zurück-Steuerung, weiche Übergänge und optionaler langsamer Schwenk mit Zoom.
- **TV-Betrieb:** Bildschirm bleibt während der Wiedergabe aktiv; Vollbilddarstellung und Steuerung über Fernbedienung.
- **Displayausrichtung:** Beim ersten Start Quer- oder Hochformat wählen. Die gesamte Oberfläche einschließlich Einstellungen und QR-Auswahl wird gedreht; die Richtungstasten der Fernbedienung werden passend dazu umgelegt.
- **App-Symbol:** Eigenes Foto-Rahmen-Symbol im Launcher.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5); Releases enthalten zusätzlich die klassische APK-Signatur für ältere Android-Versionen.
- **Updates in der App:** Beim Start wird GitHub nach einem neueren stabilen Release gefragt. Die App kann dessen APK herunterladen, Paket und Versionsnummer prüfen und den Android-Installationsdialog öffnen.
- **Ausrichtung:** Bilder auf 0°, 90°, 180° oder 270° drehen, ohne Originaldateien zu verändern. So lassen sich auch quer ausgebende Fire-TV-Geräte an einen hochkant montierten Bildschirm anpassen.
- **Browser-Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/google-photos-carousel/) mit Landscape/Portrait, Übergängen, Dauer und Bildfüllung.

## Google Fotos einrichten

Die App verwendet vorrangig die **Google Photos Picker API**. Der Fernseher zeigt einen QR-Code, du bestätigst den Zugriff und wählst auf dem Handy die gewünschten Fotos aus. Nach dem Abruf speichert die App die Bilddateien lokal auf dem TV, damit die Diashow nicht fortlaufend eine Internetverbindung braucht. Der Picker gewährt Zugriff nur auf die ausgewählten Elemente; er bietet keine dauerhafte Synchronisierung eines Albums.

Die **Ambient API** bleibt als alternative Album-Verknüpfung enthalten. Google gibt Ambient nur für Projekte frei, die ins Google Photos Partner Program aufgenommen wurden. OAuth-Anmeldung und aktivierte API reichen dafür nicht. Details und Antrag: [Google Photos Partner Program](https://developers.google.com/photos/partner-program/overview).

Für Google Fotos sind diese Schritte in Google Cloud erforderlich:

1. Die **Google Photos Picker API** im Projekt aktivieren.
2. Einen OAuth-Client des Typs **TVs and Limited Input devices** anlegen. Ein Android-Client ist für den QR-/Gerätecode-Flow nicht geeignet.
3. Den OAuth-Zustimmungsbildschirm für den Bereich `https://www.googleapis.com/auth/photospicker.mediaitems.readonly` konfigurieren und die OAuth-Verifizierung beantragen, wenn die Warnung für Nutzer entfernt werden soll.
4. Für lokale Builds `AMBIENT_CLIENT_ID` und `AMBIENT_CLIENT_SECRET` als Umgebungsvariablen oder Gradle-Properties `ambientClientId` / `ambientClientSecret` bereitstellen. Diese vorhandenen Variablennamen gelten für beide Google-Photos-Flows.
5. Für Release-Builds dieselben Werte als GitHub Actions Secrets `AMBIENT_CLIENT_ID` und `AMBIENT_CLIENT_SECRET` speichern. Signatur-Secrets sind zusätzlich nötig (siehe unten).

Für eine öffentliche Nutzung muss die OAuth-App die Google-Prüfung bestehen. Ein Client-Secret in einer Android-App lässt sich technisch aus dem APK extrahieren; es ist daher kein Geheimnis gegenüber App-Nutzern. Das Secret gehört dennoch nicht in Git oder öffentliche Workflow-Logs. Die Partnerprogramm-Freigabe für Ambient ist davon unabhängig.

Der Picker erzeugt für jede Fotoauswahl eine kurzlebige Auswahl-Sitzung. Nach dem Abruf löscht die App diese Sitzung wieder. Eine geänderte Auswahl startest du erneut über „Google Fotos“. Ambient verknüpft dagegen das Gerät mit ausgewählten Medienquellen und fragt danach die freigegebenen Medien ab; dieser Weg setzt die Partnerfreigabe voraus.

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
