package com.taskalarm.engine

import android.app.Application
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateFormat
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WakeCheckFlowTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private var time = 0L
  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    store = AlarmStore(context)
    time = System.currentTimeMillis()
    engine = AlarmEngine(context) { time }
  }
  private fun alarm() = AlarmDraft(days = emptySet(), dismissTasks = emptyList(), wakeEnabled = true)
  private fun fire(alarm: AlarmDraft) {
    engine.save(alarm.json().toString())
    time = store.find(alarm.id)!!.getLong("nextAt")
    // The platform consumes the delivered one-shot PendingIntent before calling the receiver.
    val manager = context.getSystemService(AlarmManager::class.java)
    shadowOf(manager).scheduledAlarms.toList().forEach { scheduled -> scheduled.operation?.let { manager.cancel(it) } }
    engine.fire(alarm.id)
  }
  private fun complete(action: String = "dismiss") {
    val id = store.sessions().first().getString("sessionId")
    engine.begin(id, action)
    while (store.sessions().firstOrNull()?.getString("sessionId") == id) {
      val session = store.sessions().first()
      engine.submit(id, session.getString("challengeId"), session.getString("answer"))
    }
  }
  private fun due(): JSONObject {
    val check = store.checks().single()
    time = check.getLong("dueAt")
    engine.wakeEvent(check.getString("sessionId"), "due")
    return store.checks().single()
  }

  @Test fun defaultTimersBeginAfterDismissAndYesCancelsTheDeadline() {
    fire(alarm()); complete()
    val record = store.checks().single()
    assertEquals(time + 5 * 60_000L, record.getLong("dueAt"))
    assertEquals(time + 10 * 60_000L, record.getLong("deadlineAt"))
    assertNull(JSONObject(engine.state()).optJSONObject("wakeCheck"))
    assertEquals(3, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    val check = due()
    assertNotNull(JSONObject(engine.state()).optJSONObject("wakeCheck"))
    engine.respondWake(check.getString("sessionId"), true)
    assertTrue(store.checks().isEmpty())
    assertTrue(store.sessions().isEmpty())
    assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    time = record.getLong("deadlineAt")
    engine.wakeEvent(record.getString("sessionId"), "deadline")
    assertTrue(store.sessions().isEmpty())
  }
  private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

  @Test fun flashlightSettingDefaultsOffForExistingAlarmsAndRoundTrips() {
    val legacy = alarm().json().apply { remove("flashlightMode") }
    assertEquals("off", AlarmDraft.from(legacy).flashlightMode)
    for (mode in listOf("off", "steady", "flashing")) {
      val draft = alarm().copy(flashlightMode = mode, enabled = false)
      engine.save(draft.json().toString())
      assertEquals(mode, AlarmDraft.from(store.find(draft.id)!!).flashlightMode)
    }
    assertThrows(IllegalArgumentException::class.java) { alarm().copy(flashlightMode = "bad").validate() }
    assertThrows(IllegalArgumentException::class.java) { engine.save(alarm().copy(flashlightMode = "bad").json().toString()) }
  }
  @Test fun enablingFlashlightNeedsHardwareAndPermissionButRevocationDoesNotStopARingingAlarm() {
    val draft = alarm().copy(flashlightMode = "steady")
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH, false)
    assertThrows(IllegalStateException::class.java) { engine.save(draft.json().toString()) }
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH, true)
    shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
    assertThrows(IllegalStateException::class.java) { engine.test(draft.json().toString()) }
    shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
    engine.save(draft.json().toString())
    shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
    time = store.find(draft.id)!!.getLong("nextAt")
    engine.fire(draft.id)
    assertEquals(1, store.sessions().size)
    assertEquals("steady", store.sessions().single().getJSONObject("alarm").getString("flashlightMode"))
  }
  @Test fun retrySnoozeAndTaskMuteKeepTheConfiguredFlashlightMode() {
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH, true)
    shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
    val original = alarm().copy(flashlightMode = "flashing", allowMuteWhileSolving = true, dismissTasks = listOf(TaskDraft()))
    fire(original)
    val sessionId = store.sessions().single().getString("sessionId")
    engine.begin(sessionId, "dismiss")
    engine.setMuted(sessionId, true)
    assertEquals("flashing", store.sessions().single().getJSONObject("alarm").getString("flashlightMode"))
    complete()
    val check = due()
    engine.respondWake(check.getString("sessionId"), false)
    assertEquals("flashing", store.sessions().single().getJSONObject("alarm").getString("flashlightMode"))
    complete("snooze")
    time = store.find(original.id)!!.getLong("nextAt")
    engine.fire(original.id)
    assertEquals("flashing", store.sessions().single().getJSONObject("alarm").getString("flashlightMode"))
  }

  @Test fun reminderAppearsAfterTwoMinutesWithLocalizedDueTimeAndCancelAction() {
    fire(alarm()); complete()
    val record = store.checks().single()
    val id = record.getString("sessionId")
    time += 119_999L
    engine.wakeEvent(id, "reminder")
    assertTrue(notifications().isEmpty())
    time += 1
    engine.wakeEvent(id, "reminder")
    val notification = notifications().single()
    assertEquals("Wake-up check at ${DateFormat.getTimeFormat(context).format(Date(record.getLong("dueAt")))}",
      notification.extras.getString(Notification.EXTRA_TITLE))
    assertEquals("Dismiss check", notification.actions.single().title.toString())
    assertEquals("taskalarm://wake/$id/dismiss_early", shadowOf(notification.actions.single().actionIntent).savedIntent.data.toString())
    assertNull(notification.fullScreenIntent)
    assertNull(JSONObject(engine.state()).optJSONObject("wakeCheck"))
    // Foreground state polling must not repeatedly notify or restart the timers.
    engine.state()
    assertEquals(record.getLong("deadlineAt"), store.checks().single().getLong("deadlineAt"))
    assertEquals(1, notifications().size)
  }
  @Test fun earlyDismissCancelsTaskCheckAndAllItsTimersWithoutRinging() {
    fire(alarm().copy(wakeMode = "tasks")); complete()
    val record = store.checks().single()
    time += 120_000L
    engine.wakeEvent(record.getString("sessionId"), "reminder")
    engine.wakeEvent(record.getString("sessionId"), "dismiss_early")
    assertTrue(store.checks().isEmpty())
    assertTrue(notifications().isEmpty())
    assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    time = record.getLong("deadlineAt")
    listOf("reminder", "due", "deadline", "dismiss_early").forEach { engine.wakeEvent(record.getString("sessionId"), it) }
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun staleEarlyDismissCannotBypassTasksAtOrAfterTheDueTime() {
    fire(alarm().copy(wakeMode = "tasks")); complete()
    val record = store.checks().single()
    val id = record.getString("sessionId")
    time = record.getLong("dueAt")
    engine.wakeEvent(id, "dismiss_early")
    val challenge = store.checks().single().getString("challengeId")
    engine.wakeEvent(id, "dismiss_early")
    assertEquals(challenge, store.checks().single().getString("challengeId"))
    assertEquals("Are you out of bed?", notifications().single().extras.getString(Notification.EXTRA_TITLE))
    assertFalse(notifications().single().actions.any { it.title.toString() == "Dismiss check" })
    time = record.getLong("deadlineAt")
    engine.wakeEvent(id, "deadline")
    engine.wakeEvent(id, "dismiss_early")
    assertEquals(1, store.sessions().size)
  }
  @Test fun delayedReminderAtTheDeadlineRingsInsteadOfOfferingEarlyDismiss() {
    fire(alarm()); complete()
    val record = store.checks().single()
    time = record.getLong("deadlineAt")
    engine.wakeEvent(record.getString("sessionId"), "reminder")
    assertTrue(store.checks().isEmpty())
    assertTrue(notifications().isEmpty())
    assertEquals(1, store.sessions().size)
  }
  @Test fun shortChecksDoNotScheduleAnEarlyReminder() {
    for (minutes in 1..2) {
      val original = alarm().copy(wakeDelayMinutes = minutes)
      fire(original); complete()
      val record = store.checks().single()
      assertEquals(2, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
      time = record.getLong("dueAt")
      engine.wakeEvent(record.getString("sessionId"), "reminder")
      assertTrue(notifications().isEmpty())
      engine.delete(original.id)
    }
  }
  @Test fun rebootRestoresFutureReminderAndRepostsItBeforeDueWithoutExtendingDeadline() {
    fire(alarm()); complete()
    val record = store.checks().single()
    val manager = context.getSystemService(AlarmManager::class.java)
    fun clearPlatformTimers() = shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let(manager::cancel) }
    clearPlatformTimers()
    engine = AlarmEngine(context) { time }
    engine.restore(IntentNames.BOOT)
    assertEquals(3, shadowOf(manager).scheduledAlarms.size)
    time += 120_000L
    engine.wakeEvent(record.getString("sessionId"), "reminder")
    context.getSystemService(NotificationManager::class.java).cancelAll()
    clearPlatformTimers()
    engine = AlarmEngine(context) { time }
    engine.restore(IntentNames.BOOT)
    assertEquals(1, notifications().size)
    assertEquals(record.getLong("deadlineAt"), store.checks().single().getLong("deadlineAt"))
    assertEquals("scheduled", store.checks().single().getString("stage"))
  }
  @Test fun upgradeRecoversReminderForAnExistingPendingCheck() {
    fire(alarm()); complete()
    val record = store.checks().single()
    record.remove("reminderAt")
    store.checks(listOf(record))
    time += 120_000L
    engine = AlarmEngine(context) { time }
    engine.state()
    assertEquals(1, notifications().size)
    engine.wakeEvent(record.getString("sessionId"), "dismiss_early")
    assertTrue(store.checks().isEmpty())
  }
  @Test fun noRingsImmediatelyAndTimeoutReusesOriginalDismissTasks() {
    val original = alarm().copy(dismissTasks = listOf(TaskDraft(kind = "math", rounds = 2)))
    fire(original); complete()
    val check = due()
    engine.respondWake(check.getString("sessionId"), false)
    assertTrue(store.checks().isEmpty())
    assertEquals(original.dismissTasks[0].id, store.sessions().single().getJSONObject("alarm").getJSONArray("dismissTasks").getJSONObject(0).getString("id"))
    complete()
    val next = store.checks().single()
    time = next.getLong("deadlineAt")
    engine.wakeEvent(next.getString("sessionId"), "deadline")
    engine.wakeEvent(next.getString("sessionId"), "deadline")
    assertEquals(1, store.sessions().size)
    assertTrue(store.sessions().single().getBoolean("wakeRetry"))
  }
  @Test fun customRetryTasksSurviveSnoozeWithoutReplacingTheSavedDefinition() {
    val original = alarm().copy(dismissTasks = listOf(TaskDraft(kind = "math")), wakeReuseDismissTasks = false, wakeRetryTasks = listOf(TaskDraft(kind = "text")))
    fire(original); complete()
    val check = due()
    engine.respondWake(check.getString("sessionId"), false)
    assertEquals("text", store.sessions().single().getJSONObject("alarm").getJSONArray("dismissTasks").getJSONObject(0).getString("kind"))
    complete("snooze")
    assertEquals("math", store.find(original.id)!!.getJSONArray("dismissTasks").getJSONObject(0).getString("kind"))
    time = store.find(original.id)!!.getLong("nextAt")
    engine.fire(original.id)
    assertEquals("text", store.sessions().single().getJSONObject("alarm").getJSONArray("dismissTasks").getJSONObject(0).getString("kind"))
  }
  @Test fun taskConfirmationRequiresEveryRoundAndRejectsStaleAnswers() {
    fire(alarm().copy(wakeMode = "tasks", wakeTasks = listOf(TaskDraft(kind = "math", rounds = 2))))
    complete()
    val first = due()
    val id = first.getString("sessionId")
    assertFalse(engine.submitWake(id, first.getString("challengeId"), "wrong"))
    assertThrows(IllegalStateException::class.java) { engine.respondWake(id, true) }
    assertTrue(engine.submitWake(id, first.getString("challengeId"), first.getString("answer")))
    assertEquals(1, store.checks().size)
    assertThrows(IllegalStateException::class.java) { engine.submitWake(id, first.getString("challengeId"), first.getString("answer")) }
    val second = store.checks().single()
    assertEquals(2, second.getInt("round"))
    assertFalse(JSONObject(engine.state()).getJSONObject("wakeCheck").has("answer"))
    assertTrue(engine.submitWake(id, second.getString("challengeId"), second.getString("answer")))
    assertTrue(store.checks().isEmpty())
  }
  @Test fun partialTaskCompletionCannotExtendTheDeadline() {
    fire(alarm().copy(wakeMode = "tasks", wakeTasks = listOf(TaskDraft(kind = "text", rounds = 3))))
    complete()
    val check = due()
    engine.submitWake(check.getString("sessionId"), check.getString("challengeId"), check.getString("answer"))
    val next = store.checks().single()
    time = check.getLong("deadlineAt")
    assertThrows(IllegalStateException::class.java) { engine.submitWake(next.getString("sessionId"), next.getString("challengeId"), next.getString("answer")) }
    assertTrue(store.checks().isEmpty())
    assertEquals(1, store.sessions().size)
  }
  @Test fun disablingOrDeletingAnAlarmCancelsPendingChecks() {
    val original = alarm()
    fire(original); complete()
    val record = store.checks().single()
    engine.save(original.copy(enabled = false).json().toString())
    assertTrue(store.checks().isEmpty())
    time = record.getLong("deadlineAt")
    engine.wakeEvent(record.getString("sessionId"), "deadline")
    assertTrue(store.sessions().isEmpty())
    engine.testWake(original.json().toString())
    engine.delete(original.id)
    assertTrue(store.checks().isEmpty())
  }
  @Test fun appRecreationKeepsProgressAndRestoreRetainsDeadline() {
    fire(alarm().copy(wakeMode = "tasks", wakeTasks = listOf(TaskDraft(kind = "math", rounds = 2))))
    complete(); val record = due()
    engine.submitWake(record.getString("sessionId"), record.getString("challengeId"), record.getString("answer"))
    val saved = store.checks().single()
    engine = AlarmEngine(context) { time }
    engine.restore(IntentNames.BOOT)
    val recovered = JSONObject(engine.state()).getJSONObject("wakeCheck")
    assertEquals(saved.getString("challengeId"), recovered.getString("challengeId"))
    assertEquals(saved.getLong("deadlineAt"), recovered.getLong("deadlineAt"))
    assertEquals(2, recovered.getInt("round"))
  }
  @Test fun muteRequiresOptInAndAnActiveTaskAndDoesNotFinishTheAlarm() {
    val original = alarm().copy(dismissTasks = listOf(TaskDraft()), allowMuteWhileSolving = true)
    fire(original)
    val id = store.sessions().single().getString("sessionId")
    assertThrows(IllegalStateException::class.java) { engine.setMuted(id, true) }
    engine.begin(id, "dismiss")
    engine.setMuted(id, true)
    assertTrue(store.sessions().single().getBoolean("muted"))
    assertTrue(store.checks().isEmpty())
    engine.setMuted(id, false)
    assertFalse(store.sessions().single().getBoolean("muted"))
    complete()
    assertEquals(1, store.checks().size)
  }
  @Test fun oldAlarmDefinitionsKeepNewFeaturesDisabled() {
    val legacy = alarm().json()
    listOf("wakeEnabled", "wakeTasks", "wakeRetryTasks", "wakeDelayMinutes", "wakeResponseMinutes", "wakeMode", "wakeReuseDismissTasks", "protectVolume", "allowMuteWhileSolving").forEach { legacy.remove(it) }
    val decoded = AlarmDraft.from(legacy)
    assertFalse(decoded.wakeEnabled)
    assertFalse(decoded.allowMuteWhileSolving)
    assertFalse(decoded.protectVolume)
    assertEquals(5, decoded.wakeDelayMinutes)
    engine.save(legacy.toString())
    time = store.find(decoded.id)!!.getLong("nextAt"); engine.fire(decoded.id); complete()
    assertTrue(store.checks().isEmpty())
  }
  @Test fun snoozingDoesNotStartTheWakeCheckDelay() {
    val original = alarm()
    fire(original); complete("snooze")
    assertTrue(store.checks().isEmpty())
    time = store.find(original.id)!!.getLong("nextAt")
    engine.fire(original.id); complete()
    assertEquals(time + 5 * 60_000L, store.checks().single().getLong("dueAt"))
  }
  @Test fun overdueBootRecoveryUsesAnExactEventInsteadOfStartingMediaFromBoot() {
    fire(alarm()); complete()
    val record = store.checks().single()
    time = record.getLong("deadlineAt") + 1000
    engine = AlarmEngine(context) { time }
    engine.restore(IntentNames.BOOT)
    assertTrue(store.sessions().isEmpty())
    engine.wakeEvent(record.getString("sessionId"), "deadline")
    assertEquals(1, store.sessions().size)
    assertTrue(store.checks().isEmpty())
  }
  @Test fun testsAreBoundedAndMutingCannotBeEnabledWithoutOptIn() {
    val original = alarm().copy(dismissTasks = listOf(TaskDraft()), allowMuteWhileSolving = false)
    engine.test(original.json().toString())
    val id = store.sessions().single().getString("sessionId")
    engine.begin(id, "dismiss")
    assertThrows(IllegalStateException::class.java) { engine.setMuted(id, true) }
    complete()
    assertTrue(store.checks().isEmpty())
    engine.testWake(original.json().toString())
    val check = store.checks().single()
    engine.respondWake(check.getString("sessionId"), false)
    assertTrue(store.sessions().single().getBoolean("test"))
    complete()
    assertTrue(store.checks().isEmpty())
    assertTrue(store.sessions().isEmpty())
  }
  private object IntentNames { const val BOOT = "android.intent.action.BOOT_COMPLETED" }
}
