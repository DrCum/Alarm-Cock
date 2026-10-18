package com.taskalarm.engine

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.taskalarm.app.BootAlarmActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Minimal device-protected schedule mirror. Task answers, barcodes and user files stay credential-protected. */
internal class BootRecovery(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
  private val device = context.createDeviceProtectedStorageContext()
  private val prefs = device.getSharedPreferences("task_alarm_boot_v1", Context.MODE_PRIVATE)
  private val manager = context.getSystemService(AlarmManager::class.java)
  private fun snapshot() = JSONObject(prefs.getString("snapshot", "{}")!!)
  private fun pending() = JSONArray(prefs.getString("pending", "[]"))
  private fun list(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
  private fun items(snapshot: JSONObject, key: String) = list(snapshot.optJSONArray(key) ?: JSONArray())
  private fun saveSnapshot(value: JSONObject) { check(prefs.edit().putString("snapshot", value.toString()).commit()) }
  private fun savePending(value: List<JSONObject>) { check(prefs.edit().putString("pending", JSONArray(value).toString()).commit()) }
  private fun canSchedule() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
  private fun receiver(id: String) = PendingIntent.getBroadcast(context, 0,
    Intent(context, AlarmReceiver::class.java).setData(android.net.Uri.parse("taskalarm://alarm/$id")),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  private fun wake(id: String, action: String) = PendingIntent.getBroadcast(context, 0,
    Intent(context, WakeCheckReceiver::class.java).setData(android.net.Uri.parse("taskalarm://wake/$id/$action")),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  fun open() = PendingIntent.getActivity(context, 0, Intent(context, BootAlarmActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  private fun exact(at: Long, operation: PendingIntent) {
    manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, open()), operation)
  }
  fun scheduleResume() {
    val intent = Intent(context, RingRecoveryReceiver::class.java).setData(android.net.Uri.parse("taskalarm://recovery/resume"))
    exact(now() + 1000, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
  }

  /** Called only after a successful native-store commit while credential storage is available. */
  fun sync() = synchronized(AlarmEngine.LOCK) {
    if(!unlocked(context)) return@synchronized
    val source = context.getSharedPreferences("task_alarm_v1", Context.MODE_PRIVATE)
    fun records(key: String) = list(JSONArray(source.getString(key, "[]")))
    val alarms = records("alarms")
    val sessions = records("sessions").filter { !it.optBoolean("test") || it.getJSONObject("alarm").optBoolean("deliveryTest") }
    val checks = records("wake_checks").filter { !it.optBoolean("test") }
    val previous = snapshot()
    val safe = JSONObject().put("preparedAt", previous.optLong("preparedAt", now()))
      .put("alarms", JSONArray(alarms.map { alarm -> JSONObject().apply {
        listOf("id", "hour", "minute", "days", "enabled", "nextAt", "pauseUntil", "skipAt", "skipDate", "snoozed", "deliveryTest").forEach { key ->
          if(alarm.has(key)) put(key, alarm.get(key))
        }
      } }))
      .put("sessions", JSONArray(sessions.map { JSONObject().put("id", it.getJSONObject("alarm").getString("id")) }))
      .put("checks", JSONArray(checks.map { record -> JSONObject().apply {
        listOf("sessionId", "dueAt", "deadlineAt", "stage").forEach { key -> if(record.has(key)) put(key, record.get(key)) }
      } }))
    if(safe.toString() != previous.toString()) saveSnapshot(safe)
    val activeIds = sessions.map { it.getJSONObject("alarm").getString("id") }.toSet()
    val live = list(pending()).filter { item ->
      if(item.getString("kind") == "alarm") alarms.any {
        it.getString("id") == item.getString("id") && it.optBoolean("enabled") && it.optLong("nextAt") == item.optLong("dueAt")
      } && item.getString("id") !in activeIds
      else checks.any { it.getString("sessionId") == item.getString("id") }
    }
    if(JSONArray(live).toString() != pending().toString()) savePending(live)
    if(live.isEmpty() && sessions.isEmpty() && prefs.getBoolean("audioRunning", false)) context.stopService(Intent(context, BootRingingService::class.java))
  }

  fun wasTriggered(id: String) = list(pending()).any { it.getString("kind") == "alarm" && it.getString("id") == id }
  fun hasRinging() = pending().length() > 0 || items(snapshot(), "sessions").isNotEmpty()
  fun setAudioRunning(value: Boolean) { prefs.edit().putBoolean("audioRunning", value).commit() }
  fun event(type: String, detail: String = "", id: String = "") = synchronized(AlarmEngine.LOCK) {
    val events = list(JSONArray(prefs.getString("events", "[]"))).takeLast(39) +
      JSONObject().put("at", now()).put("type", type).put("detail", detail.take(240)).put("id", id)
    prefs.edit().putString("events", JSONArray(events).toString()).commit()
  }
  fun restored(action: String?, scheduled: Int, recovered: Int, skipped: Int, blocked: Boolean = false) {
    val value = JSONObject().put("at", now()).put("action", action ?: "unknown").put("scheduled", scheduled)
      .put("recovered", recovered).put("skipped", skipped).put("blocked", blocked)
      .put("bootCount", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
    prefs.edit().putString("lastRestore", value.toString()).commit()
    event("restore", "${action ?: "unknown"}: $scheduled scheduled, $recovered recovered, $skipped missed; blocked=$blocked")
  }
  fun summary() = JSONObject().put("prepared", prefs.contains("snapshot")).put("userUnlocked", unlocked(context))
    .put("bootCount", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
    .put("bootedAt", now() - SystemClock.elapsedRealtime())
    .put("lastRestore", JSONObject(prefs.getString("lastRestore", "{}")!!))
    .put("events", JSONArray(prefs.getString("events", "[]")))

  fun restoreLocked(action: String?) = synchronized(AlarmEngine.LOCK) {
    if(!canSchedule()) { restored(action, 0, 0, 0, true); return@synchronized }
    val data = snapshot(); var scheduled = 0; var recovered = 0; var skipped = 0
    val updated = items(data, "alarms").map { alarm ->
      if(alarm.optBoolean("enabled") && alarm.has("nextAt")) {
        val due = alarm.getLong("nextAt")
        if(due > now() || now() - due <= CATCH_UP_MS || wasTriggered(alarm.getString("id"))) {
          exact(if(due > now()) due else now() + 1000, receiver(alarm.getString("id")))
          scheduled++; if(due <= now()) recovered++
        } else if(!alarm.optBoolean("deliveryTest")) {
          alarm.put("nextAt", next(alarm)); exact(alarm.getLong("nextAt"), receiver(alarm.getString("id"))); scheduled++; skipped++
        } else skipped++
      }
      alarm
    }
    data.put("alarms", JSONArray(updated)); saveSnapshot(data)
    if(hasRinging()) { scheduleResume(); recovered++ }
    items(data, "checks").filter { it.optString("stage") != "queued" }.forEach { check ->
      val id = check.getString("sessionId")
      exact(maxOf(now() + 1000, check.getLong("deadlineAt")), wake(id, "deadline"))
      if(check.getLong("dueAt") > now()) exact(check.getLong("dueAt"), wake(id, "due"))
      else if(check.getLong("deadlineAt") > now()) showCheck(id)
    }
    restored(action, scheduled, recovered, skipped)
  }
  private fun next(alarm: JSONObject): Long {
    val days = alarm.getJSONArray("days")
    return Timing.next(alarm.getInt("hour"), alarm.getInt("minute"), (0 until days.length()).map { days.getInt(it) }.toSet(),
      Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()), alarm.optLong("pauseUntil"), alarm.optLong("skipAt"), alarm.optString("skipDate"))
  }
  private fun record(kind: String, id: String, dueAt: Long) {
    val live = list(pending())
    if(live.none { it.getString("kind") == kind && it.getString("id") == id }) savePending(live +
      JSONObject().put("kind", kind).put("id", id).put("dueAt", dueAt).put("at", now()))
  }
  fun fireLocked(id: String) = synchronized(AlarmEngine.LOCK) {
    val data = snapshot()
    val alarm = items(data, "alarms").find { it.getString("id") == id } ?: return@synchronized
    if(!alarm.optBoolean("enabled") || alarm.optLong("nextAt", Long.MAX_VALUE) > now() + 2000) return@synchronized
    record("alarm", id, alarm.getLong("nextAt")); event("locked_alarm_received", "Alarm received before first unlock", id)
    if(alarm.getJSONArray("days").length() > 0 && !alarm.optBoolean("deliveryTest")) {
      alarm.put("nextAt", next(alarm)); exact(alarm.getLong("nextAt"), receiver(id))
    } else alarm.put("enabled", false)
    data.put("alarms", JSONArray(items(data, "alarms").map { if(it.getString("id") == id) alarm else it })); saveSnapshot(data)
    resumeLocked()
  }
  fun wakeLocked(id: String, phase: String) = synchronized(AlarmEngine.LOCK) {
    val data = snapshot(); val check = items(data, "checks").find { it.getString("sessionId") == id } ?: return@synchronized
    if(check.optString("stage") == "queued") return@synchronized
    if(phase == "due" && now() >= check.getLong("dueAt") && now() < check.getLong("deadlineAt")) {
      if(check.optString("stage") == "scheduled" && hasRinging()) {
        check.put("stage", "queued")
        data.put("checks", JSONArray(items(data, "checks").map { if(it.getString("sessionId") == id) check else it })); saveSnapshot(data)
        manager.cancel(wake(id, "deadline"))
      } else showCheck(id)
    } else if(phase == "deadline" && now() >= check.getLong("deadlineAt")) {
      if(check.optString("stage") == "scheduled" && hasRinging()) {
        check.put("stage", "queued")
        data.put("checks", JSONArray(items(data, "checks").map { if(it.getString("sessionId") == id) check else it })); saveSnapshot(data)
        return@synchronized
      }
      record("wake", id, check.getLong("deadlineAt")); event("locked_check_expired", "Wake-up check expired before first unlock", id)
      resumeLocked()
    }
  }
  fun resumeLocked() {
    if(hasRinging()) context.startForegroundService(Intent(context, BootRingingService::class.java))
  }
  private fun showCheck(id: String) {
    channel(context)
    val notification = NotificationCompat.Builder(context, BootRingingService.CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Wake-up check").setContentText("Unlock your phone to complete your check")
      .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
      .setContentIntent(open()).setFullScreenIntent(open(), true).setOngoing(true).build()
    context.getSystemService(NotificationManager::class.java).notify(6000 + (id.hashCode() and 0xffff), notification)
  }
  fun stopBootAudio() { context.stopService(Intent(context, BootRingingService::class.java)) }
  companion object {
    const val CATCH_UP_MS = 12 * 60 * 60_000L
    fun unlocked(context: Context) = context.getSystemService(UserManager::class.java).isUserUnlocked
    fun channel(context: Context) {
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(BootRingingService.CHANNEL, "Alarms after restart", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Alarm sound and unlock prompts before the first unlock after a restart"; setSound(null, null)
        })
    }
  }
}
