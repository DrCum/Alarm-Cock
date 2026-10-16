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
class GameFlowTest {
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private val task = TaskDraft(kind = "breakout", gameSeconds = 5)
  @Before fun setup() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    context.getSharedPreferences("task_alarm_game_scores_v1", 0).edit().clear().commit()
    engine = AlarmEngine(context); store = AlarmStore(context)
  }
  private fun play(record: JSONObject, wake: Boolean = false) {
    val id = record.getString("sessionId"); val challenge = record.getString("challengeId")
    engine.gameFrame(id, challenge, wake, .2f)
    repeat(50) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      engine.gameFrame(id, challenge, wake, if (it % 2 == 0) .8f else .2f)
    }
  }
  @Test fun nativeGameCompletionDismissesOnlyAfterActiveTimeAndRejectsAnswerBypasses() {
    engine.test(AlarmDraft(dismissTasks = listOf(task)).json().toString())
    val id = store.sessions().single().getString("sessionId")
    engine.begin(id, "dismiss")
    val record = store.sessions().single()
    assertThrows(IllegalStateException::class.java) { engine.submit(id, record.getString("challengeId"), "complete") }
    play(record)
    assertTrue(store.sessions().isEmpty())
    assertEquals(1, JSONObject(engine.state()).getJSONObject("gameStats").getInt("breakoutCompleted"))
    assertThrows(IllegalStateException::class.java) { engine.gameFrame(id, record.getString("challengeId"), false) }
  }
  @Test fun everyGameRoundAndFollowingTaskMustBeCompletedInOrder() {
    engine.test(AlarmDraft(dismissTasks = listOf(task.copy(rounds = 2), TaskDraft(kind = "math"))).json().toString())
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    val first = store.sessions().single()
    play(first)
    assertEquals(2, store.sessions().single().getInt("round"))
    play(store.sessions().single())
    val math = store.sessions().single()
    assertEquals("math", math.getString("kind"))
    assertThrows(IllegalStateException::class.java) { engine.gameFrame(first.getString("sessionId"), first.getString("challengeId"), false) }
    engine.submit(math.getString("sessionId"), math.getString("challengeId"), math.getString("answer"))
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun gameCanConfirmAWakeCheckAndCannotUseTheYesShortcut() {
    engine.testWake(AlarmDraft(wakeEnabled = true, wakeMode = "tasks", wakeTasks = listOf(task)).json().toString())
    val record = store.checks().single()
    assertThrows(IllegalStateException::class.java) { engine.respondWake(record.getString("sessionId"), true) }
    play(record, wake = true)
    assertTrue(store.checks().isEmpty())
  }
  @Test fun oldAlarmsKeepMovingDismissOffAndInvalidDurationsFailBeforeRinging() {
    val legacy = AlarmDraft().json().apply { remove("randomDismiss") }
    assertFalse(AlarmDraft.from(legacy).randomDismiss)
    val draft = AlarmDraft(randomDismiss = true, dismissTasks = listOf(task.copy(gameSeconds = 0)))
    assertThrows(IllegalArgumentException::class.java) { draft.validate() }
    assertThrows(IllegalArgumentException::class.java) { engine.test(draft.json().toString()) }
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun playingCannotExtendAWakeCheckDeadline() {
    var wallTime = System.currentTimeMillis()
    engine = AlarmEngine(RuntimeEnvironment.getApplication()) { wallTime }
    engine.testWake(AlarmDraft(wakeEnabled = true, wakeResponseMinutes = 1, wakeMode = "tasks", wakeTasks = listOf(task.copy(gameSeconds = 120))).json().toString())
    val record = store.checks().single()
    engine.gameFrame(record.getString("sessionId"), record.getString("challengeId"), true, .2f)
    ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
    wallTime += 61_000
    assertThrows(IllegalStateException::class.java) { engine.gameFrame(record.getString("sessionId"), record.getString("challengeId"), true, .8f) }
    assertTrue(store.checks().isEmpty())
    assertEquals(1, store.sessions().size)
    assertTrue(store.sessions().single().getBoolean("wakeRetry"))
  }
}
