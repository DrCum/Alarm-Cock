package com.taskalarm.engine

import android.app.Application
import com.taskalarm.app.AlarmDraft
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AlarmEditsTest {
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private lateinit var context: Application
  private var time=System.currentTimeMillis()
  @Before fun setup() {
    context=RuntimeEnvironment.getApplication()
    for(name in listOf("task_alarm_v1","task_alarm_edits_v1")) context.getSharedPreferences(name,0).edit().clear().commit()
    engine=AlarmEngine(context) {time}; store=AlarmStore(context)
  }
  private fun saved(alarm: AlarmDraft=AlarmDraft()): AlarmDraft {engine.save(alarm.json().toString()); return AlarmDraft.from(store.find(alarm.id)!!)}
  private fun current(id: String)=AlarmDraft.from(store.find(id)!!)
  @Test fun autosavePersistsAndDiscardRestoresTheOpeningSettingsAfterMultipleWrites() {
    val original=saved(); val token=engine.beginEdit(original.json().toString())
    engine.saveEdit(token,original.copy(label="Changed").json().toString())
    engine.saveEdit(token,original.copy(label="Again",hour="09").json().toString())
    assertEquals("09",current(original.id).hour)
    assertTrue(engine.discardEdit(token)); assertEquals(original,current(original.id))
    assertThrows(IllegalStateException::class.java) {engine.saveEdit(token,original.copy(hour="10").json().toString())}
  }
  @Test fun revertSurvivesReopeningAndUsesTheBeginningOfThePreviousEdit() {
    val original=saved(); val token=engine.beginEdit(original.json().toString())
    engine.saveEdit(token,original.copy(label="First").json().toString())
    engine.saveEdit(token,original.copy(label="Second").json().toString()); engine.closeEdit(token)
    engine=AlarmEngine(context) {time}
    val reopened=engine.beginEdit(current(original.id).json().toString())
    assertTrue(JSONObject(engine.editStatus(reopened)).getBoolean("canRevert"))
    val restored=AlarmDraft.from(JSONObject(engine.revertEdit(reopened,false)))
    assertEquals(original,restored); assertEquals(original,current(original.id))
    assertFalse(JSONObject(engine.editStatus(reopened)).getBoolean("canRevert"))
  }
  @Test fun newAlarmCanBeDiscardedAfterAutosavingAndALateWorkerCannotRecreateIt() {
    val fresh=AlarmDraft(); val token=engine.beginEdit(fresh.json().toString())
    assertNull(store.find(fresh.id))
    engine.saveEdit(token,fresh.copy(label="New").json().toString()); assertNotNull(store.find(fresh.id))
    assertTrue(engine.discardEdit(token)); assertNull(store.find(fresh.id))
    assertThrows(IllegalStateException::class.java) {engine.saveEdit(token,fresh.json().toString())}
  }
  @Test fun invalidIntermediateFieldsNeverOverwriteTheLastGoodAlarm() {
    val original=saved(); val token=engine.beginEdit(original.json().toString())
    assertThrows(IllegalArgumentException::class.java) {engine.saveEdit(token,original.copy(hour="").json().toString())}
    assertEquals(original,current(original.id))
    engine.saveEdit(token,original.copy(hour="08").json().toString()); assertEquals("08",current(original.id).hour)
  }
  @Test fun openingAndSavingAnUnchangedAlarmDoesNotCancelPendingChecks() {
    val original=saved(AlarmDraft(days=emptySet(),dismissTasks=emptyList(),wakeEnabled=true))
    time=store.find(original.id)!!.getLong("nextAt"); engine.fire(original.id)
    engine.begin(store.sessions().single().getString("sessionId"),"dismiss")
    val definition=current(original.id); val check=store.checks().single().toString()
    val token=engine.beginEdit(definition.json().toString()); engine.saveEdit(token,definition.json().toString()); engine.closeEdit(token)
    assertEquals(check,store.checks().single().toString())
  }
  @Test fun aNewEditorInvalidatesOlderWorkers() {
    val original=saved(); val old=engine.beginEdit(original.json().toString()); val fresh=engine.beginEdit(original.json().toString())
    assertThrows(IllegalStateException::class.java) {engine.saveEdit(old,original.copy(label="Stale").json().toString())}
    engine.saveEdit(fresh,original.copy(label="Current").json().toString()); assertEquals("Current",current(original.id).label)
  }
  @Test fun alarmDeliveryProtectsNewRuntimeStateFromDiscardOrStaleAutosave() {
    val original=saved(AlarmDraft(days=emptySet())); val token=engine.beginEdit(original.json().toString())
    engine.saveEdit(token,original.copy(label="Changed").json().toString())
    time=store.find(original.id)!!.getLong("nextAt"); engine.fire(original.id)
    assertThrows(IllegalStateException::class.java) {engine.saveEdit(token,original.json().toString())}
    assertFalse(engine.discardEdit(token)); assertEquals("Changed",current(original.id).label)
    assertFalse(current(original.id).enabled); assertEquals(1,store.sessions().size)
  }
  @Test fun editingAndDiscardingDuringAPendingSnoozeKeepsItsExactTimeAndCount() {
    val original=saved(AlarmDraft(days=(1..7).toSet(),dismissTasks=emptyList()))
    time=store.find(original.id)!!.getLong("nextAt"); engine.fire(original.id)
    engine.begin(store.sessions().single().getString("sessionId"),"snooze")
    val snoozed=store.find(original.id)!!; val draft=AlarmDraft.from(snoozed); val token=engine.beginEdit(draft.json().toString())
    engine.saveEdit(token,draft.copy(label="Changed").json().toString())
    assertEquals(snoozed.getLong("nextAt"),store.find(original.id)!!.getLong("nextAt"))
    assertTrue(engine.discardEdit(token)); val restored=store.find(original.id)!!
    assertTrue(restored.getBoolean("snoozed")); assertEquals(1,restored.getInt("snoozeCount")); assertEquals(snoozed.getLong("nextAt"),restored.getLong("nextAt"))
  }
  @Test fun autosaveCannotRescheduleAnAlarmThatIsJustAboutToRing() {
    val original=saved(); val token=engine.beginEdit(original.json().toString())
    time=store.find(original.id)!!.getLong("nextAt")-500
    assertThrows(IllegalStateException::class.java) {engine.saveEdit(token,original.copy(label="Too late").json().toString())}
    assertEquals(original,current(original.id)); assertTrue(engine.discardEdit(token))
  }
  @Test fun legacyDefinitionsHaveStableFallbackIdsForEditComparisons() {
    val legacy=AlarmDraft().json(); legacy.remove("wakeTasks"); legacy.remove("wakeRetryTasks")
    assertEquals(AlarmDraft.from(legacy),AlarmDraft.from(JSONObject(legacy.toString())))
  }
}
