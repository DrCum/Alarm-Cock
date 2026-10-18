package com.taskalarm.engine

import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import java.util.UUID

internal object AlarmCopies {
  fun create(source: AlarmDraft, count: Int, intervalMinutes: Int, startAt: Long): List<AlarmDraft> {
    require(count in 1..50) { "Choose 1–50 additional alarms" }
    require(intervalMinutes in 1..1439 && count.toLong() * intervalMinutes < 1440) { "Choose a positive interval and keep the series within 24 hours" }
    fun fresh(tasks: List<TaskDraft>) = tasks.map { it.copy(id = UUID.randomUUID().toString()) }
    return (1..count).map { index ->
      val minutes = source.hour.toInt() * 60 + source.minute.toInt() + index * intervalMinutes
      val dayOffset = minutes / 1440
      source.copy(id = UUID.randomUUID().toString(), label = "${source.label.take(110)} · ${index + 1}",
        hour = ((minutes % 1440) / 60).toString().padStart(2, '0'), minute = (minutes % 60).toString().padStart(2, '0'),
        days = source.days.map { (it - 1 + dayOffset) % 7 + 1 }.toSet(),
        // Prevent a copy after midnight from ringing before the source's upcoming occurrence.
        pauseUntil = startAt, skipAt = 0, skipDate = "", skipOneTime = false,
        dismissTasks = fresh(source.dismissTasks), snoozeTasks = fresh(source.snoozeTasks),
        wakeTasks = fresh(source.wakeTasks), wakeRetryTasks = fresh(source.wakeRetryTasks))
    }
  }
}
