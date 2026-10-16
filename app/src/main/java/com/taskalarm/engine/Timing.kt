package com.taskalarm.engine

import java.time.ZonedDateTime

internal object Timing {
  fun next(hour: Int, minute: Int, days: Set<Int>, now: ZonedDateTime = ZonedDateTime.now()): Long {
    require(hour in 0..23 && minute in 0..59 && days.all { it in 1..7 })
    for (offset in 0L..7L) {
      val day = now.toLocalDate().plusDays(offset)
      // atZone handles daylight-saving gaps and overlaps in the phone's current zone.
      val candidate = day.atTime(hour, minute).atZone(now.zone)
      if (candidate.toInstant().isAfter(now.toInstant()) && (days.isEmpty() || day.dayOfWeek.value in days)) {
        return candidate.toInstant().toEpochMilli()
      }
    }
    error("No next alarm occurrence")
  }
}
