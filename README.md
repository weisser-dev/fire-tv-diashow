# Photo Carousel

An Android slideshow for a television that needs to show personal photos for hours without the screen going dark or the playback getting stuck. It supports local image files and a privacy-limited Google Photos selection, with shuffle, smooth fades, adjustable timing, and display rotation that leaves the original files untouched.

## What it does

- **Local images:** choose multiple images using Android's file picker. The app keeps read access and remembers the selection on this device.
- **Google Fotos:** authorize the Photos Picker API, show its selection link as a QR code, and import the photos selected on a phone. Selected images are cached in the app's private storage for playback.
- **Playback:** continuous slideshow, 3–60 second interval, shuffle, next/previous controls, and a 700 ms crossfade.
- **TV display:** keeps the screen awake during playback, hides system bars, and accepts remote arrow/enter/media keys. The image can be rotated 0°, 90°, 180°, or 270° independently of the source file.

The Google Photos Picker only returns items that the user explicitly selects. It does not let third-party apps silently browse a whole library or retrieve an album as a playlist. Google removed broad library read access in 2025. The app therefore imports a picked set of photos; it cannot sync an entire album automatically. The Picker API can present its selection link as a QR code. See [Google's API changes](https://developers.google.com/photos/support/updates), [Picker flow](https://developers.google.com/photos/picker/guides/get-started-picker), and [polling requirements](https://developers.google.com/photos/picker/reference/rest/v1/sessions).

## Build and install

Requires JDK 17 and Android SDK platform 36. On macOS, set `ANDROID_HOME="$HOME/Library/Android/sdk"` if the SDK is installed in the default location.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the repository in Android Studio and run the `app` configuration. Minimum Android version is Android 7.0 (API 24). Fire TV devices without Google Play services can use local images; Google authorization requires Google Play services and a configured OAuth client.

## Configure Google Photos

1. Create a Google Cloud project and enable the **Google Photos Picker API**.
2. Configure the OAuth consent screen and create an **Android OAuth client** for application ID `dev.weisser.googlephotoscarousel`.
3. Add the SHA-1 signing certificate fingerprints for the build variants you use. For local debug builds, run `./gradlew signingReport`. The current public release certificate fingerprint is `57:AD:6F:0B:BE:CD:2C:78:75:9A:E1:5A:D9:5D:8A:5B:23:67:C0:D9`.
4. Publish the consent screen and complete Google's verification requirements before broad public use.
5. Install the app on a device with Google Play services, authorize the Photos Picker scope, scan the on-screen QR code with a phone signed into the authorized Google account, select photos, and confirm the selection.

No OAuth secret or user token is stored in the repository. Google Photos API access remains subject to Google's user data policies and the consent screen's publication status. See [PRIVACY.md](PRIVACY.md) for the app's data handling.

## CI and public releases

- Pushes and pull requests build a debug APK in GitHub Actions. The APK is available as a workflow artifact for 14 days.
- Pushing a version tag such as `v0.1.0` builds a signed release APK and publishes it as a public GitHub Release asset.
- The download page is published at https://weisser-dev.github.io/google-photos-carousel/ and links to the latest signed APK for Fire TV's Downloader app.
- Before creating a release tag, configure these repository Actions secrets with a dedicated Android release keystore: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `ANDROID_STORE_PASSWORD`. Keep a secure backup of that keystore; future app updates must use the same signing key.

## Private local files

APK files, decompiler archives, local build outputs, signing keys, and credentials are ignored by Git. The personal test photos used for local simulator checks are kept outside this repository and are never included in builds or releases.

## License

MIT. See [LICENSE](LICENSE).
