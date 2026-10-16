package com.taskalarm.engine

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TimingTest {
  private fun at(text: String) = ZonedDateTime.parse(text)
  private fun result(hour: Int, minute: Int, days: Set<Int>, now: String) =
    Instant.ofEpochMilli(Timing.next(hour, minute, days, at(now))).atZone(at(now).zone)

  @Test fun oneTimeAlarmChoosesTodayOrTomorrow() {
    assertEquals(at("2026-10-16T07:00Z[UTC]"), result(7, 0, emptySet(), "2026-10-16T06:59Z[UTC]"))
    assertEquals(at("2026-10-17T07:00Z[UTC]"), result(7, 0, emptySet(), "2026-10-16T07:00Z[UTC]"))
  }
  @Test fun weekdaysSkipWeekendAndWeeklyAlarmCanBeSevenDaysAway() {
    assertEquals(at("2026-10-19T07:00Z[UTC]"), result(7, 0, setOf(1, 2, 3, 4, 5), "2026-10-16T08:00Z[UTC]"))
    assertEquals(at("2026-10-23T07:00Z[UTC]"), result(7, 0, setOf(5), "2026-10-16T07:00Z[UTC]"))
  }
  @Test fun springGapMovesNonexistentTimeForward() {
    val now = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, ZoneId.of("America/New_York"))
    val next = Instant.ofEpochMilli(Timing.next(2, 30, emptySet(), now)).atZone(now.zone)
    assertEquals(8, next.dayOfMonth); assertEquals(3, next.hour); assertEquals(30, next.minute)
  }
  @Test fun fallOverlapDoesNotRingTwice() {
    val next = result(1, 30, emptySet(), "2026-11-01T01:45-04:00[America/New_York]")
    assertEquals(2, next.dayOfMonth); assertEquals(1, next.hour); assertEquals(30, next.minute)
  }
}
