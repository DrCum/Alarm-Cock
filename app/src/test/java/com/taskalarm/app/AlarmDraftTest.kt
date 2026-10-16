package com.taskalarm.app

import org.junit.Assert.*
import org.junit.Test

class AlarmDraftTest {
  @Test fun partialAndOutOfRangeTimesAreRejected() {
    for (alarm in listOf(AlarmDraft(hour = ""), AlarmDraft(hour = "24"), AlarmDraft(minute = "60"))) {
      assertThrows(IllegalArgumentException::class.java) { alarm.validate() }
    }
    AlarmDraft(hour = "0", minute = "0").validate()
    AlarmDraft(hour = "23", minute = "59").validate()
  }
  @Test fun specificBarcodeMustBeEnrolledForEitherAction() {
    val task = TaskDraft(kind = "barcode", barcodeMode = "specific")
    assertThrows(IllegalArgumentException::class.java) { AlarmDraft(dismissTasks = listOf(task)).validate() }
    assertThrows(IllegalArgumentException::class.java) { AlarmDraft(snoozeTasks = listOf(task)).validate() }
    AlarmDraft(snoozeTasks = listOf(task.copy(barcode = "0012345"))).validate()
    AlarmDraft(snoozeTasks = listOf(task.copy(barcodeMode = "any"))).validate()
  }
  @Test fun ChangingSnoozeTasksDoesNotChangeDismissTasks() {
    val initial = AlarmDraft()
    val modified = initial.withTasks("snooze", listOf(TaskDraft(kind = "text")))
    assertEquals("math", modified.dismissTasks.single().kind)
    assertEquals("text", modified.snoozeTasks.single().kind)
    assertTrue(initial.snoozeTasks.isEmpty())
  }
  @Test fun zeroAndExcessiveSnoozeLengthsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) { AlarmDraft(snoozeMinutes = 0).validate() }
    assertThrows(IllegalArgumentException::class.java) { AlarmDraft(snoozeMinutes = 61).validate() }
  }
  @Test fun wakeCheckDefaultsAndInvalidConfigurations() {
    val alarm = AlarmDraft(wakeEnabled = true)
    assertEquals(5, alarm.wakeDelayMinutes)
    assertEquals(5, alarm.wakeResponseMinutes)
    assertTrue(alarm.wakeReuseDismissTasks)
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeDelayMinutes = 0).validate() }
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeResponseMinutes = 1441).validate() }
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeMode = "tasks", wakeTasks = emptyList()).validate() }
    val barcode = TaskDraft(kind = "barcode", barcodeMode = "specific")
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeMode = "tasks", wakeTasks = listOf(barcode)).validate() }
    assertThrows(IllegalArgumentException::class.java) { alarm.copy(wakeReuseDismissTasks = false, wakeRetryTasks = listOf(barcode)).validate() }
    alarm.copy(wakeRetryTasks = listOf(barcode)).validate() // Unused custom tasks do not block setup.
  }
}
