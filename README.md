# Task Alarm for Android

A native **Kotlin + Jetpack Compose** alarm clock. Users choose tasks they must complete before dismissing or snoozing an alarm. The app works locally without an account or server.

## First milestone

- Create, edit, enable, disable, and delete alarms. Choose repeat weekdays or ring once.
- Independent, ordered task lists for **dismiss** and **snooze**. Complete every selected task and round. Empty lists allow the corresponding action immediately; switching actions restarts the newly chosen sequence.
- Random text and math problems at easy, medium, and hard difficulty. Setup shows examples. Choose 1–10 rounds and up to 10 tasks per action.
- CameraX scanning with the bundled ML Kit barcode model: accept any product barcode/QR code, or enroll one specific value. Matching preserves leading zeros, case, and whitespace. No first-use model download is required.
- Android's alarm-sound picker and custom audio import via the system document picker. Imported files are copied into private app storage.
- Optional offline peak normalization: decode up to five minutes / 64 MB of PCM to a bounded temporary file, then write a WAV with its peak near −0.17 dBFS. Unsupported/oversized files fail before saving. This does not change system alarm volume; the phone's volume buttons control the alarm stream while the app is open.
- Test an alarm immediately without scheduling its draft. Test snooze ends the test.
- Native exact `AlarmManager.setAlarmClock` scheduling, looping foreground-service playback, full-screen alarm notifications, persisted task progress, stale-answer rejection, and a queue for overlapping alarms. A test does not consume a real alarm occurrence.
- Restoration of schedules after boot, package replacement, and clock/time-zone changes. Reopening the app resumes a persisted ringing session.

This is an implementation milestone. There is no attached Android phone or emulator in the cloud environment, so camera, audio, lock-screen behavior, Doze, and reboot behavior require the physical-device checklist before daily use. A successful build does not certify unattended delivery.

## Volume controls and wake-up checks (0.2.1)

In each alarm’s editor, **Protect alarm volume** blocks volume keys while the app is visible and asks the ringing service to restore the initial alarm-stream level if Android changes it. If the stream started silent, the guarded level is 50% (rounded up to the nearest available step). The original level is restored when ringing ends. This is best effort: Android can deny changes, fixed-volume devices have their own rules, and system controls/force stop remain available. See [Android AudioManager limits](https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,int,int)).

**Allow mute while solving** shows a mute/unmute button once the user starts dismiss or snooze tasks. It silences playback without completing tasks, changing progress, or scheduling snooze. Leaving the app resumes sound; switching between dismiss and snooze also resumes sound. Both volume options default off, including for alarms created in 0.1.0.

Enable **Check that I’m out of bed** to start a wake-up cycle after dismissal, including after dismissing a failed check’s repeat alarm. Snoozing does not start the delay. The independent **Ask after dismissal** and **Time to respond** settings each default to five minutes and accept 1–1440 minutes.

Two minutes after dismissal, a notification shows **Wake-up check at [time]** using the phone’s time format. **Dismiss check** cancels the upcoming check and its re-ring deadline, including when task confirmation is configured. This option expires when the check starts; stale actions cannot bypass its tasks. Checks scheduled for two minutes or less skip the advance notice. The reminder is restored after reboot and supports checks already pending from 0.2.0.

The check uses a notification and requests a full-screen pop-up when Android allows it. Choose **Yes / No** or a separate ordered list of confirmation tasks. Yes, or completing every confirmation task before the deadline, ends the cycle. No rings immediately. An unanswered or unfinished check re-rings at its deadline. The repeat alarm reuses the original dismiss tasks by default, or uses custom repeat tasks when configured; snooze tasks stay unchanged. A repeat alarm that is dismissed starts a new check cycle until the user confirms they are up.

Check records, challenge progress, and exact native timers persist across app recreation. The advance reminder, due notification, and re-ring deadline are independently scheduled with AlarmManager, so the UI does not need to be open. Boot restoration re-arms the timers after unlock; overdue retries are delivered through an exact event rather than directly starting a media service from the boot receiver. Times are stored as wall-clock deadlines; manually changing the clock may shorten or extend a check. Editing task/settings, disabling, or deleting the source alarm cancels its pending checks. In 0.11.0, changing only schedule exceptions preserves an existing cycle. A new regular occurrence supersedes older checks for that alarm.

Use **Test wake-up check now** to open a check immediately while retaining the configured response deadline. No or timeout starts a test ring; dismissing or snoozing that test ends it without creating another check. The existing **Test sound & tasks now** does not create delayed checks.

## Maximum-volume playlist escalation (0.6.0)

Enable **Escalate unanswered alarms or after two snoozes** per alarm. An unanswered ring escalates after a configurable 1–120 minutes (default two), provided neither Dismiss nor Snooze has been selected. Starting an action’s tasks prevents that unanswered trigger. The next ring after two completed snoozes escalates immediately. A triggered cycle stays escalated through further snoozes and failed wake-up checks; a new regular occurrence resets it.

Escalation requests maximum alarm-stream volume and plays an ordered playlist, switching every **45 seconds** and looping the list. Short clips loop within their slot. Choose 1–12 bundled, phone, or imported tones and reorder them in the editor. **Test escalation now** starts a bounded test immediately without changing the saved alarm or creating wake-up checks. Mute while solving still silences playback; escalation restores the prior alarm volume when ringing stops. Android fixed-volume/DND rules can prevent volume changes.

The foreground service owns the timer and playlist, with a CPU wake lock while this option is active. Trigger and slot timestamps persist using elapsed time and a boot marker, with wall time for reboot recovery. Reopening the app, mute toggles, and service recreation do not restart an escalated slot. An unavailable tone falls back for its slot and rotation continues. Queued alarms capture their starting volume after the previous alarm restores it. Existing alarms keep escalation disabled.

## Additional wake-up tools (0.5.0)

- **Memory tasks:** watch 3, 5, or 7 numbered tiles flash, then repeat their order. Each round generates a new sequence. A replay control is available; returning to the foreground shows the sequence again. These tasks can dismiss, snooze, or confirm a wake-up check.
- **Snooze limits:** choose unlimited (default) or 1, 2, 3, 5, or 10 snoozes per alarm occurrence. A snooze counts only after all its tasks finish. The count persists through snoozed rings and wake-up retries; the next regular occurrence starts fresh. At the limit, only dismissal tasks can finish the alarm.
- **Shuffle task order:** optionally reorder each action’s tasks when that action starts, including wake-up confirmation. Every task and round remains required, and the saved configuration stays unchanged.
- **Vibration:** optionally repeat a vibration pattern while ringing. Sound mute leaves vibration active; dismissal, snooze, session switches, and service teardown clean up the effect. Android may still apply device/DND rules.
- **Bundled sounds:** Beacon, Pulse, and Chime are original synthesized PCM16 WAV presets. Selection copies a versioned sound into private storage, so playback works offline and retains the chosen version. The source generator is in `scripts/generate-sounds.py`; imported/system sounds and peak normalization remain available.

Existing alarms keep unlimited snoozes, ordered tasks, and vibration off. The compact moving dismiss target now scales with the phone’s font size while staying inside the visible alarm screen.

## Moving dismiss and Breakout (0.4.0)

**Moving dismiss button** is an optional per-alarm setting. A compact button (normally 112 × 48 dp) moves to a random position across the full visible alarm screen every five seconds. Safe system insets and the keyboard stay outside its area. Snooze stays fixed in the bottom-right corner with a smaller target (normally 80 × 36 dp); Dismiss avoids that target. Both scale with font size, and movement pauses in the background. Tapping it starts the existing dismiss tasks; it never skips them. Existing alarms keep the original button unless enabled.

Add **Breakout** to dismiss, snooze, or wake-up confirmation tasks. Set difficulty, active play per round (5–3600 seconds), and idle reset (5–120 seconds, default 20). Difficulty changes paddle width and ball speed. Play time, rather than score, completes each round; balls are unlimited. The native engine credits foreground frames only while paddle movement occurred within the last 2.5 seconds. Holding a stationary finger does not sustain time. Twenty seconds without movement resets the current round by default; leaving the foreground pauses credit and counts toward the idle gap. Progress checkpoints save approximately once per second. App recreation retains recent checkpoints; a long idle gap resets time. Scores and personal bests are kept locally and never unlock an alarm.

Breakout is bundled native content and works offline; a downloadable JavaScript host remains future work. Normal answer submissions cannot complete a game task. The native engine checks challenge IDs, active time, rounds, and subsequent tasks before finishing an alarm.

## Flashlight alarms (0.3.0)

Each alarm has **Off**, **Steady**, and **Flashing** flashlight options. Existing alarms default to Off. Steady keeps the rear torch lit; Flashing uses a half-second on/off pulse, once per second. The ringing service controls the light independently of audio and the task screen, so mute while solving leaves it active. Dismissal or snooze stops the light; wake-up-check repeat alarms and later snoozed rings use the same setting. A pending wake-up notification does not illuminate the torch.

Android requires Camera permission for torch control. The editor provides permission and app-settings buttons; enabling or testing a flashlight alarm checks permission and hardware first. If the camera becomes unavailable during an alarm, sound and tasks continue, the task screen shows a warning, and the service retries. Barcode scanning temporarily releases the torch so CameraX can use the camera, then resumes it after scanning closes or the app leaves the scanner. The service releases its torch pulse callbacks and CPU wake lock when it stops. Lock-screen, background, camera contention, and manufacturer-specific behavior still need physical-device verification.

## Build and run

Use Android Studio with Java 17 or newer, Android SDK 37, and an Android 8.0+ device (API 26+). Gradle runs on Java 21 in the retained cloud environment and uses a Java 17 compiler toolchain. The project has no Node, React Native, Expo, or Metro dependency.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is standalone, embeds the native UI, and uses Android's local debug signing key. It needs no development server. Release signing and Play distribution are not configured; a release build is unsigned.

For the retained cloud tools, activate `. /workspace/.onboarding/env.sh` in each shell. Its proxy helper remains outside this repository:

```bash
CI=1 python3 /workspace/.onboarding/gradle.py :app:assembleDebug :app:testDebugUnitTest --max-workers=2 --console=plain --no-daemon
```

On the phone, grant Alarms & reminders, alarm notifications, and full-screen alarm access using the setup card. The card also appears in the editor so granting access preserves the draft. Grant Camera for flashlight alarms or when enrolling or completing a barcode task. Set an audible alarm volume and use **Test sound & tasks now** first.

## Project map

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/com/taskalarm/app/MainActivity.kt` | Android lifecycle, lock-screen flags, system sound/document pickers, permissions |
| `app/src/main/java/com/taskalarm/app/TaskAlarmApp.kt` | Compose alarm list, editor, task previews, ringing and snooze/dismiss flows |
| `app/src/main/java/com/taskalarm/app/AlarmDraft.kt` | Editor models and task examples |
| `app/src/main/java/com/taskalarm/app/BarcodeCamera.kt` | CameraX lifecycle and bundled ML Kit scanning |
| `app/src/main/java/com/taskalarm/engine/` | Scheduling, persistence, task checking, sound processing, ringing service |
| `app/src/test/java/com/taskalarm/engine/` | JVM math/text/barcode matching, DST scheduling, and PCM/WAV tests |
| `docs/device-testing.md` | Physical-device acceptance checklist |
| `docs/roadmap.md` | Downloadable tasks, JavaScript games, sound packs, and release work |

## Current behavior and limits

Times follow the phone's local time zone. Nonexistent spring-forward times move forward by the DST gap. Repeated fall-back times ring only at their first occurrence; once that has passed, the next valid day is selected. Missed alarms are scheduled for the next valid day during restoration. A future snooze keeps its stored timestamp across restoration.

Reboot restoration runs after the phone is unlocked; direct-boot alarms are not implemented. An active ringing session is preserved across reboot but does not automatically restart media playback from the boot receiver. Opening the app resumes it. The sticky foreground service is intended to recover ordinary process death; this requires device verification. Android force stop, power-off, revoked permissions, alarm volume zero, and manufacturer power-management rules can prevent delivery.

Task answers are checked by the native engine against the active session and challenge ID. Camera results are validated as values, not cryptographic proofs of scanning. This is not an anti-tampering system. Alarm definitions and in-flight sessions use a private, versioned SharedPreferences JSON store. Backup is disabled to prevent restoring sessions and private sound paths onto another phone. Unused imported sounds are retained until app data is cleared; library management and cleanup are follow-up work.

There is no backend, downloadable catalog, JavaScript game host, OTA task distribution, production signing, or published app yet. The roadmap distinguishes downloadable content from native app updates.

## Workspace verification

Version 0.13.0 passed `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` on 2026-10-16. All 133 tests passed, including photo rounds, capture cancellation/expiry/replay, action switches, wake-up deadlines, photo-gated snooze scheduling, disabled snooze and legacy compatibility, native edit-session autosave/recovery, independent alarm delivery, pending snooze preservation, steady-light timing, scheduling/timezone boundaries, wake checks, routines, sensor/game completion, and audio/escalation recovery. Lint reported zero errors and 32 warnings, including diagnostics for alarm-lifetime wake locks; the service releases each lock when its effect stops. Main text/button color pairs exceed 4.5:1 contrast. APK signing matches previous builds, and ZIP integrity and embedded APK hashes were verified. The standalone universal debug APK is approximately 36 MiB and includes ARM64, ARMv7, x86, and x86_64 native libraries.

The user reported that basic ringing and task dismissal worked in 0.1.0. The volume controls, wake-up checks, advance reminders, flashlight modes, game/button controls, memory playback, vibration, bundled sounds, and escalation still require physical-device verification, especially Doze, notification actions, and reboot recovery. The 0.13.0 signing certificate matches earlier builds for installation over the existing app.

## Browser download

The current APK is also available through the private [Task Alarm download page](https://task-alarm-download-58b83158.mhorlacher444.chatgpt.site). Sign in with the owning ChatGPT account if prompted. Download the ZIP, extract it with Android Files, then open the APK. The ZIP preserves the original signed APK unchanged.

The download Site source is retained at `/workspace/sites/task-alarm-download`; its `.openai/hosting.json` contains the existing project ID for future updates.


### Wake-up expansion (0.11.0)

- Alarm health screen with access/capability checks and a 30-second locked-phone delivery test.
- Skip next/undo, pause-until, weekday/weekend shortcuts, and next-ring previews.
- Optional difficulty increase after completed snoozes and randomized wake-up check delays (default range 3–7 minutes).
- Offline native tilt/touch maze with ordered checkpoints; completion does not depend on score.
- Walking tasks (default 20 steps) and ambient-light tasks (default 200 lux for 3 seconds), with live demos, permission checks, and configured math/text fallbacks.
- Reusable local morning routines: save, rename, update, duplicate, review/apply, and delete. Applying keeps the alarm schedule and creates independent task copies.

All new settings preserve existing alarms on upgrade and default off. Walking credit pauses outside the task; light holds restart when backgrounded. Light tasks pause the app's flashlight. Missing sensors/access or no initial light reading within 15 seconds allow the configured fallback; walking/tilt tasks also detect a 15-second interruption in events. A step sensor measures movement and a light sensor measures brightness; neither proves location. Hardware behavior and lock-screen/Doze delivery require physical-device verification. Alarms before the first unlock after reboot remain unsupported.

Implementation details and acceptance checks: [wake-up features plan](docs/wake-up-features-plan.md).

### Light task fix (0.11.1)

The light threshold accepts any finite lux reading at or above the configured value. Android light sensors report on change, so steady brightness may produce no further events. The hold now uses monotonic foreground time while the latest reading remains above the threshold. The listener stays registered; silence after a valid reading neither resets the timer nor marks the sensor unavailable. Falling below the threshold, leaving the task, or recreating the process resets the hold. An initial on-change value with an older hardware timestamp starts the hold at receipt and cannot backdate completion.

### Compact editor and autosave (0.12.0)

The editor groups controls into collapsible sections. Morning routines stays above Time / days / name; only the latter starts expanded. Pause until is a compact action with Info, and longer explanations open in scrollable Info dialogs.

Valid edits autosave after an 800 ms typing pause. Incomplete or invalid fields leave the last valid alarm in place and show a Not saved status. Done and Android Back flush valid changes before leaving. Exit without saving restores settings from the start of the visit, or removes an alarm created during that visit. Revert restores the visit's opening settings; on a freshly reopened alarm it can restore the previous edit. Recovery history persists locally and stays inside the editor.

Native edit tokens serialize saving and rollback. Closed or superseded editors cannot write, and an alarm that rings/changes independently cannot be rolled back. Edits during a pending snooze retain its exact time/count. Alarm configuration changes keep the established wake-check cancellation behavior; restoring settings does not resurrect an already cancelled check or delivered alarm.

### Folderstorm-inspired theme (0.12.1)

The default theme uses the supplied Folderstorm screenshot as a visual reference: near-black backgrounds, plum panels with fine borders, lavender controls, warm white text, and small gold highlights. Dialogs, task previews, game boards, and the Android startup window share this palette. The memory task retains four distinct tile colors plus numbers, with a gold flash during playback. This is a screenshot-based interpretation, not extracted website CSS.

Info buttons announce their section to screen readers. Autosave retries after alarm access changes, so granting a missing permission can save a waiting valid edit without retyping.

### Full-screen moving dismiss and optional snooze (0.12.2)

Moving Dismiss now uses continuous random positions across the visible alarm screen rather than nine positions in a short panel. When enabled, Snooze is smaller and stays at bottom right. Dismiss avoids the Snooze target, movement pauses in the background, and the layout adapts to the keyboard, rotation, and font size.

In **Snooze settings**, turn off **Allow snooze** to remove Snooze from the ringing screen and block native snooze requests, including test rings and wake-up retries. Existing alarms and routines default to allowing snooze. The saved length, limit, and task list remain intact while disabled; unused task enrollment is validated again before enabling snooze.

### Morning stream photo (0.13.0)

Add **Morning stream photo** to dismiss, snooze, wake-up confirmation, or retry tasks. Each round requires a fresh rear-camera capture of the morning stream or toilet bowl, keeping the body out of frame. This verifies capture, not image contents. The editor includes a camera preview that does not affect alarms. No difficulty setting applies; rounds still work normally.

CameraX captures directly into an in-memory ImageProxy and immediately closes it. No photo files, thumbnails, uploads, or gallery imports are used. The camera dialog blocks screenshots/recording, releases the alarm flashlight, and cancels pending captures on pause/close. Native single-use receipts expire after 30 seconds and are tied to the current session and challenge. Text submissions, stale callbacks, cancelled captures, and late wake-up responses cannot complete photo tasks. Process recreation requires a new capture. Camera permission and a working rear camera are required.
