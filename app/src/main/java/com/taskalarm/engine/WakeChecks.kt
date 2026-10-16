package com.taskalarm.engine

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.UUID
import java.util.Date

/** Durable native timers; the UI polling loop is never responsible for re-ringing. */
internal class WakeChecks(
  private val context: Context,
  private val now: () -> Long,
  private val onRetry: (JSONObject) -> Unit
) {
  private val store = AlarmStore(context)
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)
  private fun intent(id: String, action: String) = PendingIntent.getBroadcast(context, 0,
    Intent(context, WakeCheckReceiver::class.java).setData(Uri.parse("taskalarm://wake/$id/$action")),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  private fun open() = PendingIntent.getActivity(context, 0,
    context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  private fun notificationId(id: String) = 2000 + (id.hashCode() and 0x0fffffff)
  private fun put(record: JSONObject) {
    store.checks(store.checks().filterNot { it.getString("sessionId") == record.getString("sessionId") } + record)
  }
  private fun remove(record: JSONObject) {
    val id = record.getString("sessionId")
    alarms.cancel(intent(id, "reminder")); alarms.cancel(intent(id, "due")); alarms.cancel(intent(id, "deadline"))
    notifications.cancel(notificationId(id))
    store.checks(store.checks().filterNot { it.getString("sessionId") == id })
  }
  fun cancelForAlarm(alarmId: String, test: Boolean? = null) {
    store.checks().filter { it.getJSONObject("alarm").getString("id") == alarmId && (test == null || it.optBoolean("test") == test) }.forEach(::remove)
  }
  private fun exact(record: JSONObject, phase: String, time: Long) {
    check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) { "Allow Alarms & reminders to use wake-up checks" }
    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(time, open()), intent(record.getString("sessionId"), phase))
  }
  fun schedule(alarm: JSONObject, test: Boolean = false) {
    if (!alarm.optBoolean("wakeEnabled")) return
    check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) { "Restore Alarms & reminders access before dismissing this alarm" }
    val startedAt = now()
    val dueTime = startedAt + if (test) 0L else alarm.optInt("wakeDelayMinutes", 5) * 60_000L
    val record = JSONObject().put("sessionId", UUID.randomUUID().toString()).put("alarm", JSONObject(alarm.toString()))
      .put("test", test).put("stage", "scheduled").put("dueAt", dueTime).put("reminderAt", startedAt + 120_000L)
      .put("deadlineAt", dueTime + alarm.optInt("wakeResponseMinutes", 5) * 60_000L)
      .put("taskIndex", 0).put("round", 1).put("kind", JSONObject.NULL).put("prompt", JSONObject.NULL)
    // Schedule before removing the previous cycle so a permission failure cannot erase it.
    exact(record, "deadline", record.getLong("deadlineAt"))
    if (!test) exact(record, "due", dueTime)
    reminderAt(record)?.let { exact(record, "reminder", it) }
    cancelForAlarm(alarm.getString("id"), test)
    put(record)
    if (test) due(record.getString("sessionId"))
  }
  private fun reminderAt(record: JSONObject): Long? {
    if (record.optBoolean("test")) return null
    // Infer the dismissal time for checks saved by versions before the reminder existed.
    val time = record.optLong("reminderAt", record.getLong("dueAt") -
      record.getJSONObject("alarm").optInt("wakeDelayMinutes", 5) * 60_000L + 120_000L)
    return time.takeIf { it < record.getLong("dueAt") }
  }
  fun remind(id: String, restore: Boolean = false) {
    val record = store.checks().find { it.getString("sessionId") == id } ?: return
    val time = reminderAt(record) ?: return
    if (record.getString("stage") != "scheduled" || now() < time) return
    if (now() >= record.getLong("dueAt")) { due(id); return }
    if (record.optBoolean("reminderShown") && !restore) return
    record.put("reminderShown", true); put(record)
    channel()
    val dueTime = DateFormat.getTimeFormat(context).format(Date(record.getLong("dueAt")))
    val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Wake-up check at $dueTime")
      .setContentText("Already up? Dismiss this check before it starts to cancel its repeat alarm.")
      .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open()).setOnlyAlertOnce(true)
      .setTimeoutAfter(record.getLong("dueAt") - now())
      .addAction(0, "Dismiss check", intent(id, "dismiss_early")).build()
    notifications.notify(notificationId(id), notification)
  }
  fun dismissEarly(id: String) {
    val record = store.checks().find { it.getString("sessionId") == id } ?: return
    if (record.getString("stage") != "scheduled") return
    // A stale notification action must not bypass a check that has already started.
    if (now() >= record.getLong("dueAt")) { due(id); return }
    remove(record)
  }
  fun due(id: String) {
    val record = store.checks().find { it.getString("sessionId") == id } ?: return
    if (now() < record.getLong("dueAt")) return
    if (now() >= record.getLong("deadlineAt")) { retry(record); return }
    alarms.cancel(intent(id, "reminder"))
    if (record.getString("stage") == "scheduled") {
      record.put("stage", "waiting")
      if (record.getJSONObject("alarm").optString("wakeMode", "yes_no") == "tasks") {
        SessionTasks.start(record, record.getJSONObject("alarm").getJSONArray("wakeTasks"))
      }
      put(record)
    }
    show(record)
  }
  private fun channel() {
    notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Wake-up checks", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Confirm you are out of bed before your alarm rings again"
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    })
  }
  private fun show(record: JSONObject) {
    channel()
    val id = record.getString("sessionId")
    val tasks = record.getJSONObject("alarm").optString("wakeMode", "yes_no") == "tasks"
    val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Are you out of bed?").setContentText(if (tasks) "Complete your wake-up tasks before the alarm rings again" else "Confirm you’re awake before the alarm rings again")
      .setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setContentIntent(open()).setFullScreenIntent(open(), true)
      .setTimeoutAfter((record.getLong("deadlineAt") - now()).coerceAtLeast(1))
    if (!tasks) notification.addAction(0, "Yes, I’m up", intent(id, "yes"))
    notification.addAction(0, "No — ring again", intent(id, "no"))
    notifications.notify(notificationId(id), notification.build())
  }
  private fun waiting(id: String): JSONObject {
    val record = store.checks().find { it.getString("sessionId") == id } ?: error("This wake-up check has already finished")
    if (now() >= record.getLong("deadlineAt")) {
      retry(record); error("The response time ended. Your alarm is ringing again.")
    }
    check(record.getString("stage") == "waiting") { "This wake-up check is not ready yet" }
    return record
  }
  fun respond(id: String, awake: Boolean) {
    val record = waiting(id)
    if (!awake) { retry(record); return }
    check(record.getJSONObject("alarm").optString("wakeMode", "yes_no") == "yes_no") { "Complete the configured wake-up tasks to confirm" }
    remove(record)
  }
  fun submit(id: String, challengeId: String, answer: String): Boolean {
    val record = waiting(id)
    check(record.getJSONObject("alarm").optString("wakeMode", "yes_no") == "tasks")
    return when (SessionTasks.submit(record, record.getJSONObject("alarm").getJSONArray("wakeTasks"), challengeId, answer)) {
      SessionTasks.Result.WRONG -> false
      SessionTasks.Result.NEXT -> { put(record); true }
      SessionTasks.Result.COMPLETE -> { remove(record); true }
    }
  }
  fun gameRecord(id: String) = waiting(id).also {
    check(it.getJSONObject("alarm").optString("wakeMode", "yes_no") == "tasks")
  }
  fun saveGame(record: JSONObject) = put(record)
  fun completeGame(record: JSONObject, challengeId: String) {
    when (SessionTasks.completeGame(record, record.getJSONObject("alarm").getJSONArray("wakeTasks"), challengeId)) {
      SessionTasks.Result.COMPLETE -> remove(record)
      else -> put(record)
    }
  }
  fun deadline(id: String) {
    val record = store.checks().find { it.getString("sessionId") == id } ?: return
    if (record.getString("stage") == "retrying" || now() >= record.getLong("deadlineAt")) retry(record)
  }
  private fun retry(record: JSONObject) {
    // Persist the transition before starting media. A failed service start remains recoverable.
    record.put("stage", "retrying"); put(record)
    onRetry(record)
    remove(record)
  }
  fun reconcile() {
    store.checks().forEach { record ->
      when {
        record.getString("stage") == "retrying" || now() >= record.getLong("deadlineAt") -> retry(record)
        record.getString("stage") == "scheduled" && now() >= record.getLong("dueAt") -> due(record.getString("sessionId"))
        record.getString("stage") == "scheduled" && !record.optBoolean("reminderShown") &&
          reminderAt(record)?.let { now() >= it } == true -> remind(record.getString("sessionId"))
      }
    }
  }
  fun visible(): JSONObject? = store.checks().filter { it.getString("stage") == "waiting" }
    .minByOrNull { it.getLong("deadlineAt") }?.let {
      JSONObject(it.toString()).apply {
        remove("answer")
        put("totalTasks", getJSONObject("alarm").optJSONArray("wakeTasks")?.length() ?: 0)
      }
    }
  fun restore() {
    if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) return
    store.checks().forEach { record ->
      // Boot receivers cannot start a media FGS directly; an exact user alarm can.
      exact(record, "deadline", maxOf(now() + 1000, record.getLong("deadlineAt")))
      if (record.getString("stage") == "retrying") exact(record, "deadline", now() + 1000)
      else if (now() < record.getLong("dueAt")) {
        exact(record, "due", record.getLong("dueAt"))
        reminderAt(record)?.let {
          if (now() < it) exact(record, "reminder", it)
          else remind(record.getString("sessionId"), restore = true)
        }
      }
      else if (now() < record.getLong("deadlineAt")) due(record.getString("sessionId"))
    }
  }
  companion object { const val CHANNEL = "task-alarm-wake-checks-v1" }
}
