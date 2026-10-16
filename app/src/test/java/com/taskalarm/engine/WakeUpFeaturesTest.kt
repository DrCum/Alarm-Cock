package com.taskalarm.engine

import android.app.Application
import com.taskalarm.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WakeUpFeaturesTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private var time=Instant.parse("2026-10-16T06:00:00Z").toEpochMilli()
  @Before fun setup() {
    context=RuntimeEnvironment.getApplication()
    for(prefs in listOf("task_alarm_v1","task_alarm_health","task_alarm_routines_v1")) context.getSharedPreferences(prefs,0).edit().clear().commit()
    engine=AlarmEngine(context) { time }; store=AlarmStore(context)
  }
  private fun fire(alarm: AlarmDraft) { engine.save(alarm.json().toString()); time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id) }
  private fun answer() { val r=store.sessions().single(); assertTrue(engine.submit(r.getString("sessionId"),r.getString("challengeId"),r.getString("answer"))) }
  private fun begin(action: String) = engine.begin(store.sessions().single().getString("sessionId"),action)

  @Test fun longPausesAndSkipUseOneConsistentCalculation() {
    val now=ZonedDateTime.parse("2026-10-16T06:00Z[UTC]")
    val pause=Instant.parse("2026-12-20T07:00:00Z").toEpochMilli()
    val first=Timing.next(7,0,setOf(1,2,3,4,5),now,pause)
    assertEquals(Instant.parse("2026-12-21T07:00:00Z").toEpochMilli(),first)
    assertEquals(Instant.parse("2026-12-22T07:00:00Z").toEpochMilli(),Timing.next(7,0,setOf(1,2,3,4,5),now,pause,first))
    val boundary=Instant.parse("2026-12-21T07:00:00Z").toEpochMilli()
    assertEquals(boundary,Timing.next(7,0,setOf(1),now,boundary))
  }
  @Test fun skipRestoreUndoAndStaleBroadcastDoNotLoseTheNextRing() {
    val alarm=AlarmDraft(days=(1..7).toSet())
    engine.save(alarm.json().toString())
    val original=store.find(alarm.id)!!.getLong("nextAt")
    engine.skipNext(alarm.id)
    val next=store.find(alarm.id)!!.getLong("nextAt")
    assertTrue(next>original)
    engine.restore("android.intent.action.BOOT_COMPLETED")
    assertEquals(next,store.find(alarm.id)!!.getLong("nextAt"))
    time=original; engine.fire(alarm.id); assertTrue(store.sessions().isEmpty())
    time-=60_000; engine.skipNext(alarm.id,true)
    assertEquals(original,store.find(alarm.id)!!.getLong("nextAt"))
    time=original; engine.fire(alarm.id); assertEquals(1,store.sessions().size)
  }
  @Test fun oneTimeSkipDisablesAndUndoRestoresIt() {
    val alarm=AlarmDraft(days=emptySet())
    engine.save(alarm.json().toString()); val original=store.find(alarm.id)!!.getLong("nextAt")
    engine.skipNext(alarm.id); assertFalse(store.find(alarm.id)!!.getBoolean("enabled"))
    engine.skipNext(alarm.id,true); assertEquals(original,store.find(alarm.id)!!.getLong("nextAt"))
  }
  @Test fun pauseOnlyEditKeepsAnActiveSnoozeAndWakeCycle() {
    val alarm=AlarmDraft(days=(1..7).toSet(),dismissTasks=emptyList(),wakeEnabled=true)
    fire(alarm); begin("snooze")
    val pending=store.find(alarm.id)!!; val ringAt=pending.getLong("nextAt")
    engine.save(AlarmDraft.from(pending).copy(pauseUntil=time+30*86_400_000L).json().toString())
    assertEquals(ringAt,store.find(alarm.id)!!.getLong("nextAt")); assertTrue(store.find(alarm.id)!!.getBoolean("snoozed"))
    time=ringAt; engine.fire(alarm.id); begin("dismiss")
    val check=store.checks().single(); val saved=AlarmDraft.from(store.find(alarm.id)!!)
    engine.save(saved.copy(pauseUntil=time+40*86_400_000L).json().toString())
    assertEquals(check.getString("sessionId"),store.checks().single().getString("sessionId"))
  }
  @Test fun difficultyUsesCompletedCountWithoutCompoundingAcrossActionSwitches() {
    val alarm=AlarmDraft(days=(1..7).toSet(),progressiveDifficulty=true,dismissTasks=listOf(TaskDraft()),snoozeTasks=listOf(TaskDraft(kind="text")))
    fire(alarm); begin("snooze")
    assertEquals("easy",store.sessions().single().getJSONObject("alarm").getJSONArray("snoozeTasks").getJSONObject(0).getString("difficulty"))
    answer(); time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    repeat(3) { begin("dismiss"); begin("snooze") }
    val current=store.sessions().single().getJSONObject("alarm")
    assertEquals("medium",current.getJSONArray("dismissTasks").getJSONObject(0).getString("difficulty"))
    assertEquals("medium",current.getJSONArray("snoozeTasks").getJSONObject(0).getString("difficulty"))
    assertEquals("easy",AlarmDraft.from(store.find(alarm.id)!!).dismissTasks.single().difficulty)
    answer(); time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id); begin("dismiss")
    assertEquals("hard",store.sessions().single().getJSONObject("alarm").getJSONArray("dismissTasks").getJSONObject(0).getString("difficulty"))
  }
  @Test fun randomDelayIsPersistedAcrossRestoresAndCheckDifficultyIsNotAppliedTwice() {
    val alarm=AlarmDraft(days=emptySet(),dismissTasks=emptyList(),wakeEnabled=true,wakeRandom=true,wakeMinMinutes=3,wakeMaxMinutes=7,progressiveDifficulty=true,wakeMode="tasks",wakeTasks=listOf(TaskDraft()))
    fire(alarm); begin("snooze"); time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id); begin("dismiss")
    val record=store.checks().single()
    assertTrue(record.getLong("dueAt")-time in 180_000L..420_000L)
    repeat(3) { AlarmEngine(context) {time}.restore("android.intent.action.BOOT_COMPLETED") }
    assertEquals(record.getLong("dueAt"),store.checks().single().getLong("dueAt"))
    assertEquals(record.getLong("deadlineAt"),store.checks().single().getLong("deadlineAt"))
    time=record.getLong("dueAt"); repeat(3) { engine.wakeEvent(record.getString("sessionId"),"due") }
    assertEquals("medium",store.checks().single().getJSONObject("alarm").getJSONArray("wakeTasks").getJSONObject(0).getString("difficulty"))
  }
  @Test fun randomPolicyHandlesEqualBoundsAndRejectsBadRanges() {
    val alarm=AlarmDraft(wakeEnabled=true,wakeRandom=true,wakeMinMinutes=3,wakeMaxMinutes=3)
    assertEquals(180_000,WakeUpPolicy.delayMs(alarm.json(),Random(2)).toInt())
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeMinMinutes=8,wakeMaxMinutes=7).validate() }
    assertThrows(IllegalArgumentException::class.java) { engine.save(alarm.copy(wakeMaxMinutes=1441).json().toString()) }
  }
  @Test fun genericAnswersCannotCompleteSensorTasksAndFallbackStillRequiresAllRounds() {
    engine.test(AlarmDraft(dismissTasks=listOf(TaskDraft(kind="walk",rounds=2))).json().toString()); begin("dismiss")
    val original=store.sessions().single(); val id=original.getString("sessionId"); val challenge=original.getString("challengeId")
    assertThrows(IllegalStateException::class.java) {engine.submit(id,challenge,"done")}
    assertThrows(IllegalStateException::class.java) {engine.nativeFallback(id,challenge,false)}
    engine.startNative(id,challenge,false); val frame=engine.nativeFrame(id,challenge,false)
    assertNotNull(frame.unavailable)
    engine.nativeFallback(id,challenge,false)
    assertEquals("math",store.sessions().single().getString("kind")); assertEquals(1,store.sessions().single().getInt("round"))
    assertThrows(IllegalStateException::class.java) {engine.submit(id,challenge,"done")}
    answer(); assertEquals(2,store.sessions().single().getInt("round")); answer(); assertTrue(store.sessions().isEmpty())
  }
  @Test fun deliveryTestRecordsAcknowledgementAndNeverStartsWakeChecks() {
    val normal=AlarmDraft(); engine.save(normal.json().toString())
    val normalAt=store.find(normal.id)!!.getLong("nextAt")
    engine.testDelivery(); val test=store.alarms().single {it.optBoolean("deliveryTest")}
    time=test.getLong("nextAt"); engine.fire(test.getString("id"))
    assertEquals("delivered",JSONObject(engine.state()).getJSONObject("deliveryTest").getString("status"))
    begin("dismiss"); assertEquals("acknowledged",JSONObject(engine.state()).getJSONObject("deliveryTest").getString("status"))
    assertTrue(store.checks().isEmpty()); assertEquals(normalAt,store.find(normal.id)!!.getLong("nextAt")); assertEquals(1,store.alarms().size)
  }
  @Test fun snoozingAnActiveRingKeepsAFutureSkip() {
    val alarm=AlarmDraft(days=(1..7).toSet(),dismissTasks=emptyList())
    fire(alarm)
    val excluded=store.find(alarm.id)!!.getLong("nextAt")
    engine.skipNext(alarm.id)
    begin("snooze")
    assertEquals(excluded,store.find(alarm.id)!!.getLong("skipAt"))
    time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id)
    assertTrue(store.find(alarm.id)!!.getLong("nextAt")>excluded)
    begin("dismiss")
  }
  @Test fun skipRetainsTheChosenCalendarDateAcrossTimezoneChanges() {
    val date="2026-10-16"
    val now=ZonedDateTime.parse("2026-10-16T05:00-04:00[America/New_York]")
    val original=Instant.parse("2026-10-16T07:00:00Z").toEpochMilli()
    val next=Timing.next(7,0,(1..7).toSet(),now,0,original,date)
    assertEquals(17,Instant.ofEpochMilli(next).atZone(now.zone).dayOfMonth)
  }
  @Test fun wakeRetriesKeepDifficultyBasedOnSnoozesRatherThanPreviousChecks() {
    val alarm=AlarmDraft(days=emptySet(),dismissTasks=emptyList(),wakeEnabled=true,wakeMode="tasks",progressiveDifficulty=true)
    fire(alarm); begin("snooze"); time=store.find(alarm.id)!!.getLong("nextAt"); engine.fire(alarm.id); begin("dismiss")
    repeat(3) {
      val pending=store.checks().single(); time=pending.getLong("dueAt"); engine.wakeEvent(pending.getString("sessionId"),"due")
      val active=store.checks().single()
      assertEquals("medium",active.getJSONObject("alarm").getJSONArray("wakeTasks").getJSONObject(0).getString("difficulty"))
      engine.respondWake(active.getString("sessionId"),false); begin("dismiss")
    }
  }
  @Test fun newSettingsRoundTripAndLegacyAlarmsStayUnchanged() {
    val legacy=AlarmDraft().json(); for(key in listOf("progressiveDifficulty","wakeRandom","wakeMinMinutes","wakeMaxMinutes","pauseUntil","skipAt","skipOneTime","skipDate")) legacy.remove(key)
    val parsed=AlarmDraft.from(legacy); assertFalse(parsed.progressiveDifficulty); assertFalse(parsed.wakeRandom); assertEquals(0,parsed.pauseUntil)
    val full=parsed.copy(wakeRandom=true,progressiveDifficulty=true,pauseUntil=time+100_000,dismissTasks=listOf(TaskDraft(kind="walk",stepTarget=30,fallbackKind="text"),TaskDraft(kind="light",lightLux=500),TaskDraft(kind="maze",controlMode="touch")))
    assertEquals(full,AlarmDraft.from(full.json())); full.validate()
  }
}
