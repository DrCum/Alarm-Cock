# Extension and release roadmap

## 1. Verify and harden Android delivery

Run the physical-device matrix in `device-testing.md`. Add direct-boot storage/scheduling, user-visible delivery diagnostics, accessibility review, sound-library cleanup, and migrations for alarm definitions and active sessions. Preserve scheduled alarms and their task configurations during updates. Define an explicit recovery policy for a lost barcode or unavailable camera before public release.

Review current target-SDK, exact-alarm, full-screen intent, foreground-service, and Play Console requirements before distribution. Configure production signing and test the signed release binary.

## 2. Downloadable tasks and JavaScript games

The application remains Kotlin + Jetpack Compose. Native scheduling, playback, and task-session ownership continue without a game or JavaScript runtime running.

Add a versioned game host in a native release before shipping JavaScript games as downloaded content. A sandboxed Android WebView with a narrow, origin-restricted host interface is a candidate. Ship a bundled memory or reaction game first, including its completion verification, offline assets, lifecycle recovery, and difficulty demo. Do not expose unrestricted Android interfaces or a direct stop-service/snooze method to game code.

A task/game descriptor should contain a stable ID, pinned version, minimum host API, difficulty schema, example/demo entry point, asset hashes, signature, and supported completion-rule identifier. A game receives only its challenge/configuration and returns a result scoped to the current native session/challenge ID. The host verifies the result and advances the existing ordered task sequence. Define crash/timeout recovery and an accessible fallback policy that the user chooses while setting up the alarm.

Store installed bundles in app-private persistent storage. Download and verify every asset before allowing the task to be selected. Pin the task/game version in each saved alarm; an update must not silently change tomorrow's task or delete an installed version still referenced by an alarm. Running tasks and ringing alarms continue with their current version.

## 3. OTA content delivery

Use a catalog over HTTPS with signed manifests, content hashes, download size limits, runtime compatibility checks, atomic installation, and a last-working-version rollback. No server or signing configuration is present in this milestone. Never hot-reload a running task or replace its assets during an alarm. Downloading or connectivity loss must not block an already configured alarm.

Declarative tasks supported by the installed native engine, compatible game bundles, and sound assets can be candidates for content updates. New Android permissions, task validators, native capabilities, or changes to the Compose application require a new Android binary through the chosen distribution channel. The current native engine recognizes only text, math, and barcode tasks; arbitrary new task kinds cannot be installed into this binary over the air.

Use trusted, signed first-party JavaScript content initially. Review Google Play's interpreted-code policy and Android WebView security guidance before enabling dynamic content. Do not add self-updating native APK/DEX downloads as a substitute for store updates.

## 4. Built-in and downloadable sounds

Add source types for system, imported, bundled, and downloaded sounds. Persist each file with a content hash, duration, codec, attribution/license, and optional normalized derivative. Download to a temporary file, verify hash/size/decodability, then atomically install. Never stream an alarm sound when it rings; retain a local default fallback.

Add a sound preview with a stop control, storage management, normalization progress/cancellation, and optional per-alarm volume/ramp settings. Consider perceived-loudness normalization and limiting after listening tests on multiple devices. Current peak normalization maximizes sample peak; it does not equalize perceived loudness or override Android volume/DND settings.

## References

- [Android exact alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Time-sensitive notifications](https://developer.android.com/develop/ui/views/notifications/time-sensitive)
- [Jetpack Compose](https://developer.android.com/compose)
- [Android WebView security](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)
- [Google Play device and network abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
