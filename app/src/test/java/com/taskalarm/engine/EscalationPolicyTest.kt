package com.taskalarm.engine

import android.app.Application
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.AlarmTone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EscalationPolicyTest {
  private val start = AlarmClockStamp(1_000_000, 10_000, 2)
  private fun record(alarm: AlarmDraft = AlarmDraft(escalationEnabled = true)) = JSONObject()
    .put("alarm", alarm.json()).put("baseAlarm", alarm.json()).put("action", JSONObject.NULL)
  private fun at(milliseconds: Long) = start.copy(wall = start.wall + milliseconds, elapsed = start.elapsed + milliseconds)

  @Test fun unansweredAlarmTriggersAtTheConfiguredBoundaryAndLatchesItsCycle() {
    val record = record()
    EscalationPolicy.refresh(record, start)
    assertFalse(EscalationPolicy.refresh(record, at(119_999)))
    assertFalse(record.optBoolean("escalated"))
    assertTrue(EscalationPolicy.refresh(record, at(120_000)))
    assertEquals("unanswered", record.getString("escalationReason"))
    assertTrue(record.getJSONObject("baseAlarm").getBoolean("escalationLatched"))
    assertFalse(EscalationPolicy.refresh(record, at(150_000)))
    assertEquals(120_000L + start.elapsed, record.getLong("escalatedElapsed"))
  }
  @Test fun selectingAnActionPreventsTheUnansweredTriggerAndOffMeansOff() {
    for (action in listOf("dismiss", "snooze")) {
      val record = record(); EscalationPolicy.refresh(record, start)
      record.put("action", action)
      EscalationPolicy.refresh(record, at(900_000))
      assertFalse(record.optBoolean("escalated"))
    }
    val disabled = record(AlarmDraft(escalationEnabled = false))
    disabled.getJSONObject("alarm").put("snoozeCount", 9)
    assertFalse(EscalationPolicy.refresh(disabled, at(900_000)))
    assertFalse(disabled.optBoolean("escalated"))
  }
  @Test fun twoCompletedSnoozesTriggerImmediatelyAndOneDoesNot() {
    val record = record().put("action", "dismiss")
    record.getJSONObject("alarm").put("snoozeCount", 1)
    EscalationPolicy.refresh(record, start)
    assertFalse(record.optBoolean("escalated"))
    record.getJSONObject("alarm").put("snoozeCount", 2)
    assertTrue(EscalationPolicy.refresh(record, start))
    assertEquals("snoozes", record.getString("escalationReason"))
  }
  @Test fun everyToneGetsFortyFiveSecondsAndClockEditsDoNotChangeItsSlot() {
    val record = record(); record.getJSONObject("alarm").put("escalationLatched", true)
    EscalationPolicy.refresh(record, start)
    assertEquals(0L, EscalationPolicy.hop(record, at(44_999)))
    assertEquals(1L, EscalationPolicy.untilNextTone(record, at(44_999)))
    assertEquals(1L, EscalationPolicy.hop(record, at(45_000)))
    assertEquals(2L, EscalationPolicy.hop(record, at(90_000)))
    assertEquals(3L, EscalationPolicy.hop(record, at(135_000)))
    assertEquals(1L, EscalationPolicy.hop(record, at(45_000).copy(wall = 0)))
  }
  @Test fun recoveryRetainsTheTriggerAndUsesWallTimeOnlyAcrossReboot() {
    val record = record(); EscalationPolicy.refresh(record, start)
    EscalationPolicy.refresh(record, at(120_000))
    val recovered = JSONObject(record.toString())
    EscalationPolicy.refresh(recovered, at(185_000))
    assertEquals(1L, EscalationPolicy.hop(recovered, at(185_000)))
    assertEquals(1L, EscalationPolicy.hop(recovered, AlarmClockStamp(start.wall + 185_000, 500, 3)))
    val notYet = record(); EscalationPolicy.refresh(notYet, start)
    EscalationPolicy.refresh(notYet, start.copy(wall = start.wall + 3_600_000, elapsed = start.elapsed + 1000))
    assertFalse(notYet.optBoolean("escalated"))
  }
  @Test fun invalidActivePlaylistsFailBeforeRingingButLegacyAlarmsStayOff() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    val engine = AlarmEngine(context)
    val original = AlarmDraft().json().apply { listOf("escalationEnabled", "escalationAfterMinutes", "escalationTones").forEach(::remove) }
    val legacy = AlarmDraft.from(original)
    assertFalse(legacy.escalationEnabled); assertEquals(2, legacy.escalationAfterMinutes)
    for (alarm in listOf(
      AlarmDraft(escalationEnabled = true, escalationAfterMinutes = 0),
      AlarmDraft(escalationEnabled = true, escalationTones = emptyList()),
      AlarmDraft(escalationEnabled = true, escalationTones = listOf(AlarmTone("https://example.com/tone.wav", "Stream"))),
      AlarmDraft(escalationEnabled = true, escalationTones = listOf(AlarmTone("builtin:../bad", "Bad")))
    )) assertThrows(IllegalArgumentException::class.java) { engine.test(alarm.json().toString()) }
    assertTrue(AlarmStore(context).sessions().isEmpty())
    val valid = AlarmDraft(escalationEnabled = true)
    assertEquals(valid.escalationTones, AlarmDraft.from(valid.json()).escalationTones)
  }
  @Test fun escalationTestDoesNotChangeTheSavedAlarmOrCreateFollowUpChecks() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    val engine = AlarmEngine(context); val store = AlarmStore(context)
    val alarm = AlarmDraft(escalationEnabled = true, wakeEnabled = true, dismissTasks = emptyList())
    engine.save(alarm.json().toString())
    engine.testEscalation(alarm.json().toString())
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    assertTrue(store.sessions().isEmpty()); assertTrue(store.checks().isEmpty())
    assertFalse(store.find(alarm.id)!!.optBoolean("escalationLatched"))
    assertTrue(store.find(alarm.id)!!.getBoolean("enabled"))
  }
  @Test fun snoozeAndWakeRetriesCarryEscalationButNewRegularOccurrencesResetIt() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    var time = System.currentTimeMillis()
    val engine = AlarmEngine(context) { time }; val store = AlarmStore(context)
    val alarm = AlarmDraft(days = (1..7).toSet(), escalationEnabled = true, wakeEnabled = true, dismissTasks = emptyList())
    engine.save(alarm.json().toString()); time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    repeat(2) {
      engine.begin(store.sessions().single().getString("sessionId"), "snooze")
      time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    }
    var session = store.sessions().single()
    EscalationPolicy.refresh(session, start)
    assertTrue(session.getBoolean("escalated")); store.sessions(listOf(session))
    engine.begin(session.getString("sessionId"), "dismiss")
    val check = store.checks().single(); time = check.getLong("dueAt"); engine.wakeEvent(check.getString("sessionId"), "due")
    engine.respondWake(check.getString("sessionId"), false)
    session = store.sessions().single()
    assertTrue(session.getJSONObject("alarm").getBoolean("escalationLatched"))
    engine.begin(session.getString("sessionId"), "dismiss")
    time = store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    session = store.sessions().single()
    assertFalse(session.getJSONObject("alarm").optBoolean("escalationLatched"))
    assertEquals(0, session.getJSONObject("alarm").getInt("snoozeCount"))
  }
}
