package com.taskalarm.engine

import android.app.Application
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskQueueTest {
  private lateinit var context: Application
  private lateinit var store: AlarmStore
  private lateinit var engine: AlarmEngine
  private var time = 0L
  @Before fun setup() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    time = Instant.parse("2026-10-17T06:00:00Z").toEpochMilli()
    store = AlarmStore(context); engine = AlarmEngine(context) { time }
  }
  private fun state() = JSONObject(engine.state())
  private fun fire(alarm: AlarmDraft): JSONObject {
    engine.save(alarm.json().toString()); time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    return store.sessions().last()
  }
  private fun finishAlarm() {
    val id = store.sessions().first().getString("sessionId")
    engine.begin(id, "dismiss")
    while(store.sessions().firstOrNull()?.getString("sessionId") == id) {
      val record = store.sessions().first()
      engine.submit(id, record.getString("challengeId"), record.getString("answer"))
    }
  }
  private fun pendingCheck(): JSONObject {
    fire(AlarmDraft(days = emptySet(), dismissTasks = emptyList(), wakeEnabled = true, wakeResponseMinutes = 1))
    finishAlarm(); return store.checks().single()
  }
  private fun beginMath(record: JSONObject) {
    engine.begin(record.getString("sessionId"), "dismiss")
    val first = store.sessions().first()
    engine.submit(first.getString("sessionId"), first.getString("challengeId"), first.getString("answer"))
  }
  @Test fun incomingAlarmAndCheckKeepTheCurrentSessionChallengeAndCompletedRounds() {
    val check = pendingCheck()
    val first = fire(AlarmDraft(label = "First", hour = "07", minute = "01", days = emptySet(), dismissTasks = listOf(TaskDraft(rounds = 3))))
    beginMath(first)
    val progress = state().getJSONObject("active")
    fire(AlarmDraft(label = "Second", hour = "07", minute = "02", days = emptySet()))
    time = check.getLong("dueAt"); engine.wakeEvent(check.getString("sessionId"), "due")
    val after = state()
    assertEquals(progress.getString("sessionId"), after.getJSONObject("active").getString("sessionId"))
    assertEquals(progress.getString("challengeId"), after.getJSONObject("active").getString("challengeId"))
    assertEquals(2, after.getJSONObject("active").getInt("round"))
    assertEquals(2, after.getJSONArray("pendingTasks").length())
    assertEquals("Second", after.getJSONArray("pendingTasks").getJSONObject(0).getString("label"))
    assertEquals("queued", store.checks().single().getString("stage"))
    finishAlarm()
    assertEquals("Second", state().getJSONObject("active").getJSONObject("alarm").getString("label"))
    finishAlarm()
    assertEquals(check.getString("sessionId"), state().getJSONObject("wakeCheck").getString("sessionId"))
  }
  @Test fun aNewAlarmAndShorterCheckDoNotPreemptAnInProgressWakeTask() {
    engine.testWake(AlarmDraft(label = "Current check", wakeEnabled = true, wakeMode = "tasks", wakeTasks = listOf(TaskDraft(rounds = 3))).json().toString())
    val first = store.checks().single()
    engine.submitWake(first.getString("sessionId"), first.getString("challengeId"), first.getString("answer"))
    val progress = store.checks().single()
    // A later check with an earlier deadline used to replace the task selected by the UI.
    time += 1000
    engine.testWake(AlarmDraft(label = "Short check", wakeEnabled = true, wakeResponseMinutes = 1).json().toString())
    val alarm = AlarmDraft(label = "Incoming alarm", hour = "06", minute = "01", days = emptySet())
    fire(alarm)
    val after = state()
    assertTrue(after.isNull("active"))
    assertEquals(progress.getString("sessionId"), after.getJSONObject("wakeCheck").getString("sessionId"))
    assertEquals(progress.getString("challengeId"), after.getJSONObject("wakeCheck").getString("challengeId"))
    assertEquals(2, after.getJSONObject("wakeCheck").getInt("round"))
    assertEquals(2, after.getJSONArray("pendingTasks").length())
    assertEquals("wake", after.getJSONArray("pendingTasks").getJSONObject(0).getString("kind"))
  }
  @Test fun queuedChecksWaitWithoutExpiringAndGetOneFullWindowWhenPromoted() {
    val check = pendingCheck()
    fire(AlarmDraft(label = "Blocking alarm", hour = "07", minute = "01", days = emptySet()))
    val id = check.getString("sessionId")
    time = check.getLong("dueAt"); engine.wakeEvent(id, "due")
    time = check.getLong("deadlineAt") + 20 * 60_000L
    engine.wakeEvent(id, "deadline"); state()
    assertEquals(1, store.sessions().size)
    assertEquals("queued", store.checks().single().getString("stage"))
    assertThrows(IllegalStateException::class.java) { engine.respondWake(id, true) }
    finishAlarm()
    val promoted = store.checks().single()
    assertEquals("waiting", promoted.getString("stage"))
    assertEquals(time + 60_000, promoted.getLong("deadlineAt"))
    time += 20_000; state(); engine.wakeEvent(id, "due")
    assertEquals(promoted.getLong("deadlineAt"), store.checks().single().getLong("deadlineAt"))
    time = promoted.getLong("deadlineAt"); engine.wakeEvent(id, "deadline")
    assertTrue(store.checks().isEmpty()); assertEquals(1, store.sessions().size)
  }
  @Test fun queueAndPartialProgressSurviveProcessAndBootRecovery() {
    val check = pendingCheck()
    val first = fire(AlarmDraft(hour = "07", minute = "01", days = emptySet(), dismissTasks = listOf(TaskDraft(rounds = 3))))
    beginMath(first)
    time = check.getLong("dueAt"); engine.wakeEvent(check.getString("sessionId"), "due")
    val before = state().getJSONObject("active")
    time += 10 * 60_000L
    engine = AlarmEngine(context) { time }; engine.restore("android.intent.action.BOOT_COMPLETED")
    val recovered = state()
    assertEquals(before.getString("challengeId"), recovered.getJSONObject("active").getString("challengeId"))
    assertEquals(2, recovered.getJSONObject("active").getInt("round"))
    assertEquals(1, recovered.getJSONArray("pendingTasks").length())
    finishAlarm()
    assertEquals(check.getString("sessionId"), state().getJSONObject("wakeCheck").getString("sessionId"))
  }
  @Test fun activeWakeDeadlinesStillExpireWhileOtherAlarmsAreQueued() {
    engine.testWake(AlarmDraft(wakeEnabled = true, wakeResponseMinutes = 1).json().toString())
    val check = store.checks().single()
    fire(AlarmDraft(label = "Next alarm", hour = "06", minute = "01", days = emptySet()))
    time = check.getLong("deadlineAt"); engine.wakeEvent(check.getString("sessionId"), "deadline")
    assertTrue(store.checks().isEmpty())
    val after = state()
    assertEquals("Next alarm", after.getJSONObject("active").getJSONObject("alarm").getString("label"))
    assertEquals(1, after.getJSONArray("pendingTasks").length())
  }
  @Test fun cancelledChecksDisappearWithoutResettingTheCurrentAlarm() {
    val original = AlarmDraft(days = emptySet(), dismissTasks = emptyList(), wakeEnabled = true)
    fire(original); finishAlarm()
    val check = store.checks().single()
    fire(AlarmDraft(hour = "07", minute = "01", days = emptySet(), dismissTasks = listOf(TaskDraft(rounds = 2))))
    engine.begin(store.sessions().first().getString("sessionId"), "dismiss")
    time = check.getLong("dueAt"); engine.wakeEvent(check.getString("sessionId"), "due")
    val before = state().getJSONObject("active")
    engine.delete(original.id)
    val after = state()
    assertEquals(before.getString("challengeId"), after.getJSONObject("active").getString("challengeId"))
    assertEquals(0, after.getJSONArray("pendingTasks").length())
  }
  @Test fun anInterruptedWindowHandoffRecoversWithoutGrantingExtraResponseTime() {
    val check = pendingCheck()
    fire(AlarmDraft(hour = "07", minute = "01", days = emptySet()))
    time = check.getLong("dueAt"); engine.wakeEvent(check.getString("sessionId"), "due")
    // Model a process interruption after the chosen response window was committed but before activation.
    store.sessions(emptyList())
    val queued = store.checks().single().put("responseStartedAt", time).put("deadlineAt", time + 60_000)
    store.checks(listOf(queued))
    time += 20_000; engine = AlarmEngine(context) { time }
    val recovered = state().getJSONObject("wakeCheck")
    assertEquals(queued.getLong("deadlineAt"), recovered.getLong("deadlineAt"))
    assertEquals("waiting", recovered.getString("stage"))
  }
  @Test fun theNextOccurrenceOfTheSameAlarmDoesNotCancelItsActiveWakeTask() {
    val source = AlarmDraft(days = (1..7).toSet(), dismissTasks = emptyList(), wakeEnabled = true, wakeDelayMinutes = 1,
      wakeResponseMinutes = 1440, wakeMode = "tasks", wakeTasks = listOf(TaskDraft(rounds = 3)))
    fire(source); finishAlarm()
    val check = store.checks().single(); time = check.getLong("dueAt")
    engine.wakeEvent(check.getString("sessionId"), "due")
    val first = store.checks().single()
    engine.submitWake(first.getString("sessionId"), first.getString("challengeId"), first.getString("answer"))
    val progress = store.checks().single()
    time = store.find(source.id)!!.getLong("nextAt"); engine.fire(source.id)
    val after = state()
    assertEquals(progress.getString("challengeId"), after.getJSONObject("wakeCheck").getString("challengeId"))
    assertEquals(2, after.getJSONObject("wakeCheck").getInt("round"))
    assertEquals(1, after.getJSONArray("pendingTasks").length())
  }
}
