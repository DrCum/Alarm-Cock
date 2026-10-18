package com.taskalarm.engine

import android.app.Application
import android.app.AlarmManager
import android.app.NotificationManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmCopiesTest {
  private lateinit var context: Application
  private lateinit var store: AlarmStore
  private lateinit var engine: AlarmEngine
  private var time = 0L
  @Before fun setup() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    time = Instant.parse("2026-10-16T00:01:00Z").toEpochMilli()
    store = AlarmStore(context); engine = AlarmEngine(context) { time }
  }
  private fun copies(source: AlarmDraft, count: Int = 5, interval: Int = 5): List<JSONObject> {
    val json = engine.duplicate(source.id, count, interval, source.json().toString(), engine.preview(source.json().toString()))
    val array = JSONArray(json)
    return (0 until array.length()).map { array.getJSONObject(it) }
  }
  @Test fun bulkCopiesKeepSettingsAndHaveIndependentIdentitiesWithoutChangingTheSource() {
    val source = AlarmDraft(label = "Wake up", days = (1..7).toSet(), dismissTasks = listOf(TaskDraft(kind = "photo")),
      snoozeEnabled = false, snoozeMinutes = 15, maxSnoozes = 2, wakeEnabled = true, randomDismiss = true)
    engine.save(source.json().toString())
    val original = store.find(source.id)!!.toString()
    val result = copies(source, 5, 10)
    assertEquals(6, store.alarms().size); assertEquals(original, store.find(source.id)!!.toString())
    assertEquals(listOf("10", "20", "30", "40", "50"), result.map { AlarmDraft.from(it).minute })
    val taskIds = mutableSetOf(source.dismissTasks.single().id)
    result.forEach { raw ->
      val copy = AlarmDraft.from(raw)
      assertTrue(taskIds.add(copy.dismissTasks.single().id))
      assertEquals("photo", copy.dismissTasks.single().kind)
      assertFalse(copy.snoozeEnabled); assertEquals(15, copy.snoozeMinutes); assertEquals(2, copy.maxSnoozes)
      assertTrue(copy.wakeEnabled); assertTrue(copy.randomDismiss)
      assertFalse(raw.has("snoozeCount")); assertFalse(raw.has("snoozed")); assertFalse(raw.has("wakeRetry"))
    }
    assertEquals(6, (result.map { it.getString("id") } + source.id).toSet().size)
  }
  @Test fun oneTimeCopiesAfterMidnightCannotRingBeforeTheirSource() {
    val source = AlarmDraft(hour = "23", minute = "55", days = emptySet())
    engine.save(source.json().toString())
    val result = copies(source, 2, 10)
    assertEquals(Instant.parse("2026-10-17T00:05:00Z").toEpochMilli(), result[0].getLong("nextAt"))
    assertEquals(Instant.parse("2026-10-17T00:15:00Z").toEpochMilli(), result[1].getLong("nextAt"))
    engine = AlarmEngine(context) { time }; engine.restore("android.intent.action.BOOT_COMPLETED")
    assertEquals(result[0].getLong("nextAt"), store.find(result[0].getString("id"))!!.getLong("nextAt"))
  }
  @Test fun repeatingCopiesRollTheirWeekdaysWithTheClock() {
    val source = AlarmDraft(hour = "23", minute = "55", days = setOf(5, 7))
    engine.save(source.json().toString())
    val result = copies(source, 1, 10).single()
    assertEquals(setOf(6, 1), AlarmDraft.from(result).days)
    assertEquals("00", AlarmDraft.from(result).hour); assertEquals("05", AlarmDraft.from(result).minute)
    assertEquals(Instant.parse("2026-10-17T00:05:00Z").toEpochMilli(), result.getLong("nextAt"))
  }
  @Test fun invalidBatchSizesAndIntervalsDoNotLeavePartialCopiesOrTimers() {
    val source = AlarmDraft(); engine.save(source.json().toString())
    val timers = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
    for((count, interval) in listOf(0 to 5, 51 to 5, 5 to 0, 5 to -1, 50 to 30, 1 to 1440)) {
      assertThrows(IllegalArgumentException::class.java) { copies(source, count, interval) }
      assertEquals(1, store.alarms().size)
      assertEquals(timers, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }
  }
  @Test fun stalePreviewsAndChangedSourcesMustBeReviewedAgain() {
    val source = AlarmDraft(); engine.save(source.json().toString())
    val startAt = engine.preview(source.json().toString())
    engine.save(source.copy(label = "Changed").json().toString())
    assertThrows(IllegalStateException::class.java) { engine.duplicate(source.id, 5, 5, source.json().toString(), startAt) }
    val changed = AlarmDraft.from(store.find(source.id)!!)
    time = startAt
    assertThrows(IllegalStateException::class.java) { engine.duplicate(source.id, 5, 5, changed.json().toString(), startAt) }
    assertEquals(1, store.alarms().size)
  }
  @Test fun disabledCopiesStayDisabledAndStartWithoutRuntimeHistoryOrSkips() {
    val source = AlarmDraft(enabled = false, skipDate = "2026-10-16", skipAt = time + 1000, skipOneTime = true)
    engine.save(source.json().toString())
    val result = copies(source, 2, 5)
    result.forEach { raw ->
      val copy = AlarmDraft.from(raw)
      assertFalse(copy.enabled); assertFalse(raw.has("nextAt")); assertEquals("", copy.skipDate)
      assertEquals(0L, copy.skipAt); assertFalse(copy.skipOneTime)
    }
    assertEquals(3, store.alarms().size)
  }
  @Test fun missingNotificationAccessFailsBeforeCreatingAnyCopy() {
    val source = AlarmDraft(); engine.save(source.json().toString())
    val manager = context.getSystemService(AlarmManager::class.java)
    val before = shadowOf(manager).scheduledAlarms.size
    shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
    assertThrows(IllegalStateException::class.java) { copies(source) }
    assertEquals(1, store.alarms().size); assertEquals(before, shadowOf(manager).scheduledAlarms.size)
  }
}
