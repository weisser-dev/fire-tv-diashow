# Photo Carousel

Eine Android-App für eine dauerhaft laufende Fotoshow auf Android TV und Fire TV. Sie ist für Fernseher gedacht, auf denen Familienbilder stundenlang sichtbar bleiben sollen, ohne Dateien vorab passend drehen zu müssen.

## Funktionen

- **Lokale Bilder:** Mehrere Bilder aus dem Dateiauswahldialog wählen; Auswahl und Leseberechtigungen bleiben auf dem Gerät gespeichert.
- **Google Fotos:** Anmeldung im Silk-/Systembrowser des Fire TV, danach Fotos im Google-Photos-Picker auswählen. Die Bilder werden heruntergeladen und im privaten App-Speicher zwischengespeichert.
- **Diashow:** Shuffle, Bilddauer von 3 bis 60 Sekunden, nächste/zurück-Steuerung, weiche Übergänge und optionaler langsamer Schwenk mit Zoom.
- **TV-Betrieb:** Bildschirm bleibt während der Wiedergabe aktiv; Vollbilddarstellung und Steuerung über Fernbedienung.
- **Displayausrichtung:** Beim ersten Start Quer- oder Hochformat wählen. Die gesamte Oberfläche einschließlich Einstellungen und QR-Auswahl wird gedreht; die Richtungstasten der Fernbedienung werden passend dazu umgelegt.
- **App-Symbol:** Eigenes Foto-Rahmen-Symbol im Launcher.
- **Ältere Fire TVs:** Mindestversion Android API 22 (Fire OS 5); Releases enthalten zusätzlich die klassische APK-Signatur für ältere Android-Versionen.
- **Updates in der App:** Beim Start wird GitHub nach einem neueren stabilen Release gefragt. Die App kann dessen APK herunterladen, Paket und Versionsnummer prüfen und den Android-Installationsdialog öffnen.
- **Ausrichtung:** Die gesamte App auf 0°, 90°, 180° oder 270° drehen. Menü, Bildanzeige und Fernbedienungsrichtungen folgen derselben Ausrichtung.
- **Browser-Demo:** [Interaktive Vorschau](https://weisser-dev.github.io/google-photos-carousel/) mit Landscape/Portrait, Übergängen, Dauer und Bildfüllung.

## Google Fotos

Der TV-Gerätecode-Login kann den Photos-Picker-Scope nicht anfordern. Deshalb öffnet Photo Carousel für die Google-Anmeldung den normalen Browser auf dem Fire TV. Die App stellt dafür eine kurzlebige Anmeldeseite nur auf `127.0.0.1` bereit. Google Identity Services liefert einen kurzlebigen Zugriffstoken an diese lokale Seite; die App speichert den Token nicht dauerhaft und fordert anschließend ausschließlich `photospicker.mediaitems.readonly` an. Der Photos-Picker zeigt einen QR-Code, den du mit dem Handy scannst, um die gewünschten Fotos auszuwählen. Danach lädt die App die ausgewählten Bilder in ihren privaten Speicher.

Für den Browser-Login wird ein OAuth-Webclient mit dem JavaScript-Ursprung `http://127.0.0.1:8765` verwendet. Das Client-ID-Kennzeichen ist öffentlich und im Build enthalten; ein OAuth-Client-Secret wird weder in der App noch im Repository verwendet. Google kann bei nicht verifizierten Apps einen Warnhinweis zeigen und den Login zunächst auf eine begrenzte Zahl von Testkonten beschränken. Google muss die App verifizieren, bevor der Picker allgemein freigegeben werden kann.

Die **Ambient API** bleibt unabhängig davon deaktiviert. Sie benötigt eine separate Aufnahme des Cloud-Projekts ins [Google Photos Partner Program](https://developers.google.com/photos/partner-program/overview); ein OAuth-Webclient für den Picker erteilt diese Freigabe nicht.

Picker-Bilder werden nach dem Download im privaten App-Speicher zwischengespeichert und stehen auch ohne aktive Google-Verbindung weiter zur Verfügung. Zum Aktualisieren der Auswahl muss erneut eine Picker-Sitzung gestartet werden.

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
