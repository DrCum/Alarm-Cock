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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhotoTaskTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private var time = 0L
  private val photo = TaskDraft(kind = "photo")
  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    time = System.currentTimeMillis(); store = AlarmStore(context); engine = AlarmEngine(context) { time }
  }
  private fun start(alarm: AlarmDraft) {
    engine.test(alarm.json().toString())
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
  }
  private fun receipt(session: JSONObject = store.sessions().single(), wake: Boolean = false) =
    engine.startPhotoCapture(session.getString("sessionId"), session.getString("challengeId"), wake)
  private fun wakeCheck(): JSONObject {
    engine.testWake(AlarmDraft(wakeEnabled = true, wakeMode = "tasks", wakeTasks = listOf(photo),
      dismissTasks = listOf(photo), wakeResponseMinutes = 1).json().toString())
    return store.checks().single()
  }

  @Test fun everyPhotoRoundNeedsAFreshCaptureAndFollowingTasksStillApply() {
    start(AlarmDraft(dismissTasks = listOf(photo.copy(rounds = 2), TaskDraft())))
    val first = store.sessions().single()
    assertThrows(IllegalStateException::class.java) { engine.submit(first.getString("sessionId"), first.getString("challengeId"), "") }
    val token = receipt(); engine.completePhotoCapture(token)
    assertEquals(2, store.sessions().single().getInt("round"))
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(token) }
    assertThrows(IllegalStateException::class.java) { receipt(first) }
    engine.completePhotoCapture(receipt())
    val math = store.sessions().single()
    assertEquals("math", math.getString("kind"))
    assertThrows(IllegalStateException::class.java) { receipt(math) }
    engine.submit(math.getString("sessionId"), math.getString("challengeId"), math.getString("answer"))
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun cancelledExpiredAndLostCapturesLeaveTheTaskIncomplete() {
    start(AlarmDraft(dismissTasks = listOf(photo)))
    val cancelled = receipt(); engine.cancelPhotoCapture(cancelled)
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(cancelled) }
    val expired = receipt(); ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(expired) }
    val lost = receipt(); engine = AlarmEngine(context) { time }
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(lost) }
    assertEquals(1, store.sessions().single().getInt("round"))
    engine.completePhotoCapture(receipt())
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun switchingActionsInvalidatesTheOldCameraResult() {
    start(AlarmDraft(dismissTasks = listOf(photo), snoozeTasks = listOf(photo)))
    val token = receipt()
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(token) }
    assertEquals("snooze", store.sessions().single().getString("action"))
    engine.completePhotoCapture(receipt())
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun aCompletedWakePhotoConfirmsTheCheckWithoutStartingAnotherAlarm() {
    val check = wakeCheck()
    assertThrows(IllegalStateException::class.java) { engine.submitWake(check.getString("sessionId"), check.getString("challengeId"), "photo") }
    engine.completePhotoCapture(receipt(check, true))
    assertTrue(store.checks().isEmpty()); assertTrue(store.sessions().isEmpty())
  }
  @Test fun aLateWakePhotoCannotPreventTheDeadlineRetry() {
    val check = wakeCheck(); val token = receipt(check, true)
    time = check.getLong("deadlineAt")
    assertThrows(IllegalStateException::class.java) { engine.completePhotoCapture(token) }
    assertTrue(store.checks().isEmpty()); assertEquals(1, store.sessions().size)
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    assertEquals("photo", store.sessions().single().getString("kind"))
  }
  @Test fun aRealSnoozeWaitsForThePhotoBeforeSchedulingAndCountingIt() {
    val alarm = AlarmDraft(days = emptySet(), snoozeTasks = listOf(photo), snoozeMinutes = 15, maxSnoozes = 1)
    engine.save(alarm.json().toString()); time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    assertEquals(0, store.sessions().single().getJSONObject("alarm").getInt("snoozeCount"))
    engine.completePhotoCapture(receipt())
    assertTrue(store.sessions().isEmpty())
    val saved = store.find(alarm.id)!!
    assertEquals(time + 15 * 60_000L, saved.getLong("nextAt")); assertEquals(1, saved.getInt("snoozeCount"))
  }
}
