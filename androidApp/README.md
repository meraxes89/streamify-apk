# androidApp/ — Android application shell

The shipping Android APK: manifest, activity, widgets, signing, and store
variants. The Compose UI itself lives in [composeApp](../composeApp/README.md);
this module hosts it.

## Module facts

- `applicationId = "com.streamify.app"`, namespace `com.maxrave.simpmusic`,
  `compileSdk = 37`, `minSdk = 26`, `targetSdk = 36`, Java 21.
- Version from `gradle/libs.versions.toml` (currently 1.0.1-beta, versionCode
  64).
- ABI split is release-only: `armeabi-v7a`, `arm64-v8a`, `x86_64` plus a
  universal APK; debug builds are unsplitted.
- `jniLibs.useLegacyPackaging = true`; the kuromoji dictionary is excluded
  from packaging and fetched on demand.
- Signing: debug keystore committed for debug; release reads
  `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
  environment variables (fallback: debug keystore). `isFullBuild` gradle
  property toggles crashlytics vs the FOSS `crashlytics-empty` stub and the
  Sentry meta cleanup.

## Manifest inventory

- `MainActivity` (`singleTask`) hosts the Compose `App()` composable and
  starts the media service.
- Media services: `com.maxrave.media3.service.SimpleMediaService` — the
  Media3 `MediaSessionService` (declared here, implemented in the `core`
  submodule), `foregroundServiceType="mediaPlayback"`, with MediaLibraryService
  and MediaButton intent filters; `MusicDownloadService` (dataSync); the
  Android Auto car app service; a Quick Settings recognition tile.
- Widgets (Glance): TurntableWidgetReceiver, PlaylistsWidgetReceiver,
  ListeningInsightsWidgetReceiver.
- Permissions include `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `READ_MEDIA_AUDIO`,
  `RECORD_AUDIO` (recognition), `CAMERA` (login-sync QR), local network
  (jam).
- Deep links: `streamify://callback`, `streamify://jam`, YouTube hosts, and
  the Last.fm `wordbyword://` auth callback.

## vivo store variant (vivoAndroidApp/)

`vivoAndroidApp` reuses androidApp's sources with different packaging:
`applicationId = "com.spotify.music"` (a package name vivo's Origin Island
tracks; it must remain in the official package list and be release-signed),
ABIs `arm64-v8a` + `armeabi-v7a` only with universal APK (PR #248), and
Full-build-only shipping. Build it with
`./build_and_sign_apk.sh --release --full --vivo` or
`./gradlew :vivoAndroidApp:assembleRelease`.

## Build and test

```bash
git -C core apply ../core.patch    # required: patches the core submodule
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:testDebugUnitTest -PskipExternalNativeBuild=true
./scripts/build_and_sign_apk.sh --release --full      # signed release (env keystore)
```

CI lane: `_ci-android.yml` runs the unit suite and APK assembly; release mode verifies the universal APK contains `lib/arm64-v8a/` and `lib/x86_64/`.

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
