# Physical-device acceptance checklist

No physical-device checks have been executed in the cloud environment. Record device model, Android version, build variant, app version, and results for every run. Include a recent Pixel and a Samsung device, then expand to the oldest supported Android version.

## Alarm delivery

- Schedule a one-time alarm 2–3 minutes ahead. Leave the app, turn the screen off, and confirm audible ringing and the task notification. Repeat with the app removed from recents.
- Use `adb shell dumpsys deviceidle force-idle` on a test phone to verify Doze delivery, then `adb shell dumpsys deviceidle unforce` afterward.
- Verify time/weekday recurrence, midnight boundaries, clock changes, and DST behavior. Confirm snooze schedules exactly one next occurrence and returns to weekday recurrence afterward.
- Set two simultaneous alarms; finish the first and confirm the second starts. Snooze the first and confirm the queue continues.
- Reboot with a future alarm and a future snooze; first test without unlocking before their times, then repeat with unlock before the due time. Confirm exact restoration and the bundled pre-unlock tone.
- Kill the process without Android force stop while ringing; confirm service/audio recovery and retained task progress. Reboot while ringing and confirm bundled sound resumes before unlock, then verify the stored task progress after unlocking.
- Revoke exact-alarm permission, notifications, and full-screen access individually. Verify readiness warnings, enabling failures, and regrant/rescheduling. Confirm no permission denial is presented as a successfully enabled alarm.

## Task completion

- Try easy, medium, and hard text and math, checking the examples in setup against generated task styles. Wrong answers must not advance; text case and symbols must match exactly.
- Combine multiple task types and rounds in each action. No earlier completion should skip a later task. Switch actions midway and confirm the new sequence starts at task one.
- Repeat scanning for any barcode; enroll and match a specific barcode. A different barcode must fail. Test camera denial, permanent denial, dark-room scanning, leading zeros, QR payloads with spaces, and scanner cancellation.
- Send duplicate/stale submissions through an engine integration harness; the challenge ID must reject advancement. Background/reopen midway through a task; the challenge and progress must persist.
- Test task-free dismiss/snooze, a one-time snooze, recurring snooze, and the special test-mode snooze behavior.
- Test large font, TalkBack, keyboard visibility, device Back, and use on the lock screen. Finish an alarm and confirm keep-awake/lock-screen flags are cleared.

## Audio

- Pick system alarm sounds and import MP3, AAC/M4A, WAV, and Ogg supported by the device. Rename/move the original imported document and confirm the private copy still plays.
- Normalize a quiet file, silent file, full-scale file, stereo file, and an unsupported/oversized file. Confirm bounded failure and no orphan temporary files. Verify WAV header duration, sample rate, channels, and output peak with an independent audio tool.
- Check playback volume and looping, system alarm volume zero/nonzero, DND, headphones/Bluetooth, an incoming call, competing audio focus, and a missing/unreadable sound. Verify the default sound or fallback beep plays when an imported sound cannot.
- Confirm finishing an alarm releases audio focus, media playback, and wake resources, and removes the foreground notification.

## Update readiness (future)

- Install a compatible signed content update while idle and confirm existing alarms keep ringing with their original tasks.
- Attempt an update while ringing; activation must wait. Verify rollback after a broken content update, incompatible-host rejection, offline startup, and future task/game version pinning.

## Volume guard, intentional mute, and wake-up checks

- Upgrade from 0.1.0 without clearing app data. Existing alarms must retain their time, tasks, sounds, and enabled state; the new features default off.
- Enable Protect alarm volume, test a ring, and try volume down/up/mute in the task UI, camera screen, system volume panel, lock screen, and while the app is in the background. Check Android’s DND/fixed-volume limits and the guard warning when the system rejects adjustment. Start with alarm volume zero and verify the documented 50% fallback and restoration afterward.
- Enable Allow mute while solving. The button appears only during tasks. Mute, submit wrong answers, and advance multiple rounds: progress and alarm state must remain active. Unmute, switch actions, and background the app to verify audible playback resumes. Test both media playback and an unavailable-file fallback beep.
- Enable wake-up checks with a one-minute delay and one-minute response period. Dismiss a real scheduled alarm, close the app, and confirm the check notification appears, then ignore it and confirm re-ringing at the deadline. Repeat with screen off/Doze and after process death.
- Try Yes, No, and task confirmation. Yes ends checks; No immediately rings. Partial task completion must not extend the deadline. Test custom confirmation and repeat tasks, including specific barcodes and multiple rounds.
- Snooze a failed check’s repeat alarm. It must keep its custom repeat tasks, and the saved alarm’s original dismiss configuration must remain unchanged. Dismissing the repeat starts another check cycle.
- Reboot before the check and while its response window is open, unlock, and verify timer restoration and preserved task progress. Reboot past the deadline and verify an exact recovery event re-rings after unlock.
- Confirm notification Yes/No actions work without opening the app, task-mode notifications offer no Yes bypass, and dismissing a notification never cancels its deadline. Check notification-channel denial and exact-alarm permission revocation.
- Edit, disable, or delete an alarm with a pending check; it must cancel that cycle. Check two alarms with overlapping wake-up windows and a new regular occurrence while an old check is pending.
- Use Test wake-up check now in both modes. A resulting test ring must finish without creating another delayed check or changing the real alarm’s schedule.

## Flashlight alarms

- Upgrade from 0.2.1 without clearing data. Existing alarms must default to Off and keep their other settings. Save and reopen alarms in Steady and Flashing modes.
- Grant Camera using the editor. Test denial, permanent denial, a device with no flash, and permission revocation after scheduling. Sound and tasks must continue when the torch cannot be used; the task screen must show a warning.
- Test both modes with text/math tasks, mute/unmute, app backgrounding, a locked screen, and Doze. Flashing should pulse once per second; Steady should remain lit.
- Dismiss and snooze with and without tasks. Confirm the light shuts off, pulses stop, and the CPU wake lock is released. Check queued alarms using different flashlight modes.
- Open, cancel, and complete barcode scans during a flashlight alarm. The torch must pause for scanning and resume afterward. Leave the scanner in the background and return, checking that camera ownership changes do not strand the light or scanner.
- Let a wake-up check fail and then snooze its repeat alarm. Each ringing occurrence must use its configured light; silent check notifications must not turn it on.
- Use another camera app while ringing, then close it. Confirm sound/tasks continue and the light recovers when Android allows it. Test ordinary process death and service recreation; force stop must leave the torch off.

## Moving dismiss and Breakout

- Enable Moving dismiss button and verify the compact target can appear near all four corners and the center of the visible alarm screen, stays clear of system bars/the keyboard and the smaller bottom-right Snooze button, moves every five seconds, and starts dismiss tasks without skipping them. Test large fonts, landscape, TalkBack, and a snooze-to-dismiss action switch.
- Add Breakout at each difficulty. Change active seconds and idle reset, save, and reopen. Combine it with math, text, barcode, and multiple rounds for dismiss, snooze, and wake-up confirmation.
- Keep moving the paddle until the timer completes even with a low score. Miss balls repeatedly; there must be no score/lives gate. Hold a stationary finger, stop touching, and background the app: time must stop increasing. At 20 seconds idle, the current round resets.
- Return within the idle limit and after it. Kill/recreate the process mid-game and check checkpoint restoration and expired progress. Confirm a wake-up-check deadline still re-rings while playing.
- Mute/unmute sound and enable each flashlight mode while playing. Completing the last game must dismiss or snooze only after every configured round and following task.

## Additional wake-up tools

- Add Memory to each action and wake-up confirmation at all difficulties. Watch 3/5/7 flashes, submit a wrong sequence, replay, complete multiple rounds, and background/reopen while flashes are playing. Wrong or stale submissions must not advance.
- Configure one snooze with several snooze tasks. The counter must stay zero until those tasks finish, then persist through the snoozed ring and a failed wake-up check. The native engine and UI must both reject further snoozes. Verify the next regular occurrence starts at zero.
- Enable Shuffle task order. Confirm every task/round still appears, progress resumes in the same presented order after app recreation, and saved editor order remains unchanged. Check separate dismiss, snooze, and wake-up lists.
- Enable vibration, then mute, scan a barcode, switch queued alarms, dismiss, and snooze. Muting only changes sound; final teardown must stop vibration. Check DND and devices without a vibrator.
- Choose Beacon, Pulse, and Chime, listen on the device, save/reopen, and test screen-off playback. Normalize each preset and verify the original remains available. Upgrade without clearing data and check saved private sound paths.

## Maximum-volume playlist escalation

- Upgrade from 0.5.0 and confirm existing alarms keep escalation off. Enable it on one alarm with a short unanswered delay, leave the screen off, and verify escalation to maximum alarm volume. Pick Dismiss or Snooze tasks before the delay and verify the unanswered trigger is suppressed.
- Complete two real snoozes. The next ring must immediately use maximum volume and the playlist. Test with snooze limits, wake-up failures/retries, and the next regular occurrence to verify count and latch boundaries.
- Select bundled, phone, and imported tones; reorder and remove them. Check each 45-second slot, short-clip looping, a long clip’s cutoff, playlist wraparound, and fallback for a missing/unreadable file.
- Mute/unmute while solving; playback volume should change without restarting the slot or advancing tasks. Leave the app and check sound resumes as configured.
- Recreate the service/process during a slot, change the wall clock, and reboot/unlock while a session is pending. Verify elapsed-time scheduling within a boot and recovery afterward. Check DND/fixed-volume restrictions and the volume warning.
- Dismiss/snooze, stop the test, and switch to a queued alarm: restore the previous volume, release the old player, and cancel the rotation/guard/wake lock. A queued non-escalating alarm must not inherit maximum volume as its original setting.

## 0.11.0 wake-up expansion

- Upgrade over 0.6.0 without clearing data. Verify existing schedules, task lists, sounds, volume escalation, and active sessions. New difficulty progression and random checks must default off.
- Open Alarm health, revoke/regrant each access, change channel settings, and return. Check capability labels and battery-settings links. Schedule the 30-second delivery test, lock the phone, acknowledge it, and verify the recorded result and unchanged real alarms. Test with another ring already queued; acknowledgement means observed delivery, not a future guarantee.
- Skip a recurring occurrence, reboot, and undo it before the original time. Deliver a stale broadcast for the skipped time; it must not ring early. Skip a one-time alarm and undo. Pause for several weeks, verify preview and next ring, clear the pause, and use weekday/weekend shortcuts. Check DST and timezone changes: a skipped local date remains excluded in the new zone; pause-until retains its chosen instant. Pauses/skips do not cancel pending wake checks. A pause-only edit retains an active snooze; changing other alarm settings keeps the existing edit/replacement semantics.
- Enable progressive difficulty with easy text/math/memory/Breakout/maze. Complete one snooze and check Medium; complete two and check Hard. Repeatedly switch actions, recreate the app, and fail several wake checks: difficulty must remain based on completed snoozes. Durations, step targets, light settings, and barcode values must not change. The next regular occurrence starts from configured difficulty.
- Choose random wake-check delays, including equal bounds and a range reaching below two minutes. Confirm the chosen time remains fixed across backgrounding, process recreation, and reboot; the two-minute advance notice shows that actual time when applicable. Early dismissal, response deadlines, custom tasks, and retry tasks must keep their behavior.
- Try maze demos and actual alarms in Easy/Medium/Hard with tilt and touch controls. Calibrate in portrait and landscape. Reach numbered checkpoints in order, attempt to cross walls, pause/reopen, and kill/recreate the process. Test TalkBack direction controls and large fonts. Complete multiple rounds mixed with existing tasks for dismiss, snooze, wake confirmation, and custom retries.
- On phones with step detector and/or counter sensors, grant walking permission, try the live demo, and complete the target while carrying the phone. Verify steps before opening the task and while backgrounded do not count. Reopen and recreate mid-task; credited steps persist. Test denied/revoked permission, no sensor, and no events for 15 seconds. The configured fallback still requires its remaining rounds and following tasks. Compare actual walking with stationary movement; a step sensor does not prove physical location.
- Try the light demo in the intended room before saving a threshold. Verify that a single initial bright reading can sustain the entire hold when light stays unchanged; a new below-threshold reading and backgrounding/recreation reset it. Initial cached hardware values must start timing at receipt, without backdating completion. Test devices with on-change-only sensors, covered sensors, screen glare, and missing hardware. Enable alarm flashlight: it must pause during the light task and resume for the next task. Test fallback, mute, and wake-check timeout while measuring.
- Save, rename, replace, duplicate, apply, and delete routines. Review all replacement settings before applying to a draft. Existing alarm time/days/name/enabled state and pause/skip must stay intact. Applied alarms must be independent of later routine edits/deletion. Strong wake-up needs user barcode enrollment; sensor routines need capability/fallback review. Remove a referenced imported sound and check the readable error before application.
- Check resource cleanup when changing tasks, switching actions, completing/snoozing, closing demos, receiving a wake-check deadline, and moving between queued alarms. No sensor listeners, light measurements, vibration, sound players, or wake locks should remain after their session ends.

## 0.11.1 light task regression

- Set 200 lux / 20 seconds. Place the phone in steady light well above 200 lux; the timer must rise continuously even if no new sensor event arrives. Repeat with slow updates and brightness changes that remain above 200. There is no upper lux limit and a reading exactly at 200 qualifies.
- Cover the ambient sensor to fall below the threshold: progress must reset. Uncover it to begin a new hold. Leave/reopen the task and recreate the process; each requires a new reading and a full hold.
- Try the live demo, a scheduled alarm, snooze tasks, and wake-up confirmation. Keep the existing flashlight pause/resume and wake-check deadline behavior.
- Check an unsupported sensor, failed registration, and 15 seconds without any initial reading: the configured fallback remains available. Silence after a valid on-change reading must not trigger that warning.

## 0.12.0 editor and recovery

- Open an alarm: Morning routines is collapsed above the expanded Time / days / name section. Every other settings section starts collapsed. Toggle sections independently; rotate/recreate and check retained expansion state. Test large fonts and TalkBack headers/Info buttons.
- Change several fields quickly. After a short typing pause, confirm Saved and reopen to verify persistence. Clear a time, enter an invalid game duration, or add an unenrolled barcode: Not saved must remain visible and the last valid alarm must stay scheduled. Fix it and verify autosave resumes.
- Use Done and Android Back immediately after typing; both must flush the latest valid values. With invalid input, remain in the editor and offer Exit without saving. Test while a normalization/import operation is running.
- Make multiple autosaved changes, then Exit without saving. Restore the original settings and schedule. Repeat for a newly created alarm: it must disappear. A late save must not recreate it. Revert during editing must restore the baseline without leaving; reopen a previously saved edit and use Revert to recover its previous settings.
- Return from camera enrollment, audio pickers, Android permission settings, and task demos. Retain the edit session and saved state. Background/recreate the activity midway through editing and verify recovery and validation status.
- Let an alarm ring while its editor is open. Its delivered session and next occurrence must survive stale autosaves and discard. Try an edit within one second of a due alarm: it must not postpone that ring. Edit/undo a pending snooze and verify its time and count stay intact.
- Open Pause until: both the picker button and Info must remain visible. Check short pause status/Clear pause, next-ring preview, and weekday/weekend shortcuts in the time section. Long explanations must be available in scrollable modals without crowding the controls.

## 0.12.1 theme

- Check the list, expanded/collapsed editor sections, Info dialogs, text fields, chips, switches, task previews, and active alarm/wake-check screens for readable text and clear focus/selection states. Startup and navigation/status bars should match the near-black background.
- Play Breakout, maze, and memory: walls, paddle, checkpoints, ball, numbered tiles, and playback flashes must stay distinct. Check memory tile labels both while playing back and while answering.
- With TalkBack, Info buttons should announce the section name. Revoke alarm access, edit an enabled alarm, then grant access from the setup card; verify the pending valid edit autosaves after returning.

## 0.12.2 moving actions and snooze switch

- With moving dismiss enabled, scroll the task screen, rotate, open the answer keyboard, and change font size. Dismiss must use the current visible viewport instead of scrolling away with page content. Snooze stays smaller and fixed at bottom right; its disabled state still respects snooze limits and an active snooze task. Finish dismiss tasks and confirm the floating Dismiss disappears during that action.

- Turn off Allow snooze, save/reopen, and test with moving dismiss on and off. Only Dismiss should be offered. Dismissal must still require all selected tasks. Let a wake-up check fail: the repeat ring must also have no Snooze. Re-enable snooze and confirm its previous length, limit, and tasks remain. Save/apply a routine with snooze disabled and confirm that choice carries over. Upgrading old alarms/routines must leave snooze enabled.

## 0.13.0 morning stream photo

- Add the task to dismiss, snooze, wake-up confirmation, and custom retry lists. Use two rounds followed by math. Each round must require a new shutter press; cancelling/back or returning from the background must leave progress intact but incomplete. Camera preview in the editor must not change any alarm.
- Grant/deny Camera, return from app settings, revoke it while open, and contend with another camera app. Show retryable errors without completing tasks. Test on the lock screen, landscape, and large fonts; capture/cancel controls must remain reachable.
- Enable steady/flashing alarm flashlight, then open/close the photo camera. The service torch must pause while the camera owns it and resume after closing. Alarm audio continues unless muted through the configured solving control.
- Attempt a screenshot/screen recording during camera preview. It must be blocked. Verify no photo appears in gallery, Files, app cache, or external storage after preview/capture/cancel/error. No image is uploaded; the application has no Internet permission. The app does not recognize image contents.
- Let a wake-up response deadline pass during capture. The alarm must ring again and a late camera callback must not dismiss it. Rotate, close, or recreate the process during an in-flight capture: another fresh capture is required. Test capture failures/timeouts and retries.

## 0.14.0 overlap queue and duplicates

- While solving math/text (including a partly typed answer), barcode, photo, memory, Breakout, maze, walking, or light, let another alarm, snoozed ring, and wake check arrive. Keep the same task and completed rounds on screen; a scanner/camera must not close merely because another item arrived. Inspect Queue to see the ordered waiting items. Finish each and verify the next one appears. Repeat with a wake-up task already in progress and a shorter-deadline check arriving afterward.
- Let a wake check wait behind a long task beyond its original deadline. It must remain queued, then get its configured response window once at the front. Repeated notifications/state refreshes must not extend that active deadline. Background an active check and let it time out: its retry must join the queue without deleting other progress. Recreate the app/reboot while items are waiting and confirm order and partial task progress recover.
- Use Duplicate with 5 additional alarms every 5 minutes; review dates, create, reopen, and independently edit a copy. Check matching tasks/sound/settings and unchanged original. Test disabled originals, one-time and recurring alarms, weekday-to-weekend and midnight rollover, and DST boundaries. No copy should ring before the original’s reviewed first occurrence.
- Try blank/invalid counts and intervals, a series spanning 24 hours, missing alarm/notification access, a changed source, and an expired preview. Creation must fail without partial alarm cards or orphan scheduled copies. Test rapid double taps while creation is busy.


## 0.15.0 restart and closed-app delivery

These checks require a physical phone. Prioritize the reported Pixel 8 on its installed Android version; the app health screen records the actual version. An overnight update/restart was suspected, not confirmed.

- Install over the existing app, open once, and verify **Restart & delivery history** says prepared. Confirm original alarms, tasks, barcodes, and sound selections remain intact. Check exact-alarm, normal/restart notification, and full-screen access; set an audible alarm-stream volume.
- Run **Restart test · ring in 2 minutes**, restart immediately, and do **not** perform the first PIN/pattern/password unlock. Expect bundled Beacon audio and an unlock prompt at the test time. Unlock and dismiss the test; verify the boot sound stops and no second copy rings. Repeat without full-screen permission: the notification and audio must remain useful.
- Repeat with a real math/barcode/game alarm and an imported sound. Before first unlock, only the bundled tone and unlock screen should appear. After unlock, configured playback/effects and the original tasks should resume. No task answer, barcode, custom-sound URI, or user label should be exposed in pre-unlock notifications.
- Swipe away the UI and lock the screen before a regular alarm and a snooze. Separately let Android kill the process (without Force stop). Confirm delivery, sound, task queue, and progress. Repeat in Doze and battery saver.
- Power off across an alarm, then restart within 12 hours of its due time; expect catch-up rather than rescheduling to tomorrow. Repeat with a snooze. Check a future alarm retains its exact timestamp after package update and app reopen. For an occurrence older than 12 hours that was never delivered, expect a recorded missed occurrence and the next valid schedule.
- While a task is partly complete and temporarily muted, restart. Expect audible recovery and retained completed rounds after unlock. Queue another alarm/check during this sequence and confirm it does not replace the current task. Repeat with a recurring alarm and verify its next occurrence remains scheduled.
- Dismiss an alarm with a wake check, then restart before due, during its response window, and across its deadline. Without first unlock, expect an unlock notification when due and bundled alarm audio at timeout. After unlock, verify the configured confirmation/retry and queue order. A waiting check behind another alarm must still receive its response window at promotion.
- Inspect history for locked-boot restoration, pre-unlock delivery/audio, post-unlock restoration, and normal audio handoff. Confirm boot count and estimated boot time change after an actual restart. These records indicate app events; an audio-start record alone does not prove the speaker was audible.
- Use Android **Force stop** as a separate negative test: no app can promise scheduled delivery in the stopped state. Reopen Task Alarm, verify schedule repair and recent catch-up, then retest ordinary app closure. Also test revoked exact/full-screen/notification access, alarm volume zero, and DND, documenting the limits. Keep a system Clock backup until the restart/Doze tests pass.
