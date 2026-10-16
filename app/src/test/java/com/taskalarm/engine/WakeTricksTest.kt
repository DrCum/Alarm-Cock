package com.taskalarm.engine

import android.app.Application
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WakeTricksTest {
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private var time = System.currentTimeMillis()
  @Before fun setup() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    engine = AlarmEngine(context) { time }; store = AlarmStore(context)
  }
  private fun fire(alarm: AlarmDraft) {
    engine.save(alarm.json().toString()); time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
  }
  private fun answer(session: JSONObject) = engine.submit(session.getString("sessionId"), session.getString("challengeId"), session.getString("answer"))

  @Test fun memoryLengthsFollowDifficultyAndWrongOrStaleSequencesCannotAdvance() {
    for ((difficulty, length) in listOf("easy" to 3, "medium" to 5, "hard" to 7)) {
      val generated = Tasks.generate("memory", difficulty)
      assertEquals(length, generated.answer.length)
      assertTrue(generated.answer.all { it in '1'..'4' })
    }
    engine.test(AlarmDraft(dismissTasks = listOf(TaskDraft(kind = "memory", rounds = 2))).json().toString())
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    val first = store.sessions().single()
    assertFalse(engine.submit(first.getString("sessionId"), first.getString("challengeId"), "000"))
    assertEquals(1, store.sessions().single().getInt("round"))
    assertFalse(JSONObject(engine.state()).getJSONObject("active").has("answer"))
    assertEquals(first.getString("answer"), (0 until first.getJSONArray("memorySequence").length()).joinToString("") { first.getJSONArray("memorySequence").getInt(it).toString() })
    answer(first)
    assertThrows(IllegalStateException::class.java) { answer(first) }
    assertEquals(2, store.sessions().single().getInt("round"))
    answer(store.sessions().single())
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun snoozeCountsOnlyAfterItsTasksAndLimitSurvivesWakeUpRetry() {
    val alarm = AlarmDraft(days = emptySet(), maxSnoozes = 1, dismissTasks = emptyList(), snoozeTasks = listOf(TaskDraft()), wakeEnabled = true)
    fire(alarm)
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    assertEquals(0, store.sessions().single().getJSONObject("alarm").getInt("snoozeCount"))
    answer(store.sessions().single())
    assertEquals(1, store.find(alarm.id)!!.getInt("snoozeCount"))
    time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    val id = store.sessions().single().getString("sessionId")
    assertThrows(IllegalStateException::class.java) { engine.begin(id, "snooze") }
    engine.begin(id, "dismiss")
    val check = store.checks().single(); time = check.getLong("dueAt")
    engine.wakeEvent(check.getString("sessionId"), "due")
    engine.respondWake(check.getString("sessionId"), false)
    val retry = store.sessions().single()
    assertEquals(1, retry.getJSONObject("alarm").getInt("snoozeCount"))
    assertThrows(IllegalStateException::class.java) { engine.begin(retry.getString("sessionId"), "snooze") }
  }
  @Test fun nextRegularOccurrenceGetsAFreshSnoozeAllowance() {
    val alarm = AlarmDraft(days = (1..7).toSet(), maxSnoozes = 1, dismissTasks = emptyList())
    fire(alarm)
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    assertEquals(1, store.sessions().single().getJSONObject("alarm").getInt("snoozeCount"))
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    assertEquals(0, store.sessions().single().getJSONObject("alarm").getInt("snoozeCount"))
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun restoringPastAMissedSnoozeResetsTheNextRegularOccurrencesAllowance() {
    val alarm = AlarmDraft(days = (1..7).toSet(), maxSnoozes = 1, dismissTasks = emptyList())
    fire(alarm)
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    time = store.find(alarm.id)!!.getLong("nextAt") + 60_000
    engine.restore("android.intent.action.BOOT_COMPLETED")
    val recovered = store.find(alarm.id)!!
    assertFalse(recovered.optBoolean("snoozed"))
    assertFalse(recovered.optBoolean("wakeRetry"))
    assertEquals(0, recovered.getInt("snoozeCount"))
    time = recovered.getLong("nextAt"); engine.fire(alarm.id)
    engine.begin(store.sessions().single().getString("sessionId"), "snooze")
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun shufflingPreservesEveryTaskRoundAndTheSavedConfiguration() {
    val configured = listOf(TaskDraft(kind = "memory", rounds = 2), TaskDraft(kind = "math"), TaskDraft(kind = "text"))
    val alarm = AlarmDraft(days = emptySet(), shuffleTasks = true, dismissTasks = configured)
    fire(alarm)
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    val presented = store.sessions().single().getJSONObject("alarm").getJSONArray("dismissTasks")
    assertEquals(configured.map { it.id }.toSet(), (0 until presented.length()).map { presented.getJSONObject(it).getString("id") }.toSet())
    var answers = 0
    while (store.sessions().isNotEmpty()) { answer(store.sessions().single()); answers++ }
    assertEquals(4, answers)
    assertEquals(configured.map { it.id }, AlarmDraft.from(store.find(alarm.id)!!).dismissTasks.map { it.id })
  }
  @Test fun arrangingTasksUsesCopiesAndNeverMutatesTheSource() {
    val source = JSONArray(listOf(TaskDraft().json(), TaskDraft(kind = "memory").json()))
    val arranged = TaskOrder.arrange(source, true)
    arranged.getJSONObject(0).put("rounds", 9)
    assertEquals(1, source.getJSONObject(0).getInt("rounds"))
    assertEquals(1, source.getJSONObject(1).getInt("rounds"))
  }
  @Test fun legacySettingsRemainOptionalAndInvalidLimitsFailBeforeRinging() {
    val legacy = AlarmDraft().json().apply { listOf("maxSnoozes", "shuffleTasks", "vibrate").forEach { remove(it) } }
    val parsed = AlarmDraft.from(legacy)
    assertEquals(0, parsed.maxSnoozes); assertFalse(parsed.shuffleTasks); assertFalse(parsed.vibrate)
    assertThrows(IllegalArgumentException::class.java) { engine.test(AlarmDraft(maxSnoozes = 11).json().toString()) }
    assertThrows(IllegalArgumentException::class.java) { AlarmDraft(maxSnoozes = -1).validate() }
    assertTrue(store.sessions().isEmpty())
  }
  @Test fun inactiveWakeGameDraftsDoNotBlockSavingButMustBeValidBeforeUse() {
    val invalidGame = TaskDraft(kind = "breakout", gameSeconds = 0)
    val draft = AlarmDraft(wakeEnabled = false, wakeMode = "tasks", wakeTasks = listOf(invalidGame), wakeRetryTasks = listOf(invalidGame))
    draft.validate(); engine.save(draft.json().toString())
    val enabled = draft.copy(wakeEnabled = true)
    assertThrows(IllegalArgumentException::class.java) { enabled.validate() }
    assertThrows(IllegalArgumentException::class.java) { engine.save(enabled.json().toString()) }
    val yesNo = enabled.copy(wakeMode = "yes_no", wakeReuseDismissTasks = true)
    yesNo.validate(); engine.save(yesNo.json().toString())
    assertTrue(store.sessions().isEmpty())
  }
}
