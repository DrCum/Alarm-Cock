package com.taskalarm.engine

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

internal data class AlarmClockStamp(val wall: Long, val elapsed: Long, val boot: Int) {
  companion object {
    fun current(context: Context, wall: Long = System.currentTimeMillis()) = AlarmClockStamp(wall, SystemClock.elapsedRealtime(),
      Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))
  }
}

/** Persists trigger decisions; monotonic time prevents clock edits from changing active intervals. */
internal object EscalationPolicy {
  const val TONE_MS = 45_000L
  fun validate(alarm: JSONObject) {
    if (!alarm.optBoolean("escalationEnabled")) return
    require(alarm.optInt("escalationAfterMinutes", 2) in 1..120) { "Choose 1–120 minutes before escalation" }
    val tones = alarm.optJSONArray("escalationTones")
    require(tones != null && tones.length() in 1..12) { "Choose 1–12 escalation tones" }
    for (i in 0 until tones.length()) {
      val tone = tones.getJSONObject(i)
      val uri = tone.optString("uri")
      val supported = if (uri.startsWith("builtin:")) uri.removePrefix("builtin:") in BuiltinSounds.names
        else uri.substringBefore(':') in listOf("content", "file", "android.resource")
      require(uri.isNotBlank() && uri.length <= 4096 && supported) { "Choose a phone, imported, or bundled tone" }
      require(tone.optString("name").length <= 120) { "Tone name is too long" }
    }
  }
  private fun stamp(record: JSONObject, prefix: String, now: AlarmClockStamp) {
    record.put(prefix + "Wall", now.wall).put(prefix + "Elapsed", now.elapsed).put(prefix + "Boot", now.boot)
  }
  fun age(record: JSONObject, prefix: String, now: AlarmClockStamp): Long {
    if (!record.has(prefix + "Wall")) return 0
    return if (record.optInt(prefix + "Boot", -1) == now.boot && now.elapsed >= record.optLong(prefix + "Elapsed"))
      now.elapsed - record.getLong(prefix + "Elapsed")
    else (now.wall - record.getLong(prefix + "Wall")).coerceAtLeast(0)
  }
  fun refresh(record: JSONObject, now: AlarmClockStamp): Boolean {
    val alarm = record.getJSONObject("alarm")
    if (!alarm.optBoolean("escalationEnabled")) return false
    var changed = false
    if (!record.has("ringStartedWall")) { stamp(record, "ringStarted", now); changed = true }
    if (record.optBoolean("escalated")) return changed
    val reason = when {
      alarm.optBoolean("escalationLatched") -> "continued"
      alarm.optInt("snoozeCount") >= 2 -> "snoozes"
      record.isNull("action") && age(record, "ringStarted", now) >= alarm.optInt("escalationAfterMinutes", 2) * 60_000L -> "unanswered"
      else -> null
    } ?: return changed
    stamp(record, "escalated", now)
    record.put("escalated", true).put("escalationReason", reason)
    alarm.put("escalationLatched", true)
    record.optJSONObject("baseAlarm")?.put("escalationLatched", true)
    return true
  }
  fun hop(record: JSONObject, now: AlarmClockStamp) = age(record, "escalated", now) / TONE_MS
  fun untilNextTone(record: JSONObject, now: AlarmClockStamp) = TONE_MS - age(record, "escalated", now) % TONE_MS
}
