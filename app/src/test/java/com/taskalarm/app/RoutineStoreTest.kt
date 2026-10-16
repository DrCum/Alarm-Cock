package com.taskalarm.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RoutineStoreTest {
  private lateinit var store: RoutineStore
  @Before fun setup() {
    val context: Application=RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_routines_v1",0).edit().clear().commit()
    store=RoutineStore(context)
  }
  @Test fun applyingCopiesSettingsAndFreshTaskIdsButPreservesTheTargetSchedule() {
    val source=AlarmDraft(dismissTasks=listOf(TaskDraft(kind="maze")),snoozeTasks=listOf(TaskDraft(kind="memory")),wakeEnabled=true,wakeRandom=true,progressiveDifficulty=true,escalationEnabled=true)
    val target=AlarmDraft(label="Work",hour="09",minute="30",days=setOf(6,7),enabled=false,pauseUntil=12345,skipAt=22222)
    val result=store.apply(Routine("example","Example",source),target)
    assertEquals(target.id,result.id); assertEquals(target.label,result.label); assertEquals(target.hour,result.hour); assertEquals(target.minute,result.minute)
    assertEquals(target.days,result.days); assertEquals(target.enabled,result.enabled); assertEquals(target.pauseUntil,result.pauseUntil); assertEquals(target.skipAt,result.skipAt)
    assertTrue(result.wakeRandom); assertTrue(result.progressiveDifficulty); assertTrue(result.escalationEnabled)
    assertNotEquals(source.dismissTasks.single().id,result.dismissTasks.single().id)
    val another=store.apply(Routine("example","Example",source),target)
    assertNotEquals(result.dismissTasks.single().id,another.dismissTasks.single().id)
  }
  @Test fun editingDeletingAndDuplicatingPresetsDoNotMutateAppliedAlarms() {
    val source=AlarmDraft(dismissTasks=listOf(TaskDraft(kind="text")))
    store.save("Original",source,"one")
    val saved=store.saved().single(); val applied=store.apply(saved,AlarmDraft())
    store.save("Duplicate",saved.alarm,"two")
    store.save("Renamed",source.copy(dismissTasks=listOf(TaskDraft(kind="memory"))),"one")
    assertEquals("text",applied.dismissTasks.single().kind)
    assertEquals("text",store.saved().single { it.id=="two" }.alarm.dismissTasks.single().kind)
    store.delete("one"); assertEquals("two",store.saved().single().id)
    assertEquals("text",applied.dismissTasks.single().kind)
  }
  @Test fun missingFilesFailBeforeApplyingAndStarterBarcodeNeedsEnrollment() {
    val bad=Routine("bad","Bad",AlarmDraft(soundUri="file:///does-not-exist.wav"))
    assertThrows(IllegalArgumentException::class.java) {store.apply(bad,AlarmDraft())}
    val strong=RoutineStore.starters().single { it.id=="starter-strong" }
    assertThrows(IllegalArgumentException::class.java) {store.apply(strong,AlarmDraft()).validate()}
    assertThrows(IllegalArgumentException::class.java) {store.save(" ",AlarmDraft())}
  }
}
