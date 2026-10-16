# Wake-up features implementation plan

Status: implemented in the 0.11.0 test build, pending physical-device verification. Baseline: Task Alarm 0.6.0. All eight additions below are included. The five planned milestones were combined into one upgrade for this implementation.

## Scope and shared rules

The five milestones below describe the implementation order. They are delivered together in the signed 0.11.0 APK on the existing download page and in the existing feature PR; intermediate version numbers remain planning references.

- Keep all new behavior optional. Upgrading must preserve existing alarms, tasks, sounds, and pending sessions, with new settings off by default.
- Keep scheduling, challenge validation, and completion in the native Kotlin engine. Compose presents progress and sends input; a UI callback alone cannot dismiss an alarm.
- Provide a configuration example or demo for every new task. Support dismiss, snooze, wake-up confirmation, custom retry tasks, mixed sequences, and multiple rounds.
- Persist the selected challenge, effective difficulty, random times, and progress so recreation cannot reroll an easier task or postpone a deadline.
- Share capability checks between setup and runtime. Sensor tasks require a tested fallback selected during setup; losing hardware access must not silently complete the task or strand the user.
- Continue to support intentional mute, flashlight settings, snooze limits, shuffled tasks, and the existing maximum-volume playlist.

## Milestone 1 — Alarm health and schedule exceptions (candidate 0.7.0)

### Alarm health screen

Expand the existing readiness information into a screen showing exact-alarm access, notification permission and channel status, full-screen access, battery restrictions, next scheduled ring, and capabilities required by saved tasks. Link each actionable warning to the appropriate Android settings page.

Show a short delivery test: schedule a real test alarm, explain that the user should lock the phone, and record whether delivery was observed and acknowledged. Distinguish an acknowledgement from a guarantee of future delivery. Display the current limitation around alarms before the first unlock after a reboot; full direct-boot support remains a separate reliability item.

Implementation: extend `AlarmEngine.readiness()` and add a dedicated Compose screen. Report the next time actually scheduled by the engine. Recheck access on returning from settings. Do not imply that Android force stop, power-off, or manufacturer battery rules can be overridden.

Acceptance: permission/channel denial and regrant update the screen accurately; the test exercises native scheduling, sound, lock screen, and cleanup without modifying a real alarm or creating follow-up checks.

### Schedule exceptions

Add Skip next occurrence with Undo, Pause until a selected local date/time, and weekday/weekend shortcuts in the existing day selector. Show the resulting next ring before saving and on the alarm list.

Define boundaries explicitly: skipping or pausing affects future regular occurrences. It does not dismiss an already ringing alarm or cancel an active snooze/wake-up cycle; those remain separately visible. Skipping a one-time alarm disables that occurrence and allows Undo. Pause is a scheduling constraint, so no background timer needs to run just to re-enable the alarm.

Implementation: extend `Timing.next` with persisted occurrence exclusions and a pause boundary; remove the current seven-day search assumption so a long pause works. Use the same calculation in save, fire, restore, and previews. Keep the existing phone-local timezone and DST behavior. Persist a consumed skip so repeated restore calls cannot skip multiple occurrences, and define recomputation on timezone changes without duplicating a ring.

Acceptance: skip/undo, one-time alarms, several-week pauses, weekday/weekend presets, DST, timezone changes, reboot, stale scheduled intents, and an active snooze alongside an exception all produce one consistent next occurrence.

## Milestone 2 — Progressive difficulty and random wake-up checks (candidate 0.8.0)

### Escalating difficulty after snoozes

Add an optional per-alarm rule: increase eligible tasks by one difficulty level after each completed snooze, capped at Hard. Show previews for the original alarm, after one snooze, and after two. Count completed snoozes using the existing occurrence counter; tapping Snooze without finishing its tasks does not increase difficulty.

Text, math, memory, Breakout, and later the tilt maze use their existing difficulty meaning. Barcode identity, configured game duration, idle-reset duration, step count, and light threshold stay explicit and do not silently change. Easy becomes Medium after one snooze and Hard after two; Medium reaches Hard after one.

Implementation: derive an effective task snapshot from the saved base configuration at the start of each action. Apply the same occurrence count to dismiss, snooze, confirmation, and retry tasks. The snooze tasks that authorize the next snooze use the current count; the increment applies after they finish. Preserve the snapshot on recreation and reset the count at the next regular occurrence.

Acceptance: previews match generated challenges; switching actions never compounds the difficulty increase; wake-up retries preserve the count; the saved base task remains unchanged. Test interaction with snooze caps and volume escalation.

### Random wake-up check timing

Offer Fixed delay (existing behavior) or Random delay within a minimum/maximum range, initially 3–7 minutes. Keep the response window separate, defaulting to five minutes.

Choose the actual due time once at dismissal and persist it before scheduling. Continue the user's two-minute advance notice when the selected due time is later than that notice. Its text shows the actual check time and keeps the existing early-dismiss action. If the check is due within two minutes, omit the advance notice. Randomization varies the timing between cycles; it does not conceal the time shown in the reminder.

Implementation: extend wake-check configuration and use an injectable random source for scheduling tests. Derive reminder, due, and deadline timers from one persisted cycle. Reboot, process death, and notification actions must not sample another delay or extend the response deadline.

Acceptance: selected times stay within the configured range; min=max behaves as fixed timing; exact reminder text matches the chosen time; Yes/No, task confirmation, early dismissal, and timeout all retain current semantics.

## Milestone 3 — Tilt maze task (candidate 0.9.0)

Add an offline game where the user tilts the phone to guide a ball through ordered checkpoints to an exit. Difficulty changes maze layout, corridor width, and ball handling. Completion requires the checkpoints and exit, independent of score.

Provide a short demo, centered-position calibration, progress display, and a touch-control option for devices or users that cannot use tilt controls. Select and save the control mode during setup; a sensor failure uses the configured fallback rather than accepting a completion result.

Implementation: use a bounded native physics engine driven by gravity/rotation readings, with accelerometer support where appropriate. Apply filtering, account for screen orientation, and reject stale or impossible frame intervals. Native state owns the ball, checkpoint order, collisions, and completion. Pause simulation in the background and persist checkpoints without allowing a resumed frame to jump through a wall.

Acceptance: the ball cannot cross walls or skip checkpoints; calibration and rotation behave consistently; process recreation restores progress; sensor loss follows the fallback. Test all action types, mute, flashlight, and wake-check deadlines. The maze is a completion task; Breakout retains its active-time completion rule.

## Milestone 4 — Walking and bright-light tasks (candidate 0.10.0)

### Walking task

Add a configurable step target, default 20, with quick choices 20/30/40 and a bounded custom value. Explain that the phone must move with the user. Check step-detector/counter availability and request Activity Recognition permission where required during setup, with a live sensor test.

Count steps only for the current active challenge. Prefer the step detector; use a counter baseline when necessary and handle hardware counter reset/reboot. Retain credited progress across recreation, exclude steps taken before the challenge, and do not double-count replayed readings. End sensor collection when the task stops being active. Backgrounding pauses task credit; clearly explain this in setup.

Require a saved non-sensor fallback such as math or text for unavailable hardware, denied/revoked permission, or sensor failure. Do not replace verified steps with a generic shake counter. A phone step sensor measures movement and cannot prove that someone is standing or prevent all deliberate spoofing.

Acceptance: setup detects supported and unsupported devices, permission denial is recoverable, stale/cumulative events cannot grant instant completion, progress survives recreation, and listener cleanup works when switching actions or finishing a session. Verify actual walking and stationary movement on physical phones.

### Bright-light task

Use the ambient-light sensor to require a chosen illumination threshold for a continuous hold, initially 200 lux for three seconds. Provide a live lux meter and calibration demo so users can select a threshold achievable in their room. Present this as turning on a light or reaching a brighter location; it cannot verify outdoor daylight or physical location.

Require a functioning light sensor and a saved non-sensor fallback. Register only during the active task, reject stale readings, and reset the hold when light falls below the threshold or the app backgrounds. Temporarily pause this app's alarm flashlight during measurement and restore it afterward. Check for interference from the screen and sensor placement during device testing.

Acceptance: brief flashes cannot satisfy the hold, loss of readings cannot complete it, the alarm's own torch does not remain on during measurement, and permission/capability loss follows the configured fallback. Verify thresholds and reading cadence on different devices.

## Milestone 5 — Morning routine presets (candidate 0.11.0)

Let users save, name, duplicate, apply, and delete reusable routine presets. A routine contains ordered dismiss/snooze tasks, wake-up settings and retry tasks, difficulty progression, mute, flashlight, vibration, snooze limits, and volume escalation. Keep alarm time, recurrence, and schedule exceptions outside the routine so applying one does not unexpectedly move an alarm.

Include editable starter routines: Quick start (math plus memory), Get moving (math plus walking), and Strong wake-up (tilt maze, a user-enrolled bathroom barcode, and a follow-up check). Offer a light-task variant when supported. Treat barcode enrollment and sensor checks as required setup steps rather than shipping a fake ready-to-use barcode or unsupported task.

Implementation: store versioned routine definitions locally. Applying creates independent task IDs and copies settings into the alarm; later preset edits do not silently change saved alarms. Provide a review screen showing the tasks, follow-up timing, and affected settings before applying. Validate imported/copied sound references and hardware requirements.

Acceptance: applying, editing, and deleting a preset leave other alarms unchanged; unavailable capabilities are resolved before enabling; task order, rounds, difficulties, and examples match the final copied alarm. Test upgrade and process-recreation behavior.

## Delivery and verification

For each milestone, implement persisted models and native rules before the editor and active-task UI. Add focused tests for scheduling, validation, lifecycle recovery, and feature interactions; run the existing regression suite, debug build, and lint. Extend `device-testing.md` with the new physical-device checks.

Publish each completed milestone through the current APK download page, preserving the signing identity and upgrade path. Record exactly which checks were automated and which have been observed on a phone. Hardware, lock-screen delivery, Doze, audio restrictions, and sensor behavior need physical-device verification before a public release.

After these milestones, continue the existing [extension roadmap](roadmap.md): direct-boot reliability, production distribution, a versioned downloadable game host, signed OTA content, and downloadable sound packs remain planned. Sensor permissions, native validators, and these new Compose screens require APK updates; they cannot be added to the current binary as content alone.

## Implementation notes for 0.11.0

Schedule skips persist both the selected instant and local date. In a different timezone, the saved local date remains excluded. A pause keeps its selected instant. Skip is unavailable during a pending snooze; pausing alone preserves that snooze and pending wake checks. Changing other settings retains the established alarm-edit behavior.

Walking credit and maze progress are checkpointed during play and on foreground exit. Light holds restart on backgrounding or process recreation. Sensor tasks offer the configured math/text fallback on missing hardware/access or 15 seconds without events. A quiet step detector can legitimately produce no events when stationary; the fallback remains an intentional recovery route after that timeout. Light measurement re-requests hardware readings from on-change sensors and never extends a hold using cached brightness.

Routine presets also copy the selected alarm sound. Applying validates referenced sound readability and preserves the target alarm’s scheduling fields. The starter library includes a light-task variant; unsupported sensor tasks retain an explicit fallback.
