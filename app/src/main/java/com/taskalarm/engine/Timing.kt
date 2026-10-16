package com.taskalarm.engine

import java.time.ZonedDateTime

internal object Timing {
  fun next(hour: Int, minute: Int, days: Set<Int>, now: ZonedDateTime = ZonedDateTime.now(), pauseUntil: Long = 0, skipAt: Long = 0, skipDate: String = ""): Long {
    require(hour in 0..23 && minute in 0..59 && days.all { it in 1..7 })
    val boundary = maxOf(now.toInstant().toEpochMilli(), pauseUntil - 1)
    val start = java.time.Instant.ofEpochMilli(boundary).atZone(now.zone)
    for (offset in 0L..14L) {
      val day = start.toLocalDate().plusDays(offset)
      // atZone handles daylight-saving gaps and overlaps in the phone's current zone.
      val candidate = day.atTime(hour, minute).atZone(now.zone)
      if (candidate.toInstant().toEpochMilli() > boundary && (if(skipDate.isEmpty()) candidate.toInstant().toEpochMilli() != skipAt else day.toString() != skipDate) && (days.isEmpty() || day.dayOfWeek.value in days)) {
        return candidate.toInstant().toEpochMilli()
      }
    }
    error("No next alarm occurrence")
  }
}
