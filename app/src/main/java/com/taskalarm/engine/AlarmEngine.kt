package com.taskalarm.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

internal class AlarmEngine(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
  private val store = AlarmStore(context)
  private val manager = context.getSystemService(AlarmManager::class.java)
  private val wakeChecks by lazy { WakeChecks(context, now, ::retryWake) }
  private fun receiver(id: String) = PendingIntent.getBroadcast(context, 0,
    Intent(context, AlarmReceiver::class.java).setData(Uri.parse("taskalarm://alarm/$id")),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  fun readiness(): JSONObject {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val nm = context.getSystemService(NotificationManager::class.java)
    fun channelEnabled(id: String) = nm.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    return JSONObject().put("exactAlarms", Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
      .put("notifications", enabled && channelEnabled(RingingService.CHANNEL))
      .put("wakeNotifications", enabled && channelEnabled(WakeChecks.CHANNEL))
      .put("fullScreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
      .put("flashlightAvailable", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
      .put("cameraPermission", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }

  fun state(): String = synchronized(LOCK) {
    // Reconcile missed broadcasts when the application is visible, in addition to native timers.
    wakeChecks.reconcile()
    val active = store.sessions().firstOrNull()?.let { session ->
      JSONObject(session.toString()).apply {
        remove("answer")
        remove("baseAlarm")
        val action = session.optString("action", "")
        put("totalTasks", session.getJSONObject("alarm").getJSONArray(if (action == "snooze") "snoozeTasks" else "dismissTasks").length())
      }
    }
    val pending = store.checks().map { JSONObject().put("sessionId", it.getString("sessionId"))
      .put("label", it.getJSONObject("alarm").getString("label")).put("dueAt", it.getLong("dueAt"))
      .put("deadlineAt", it.getLong("deadlineAt")).put("stage", it.getString("stage")).put("test", it.optBoolean("test")) }
    JSONObject().put("alarms", JSONArray(store.alarms())).put("active", active ?: JSONObject.NULL)
      .put("gameStats", GameScores(context).state())
      .put("wakeCheck", wakeChecks.visible() ?: JSONObject.NULL).put("wakeChecks", JSONArray(pending))
      .put("readiness", readiness()).toString()
  }

  private fun validate(alarm: JSONObject) {
    require(alarm.getString("id").matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid alarm ID" }
    require(alarm.getInt("hour") in 0..23 && alarm.getInt("minute") in 0..59) { "Invalid time" }
    require(alarm.getInt("snoozeMinutes") in 1..60) { "Snooze must be 1–60 minutes" }
    require(alarm.optInt("maxSnoozes", 0) in 0..10) { "Choose 0–10 snoozes; 0 means unlimited" }
    require(alarm.optString("flashlightMode", "off") in listOf("off", "steady", "flashing")) { "Invalid flashlight mode" }
    require(alarm.getString("label").length <= 120) { "Alarm name is too long" }
    val days = alarm.getJSONArray("days")
    require((0 until days.length()).all { days.getInt(it) in 1..7 })
    if (alarm.optBoolean("wakeEnabled")) {
      require(alarm.optInt("wakeDelayMinutes", 5) in 1..1440 && alarm.optInt("wakeResponseMinutes", 5) in 1..1440) { "Wake-up check times must be 1–1440 minutes" }
      require(alarm.optString("wakeMode", "yes_no") in listOf("yes_no", "tasks"))
      require(alarm.optString("wakeMode", "yes_no") != "tasks" || (alarm.optJSONArray("wakeTasks")?.length() ?: 0) > 0) { "Choose at least one wake-up confirmation task" }
    }
    for (key in listOf("dismissTasks", "snoozeTasks", "wakeTasks", "wakeRetryTasks")) {
      val tasks = alarm.optJSONArray(key) ?: continue
      require(tasks.length() <= 10) { "Choose at most 10 tasks per action" }
      val used = key in listOf("dismissTasks", "snoozeTasks") || (alarm.optBoolean("wakeEnabled") &&
        ((key == "wakeTasks" && alarm.optString("wakeMode", "yes_no") == "tasks") || (key == "wakeRetryTasks" && !alarm.optBoolean("wakeReuseDismissTasks", true))))
      for (i in 0 until tasks.length()) {
        val task = tasks.getJSONObject(i)
        require(task.getString("kind") in listOf("text", "math", "barcode", "breakout", "memory")) { "Unknown task" }
        if (task.getString("kind") == "breakout" && used) {
          require(task.optInt("gameSeconds", 60) in 5..3600) { "Choose 5–3600 seconds of active play" }
          require(task.optInt("gameIdleSeconds", 20) in 5..120) { "Choose a 5–120 second idle reset" }
        }
        require(task.getString("difficulty") in listOf("easy", "medium", "hard"))
        require(task.getInt("rounds") in 1..10)
        if (task.getString("kind") == "barcode") {
          require(task.getString("barcodeMode") in listOf("any", "specific"))
          require(!used || task.getString("barcodeMode") != "specific" || task.getString("barcode").isNotEmpty()) { "Scan the target barcode first" }
        }
      }
    }
  }

  fun save(json: String) = synchronized(LOCK) {
    val alarm = JSONObject(json); validate(alarm)
    alarm.remove("snoozed"); alarm.remove("wakeRetry")
    require(store.sessions().none { it.getJSONObject("alarm").getString("id") == alarm.getString("id") }) { "Finish this alarm before editing it" }
    if (alarm.getBoolean("enabled")) {
      val access = readiness()
      check(access.getBoolean("exactAlarms")) { "Allow Alarms & reminders before enabling an alarm" }
      check(access.getBoolean("notifications")) { "Allow alarm notifications before enabling an alarm" }
      check(!alarm.optBoolean("wakeEnabled") || access.getBoolean("wakeNotifications")) { "Allow wake-up check notifications before enabling this alarm" }
      checkFlashlightAccess(alarm, access)
      alarm.put("nextAt", next(alarm)); schedule(alarm)
    } else { manager.cancel(receiver(alarm.getString("id"))); alarm.remove("nextAt") }
    store.put(alarm)
    wakeChecks.cancelForAlarm(alarm.getString("id"))
  }

  fun delete(id: String) = synchronized(LOCK) {
    require(store.sessions().none { it.getJSONObject("alarm").getString("id") == id }) { "Finish this alarm before deleting it" }
    manager.cancel(receiver(id)); store.delete(id); wakeChecks.cancelForAlarm(id)
  }

  private fun next(alarm: JSONObject): Long {
    val days = alarm.getJSONArray("days")
    return Timing.next(alarm.getInt("hour"), alarm.getInt("minute"), (0 until days.length()).map { days.getInt(it) }.toSet(),
      Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()))
  }
  private fun schedule(alarm: JSONObject) {
    val show = PendingIntent.getActivity(context, 0, launchIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.getLong("nextAt"), show), receiver(alarm.getString("id")))
  }
  fun launchIntent(): Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

  fun fire(id: String) = synchronized(LOCK) {
    val alarm = store.find(id) ?: return@synchronized
    if (!alarm.getBoolean("enabled") || alarm.optLong("nextAt", Long.MAX_VALUE) > now() + 2000) return@synchronized
    wakeChecks.cancelForAlarm(id)
    enqueue(alarm, false, alarm.optBoolean("wakeRetry")); ring()
    alarm.remove("snoozed"); alarm.remove("wakeRetry")
    if (alarm.getJSONArray("days").length() > 0) { alarm.put("nextAt", next(alarm)); schedule(alarm) }
    else { alarm.put("enabled", false); alarm.remove("nextAt") }
    store.put(alarm)
  }

  fun test(json: String) = synchronized(LOCK) {
    check(store.sessions().isEmpty()) { "An alarm is already ringing" }
    val alarm = JSONObject(json); validate(alarm)
    checkFlashlightAccess(alarm, readiness())
    enqueue(alarm, true); ring()
  }
  private fun checkFlashlightAccess(alarm: JSONObject, access: JSONObject) {
    if (alarm.optString("flashlightMode", "off") == "off") return
    check(access.getBoolean("flashlightAvailable")) { "This phone has no flashlight. Choose Off to enable this alarm." }
    check(access.getBoolean("cameraPermission")) { "Allow camera access for the flashlight, or choose Off." }
  }
  fun testWake(json: String) = synchronized(LOCK) {
    check(store.sessions().isEmpty()) { "Finish the ringing alarm first" }
    val alarm = JSONObject(json); validate(alarm)
    check(alarm.optBoolean("wakeEnabled")) { "Enable wake-up checks first" }
    wakeChecks.schedule(alarm, true)
  }

  private fun enqueue(base: JSONObject, test: Boolean, wakeRetry: Boolean = false) {
    val occurrence = JSONObject(base.toString())
    if (!base.optBoolean("snoozed") && !wakeRetry) occurrence.put("snoozeCount", 0)
    val alarm = JSONObject(occurrence.toString())
    if (wakeRetry && !base.optBoolean("wakeReuseDismissTasks", true)) {
      alarm.put("dismissTasks", base.optJSONArray("wakeRetryTasks") ?: JSONArray())
    }
    val sessions = store.sessions()
    if (sessions.none { it.getJSONObject("alarm").getString("id") == alarm.getString("id") && it.getBoolean("test") == test }) {
      val audio = context.getSystemService(AudioManager::class.java)
      val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
      sessions.add(JSONObject().put("sessionId", UUID.randomUUID().toString()).put("alarm", alarm).put("baseAlarm", occurrence)
        .put("wakeRetry", wakeRetry).put("action", JSONObject.NULL).put("taskIndex", 0).put("round", 1).put("prompt", JSONObject.NULL)
        .put("kind", JSONObject.NULL).put("test", test).put("muted", false).put("originalVolume", volume)
        .put("guardVolume", VolumePolicy.guardedLevel(volume, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))))
      store.sessions(sessions)
    }
  }
  private fun retryWake(record: JSONObject) {
    enqueue(record.getJSONObject("alarm"), record.optBoolean("test"), true)
    ring()
  }
  fun wakeEvent(id: String, phase: String) = synchronized(LOCK) {
    when (phase) {
      "reminder" -> wakeChecks.remind(id)
      "dismiss_early" -> wakeChecks.dismissEarly(id)
      "due" -> wakeChecks.due(id)
      "deadline" -> wakeChecks.deadline(id)
      "yes" -> wakeChecks.respond(id, true)
      "no" -> wakeChecks.respond(id, false)
    }
  }
  fun respondWake(id: String, awake: Boolean) = synchronized(LOCK) { wakeChecks.respond(id, awake) }
  fun submitWake(id: String, challengeId: String, answer: String): Boolean = synchronized(LOCK) { wakeChecks.submit(id, challengeId, answer) }

  fun begin(sessionId: String, action: String) = synchronized(LOCK) {
    require(action in listOf("dismiss", "snooze"))
    val sessions = store.sessions(); val session = current(sessions, sessionId)
    if (session.optString("action") == action) return@synchronized
    val alarm = session.getJSONObject("alarm")
    if (action == "snooze") check(alarm.optInt("maxSnoozes", 0) == 0 || alarm.optInt("snoozeCount") < alarm.optInt("maxSnoozes")) { "Snooze limit reached. Complete your dismiss tasks." }
    if (alarm.optBoolean("shuffleTasks")) {
      val key = if (action == "snooze") "snoozeTasks" else "dismissTasks"
      alarm.put(key, TaskOrder.arrange(alarm.getJSONArray(key), true))
    }
    session.put("action", action).put("muted", false)
    val tasks = tasks(session)
    if (tasks.length() == 0) finish(sessions)
    else { SessionTasks.start(session, tasks); store.sessions(sessions); ring() }
  }
  private fun current(sessions: List<JSONObject>, id: String): JSONObject {
    val session = sessions.firstOrNull() ?: error("No active alarm")
    check(session.getString("sessionId") == id) { "This alarm session has changed" }
    return session
  }
  private fun tasks(session: JSONObject) = session.getJSONObject("alarm")
    .getJSONArray(if (session.getString("action") == "snooze") "snoozeTasks" else "dismissTasks")

  fun setMuted(sessionId: String, muted: Boolean) = synchronized(LOCK) {
    val sessions = store.sessions(); val session = current(sessions, sessionId)
    check(VolumePolicy.mayMute(session.getJSONObject("alarm").optBoolean("allowMuteWhileSolving"), !session.isNull("action") && tasks(session).length() > 0)) { "Muting is only allowed while solving tasks for this alarm" }
    session.put("muted", muted); store.sessions(sessions); ring()
  }

  fun submit(sessionId: String, challengeId: String, supplied: String): Boolean = synchronized(LOCK) {
    val sessions = store.sessions(); val session = current(sessions, sessionId)
    check(!session.isNull("action")) { "Choose dismiss or snooze first" }
    return@synchronized when (SessionTasks.submit(session, tasks(session), challengeId, supplied)) {
      SessionTasks.Result.WRONG -> false
      SessionTasks.Result.NEXT -> { store.sessions(sessions); true }
      SessionTasks.Result.COMPLETE -> { finish(sessions); true }
    }
  }

  fun gameFrame(sessionId: String, challengeId: String, check: Boolean, input: Float? = null): BreakoutFrame = synchronized(LOCK) {
    val sessions = if (check) null else store.sessions()
    val record = if (check) wakeChecks.gameRecord(sessionId) else current(sessions!!, sessionId)
    kotlin.check(record.getString("challengeId") == challengeId && record.optString("kind") == "breakout") { "This game has already advanced" }
    val taskList = if (check) record.getJSONObject("alarm").getJSONArray("wakeTasks") else tasks(record)
    val task = taskList.getJSONObject(record.getInt("taskIndex"))
    val scores = GameScores(context)
    val game = games.getOrPut(challengeId) {
      if (games.size >= 16) games.remove(games.keys.first())
      BreakoutGame(task, record, SystemClock::elapsedRealtime, now)
    }
    val frame = game.frame(input, scores.best())
    if (game.needsSave()) {
      game.checkpoint(record)
      if (frame.complete) {
        if (check) wakeChecks.completeGame(record, challengeId)
        else when (SessionTasks.completeGame(record, taskList, challengeId)) {
          SessionTasks.Result.COMPLETE -> finish(sessions!!)
          else -> store.sessions(sessions!!)
        }
        scores.record(frame.score, completed = true)
        games.remove(challengeId)
      } else {
        if (check) wakeChecks.saveGame(record) else store.sessions(sessions!!)
        scores.record(frame.score)
      }
    }
    frame
  }
  fun pauseGame(sessionId: String, challengeId: String, check: Boolean) = synchronized(LOCK) {
    val game = games[challengeId] ?: return@synchronized
    game.pause()
    val sessions = if (check) null else store.sessions()
    val record = runCatching { if (check) wakeChecks.gameRecord(sessionId) else current(sessions!!, sessionId) }.getOrNull()
      ?: return@synchronized
    if (record.optString("challengeId") != challengeId) return@synchronized
    game.checkpoint(record)
    if (check) wakeChecks.saveGame(record) else store.sessions(sessions!!)
  }

  private fun finish(sessions: MutableList<JSONObject>) {
    val session = sessions.first()
    val base = JSONObject((session.optJSONObject("baseAlarm") ?: session.getJSONObject("alarm")).toString())
    if (!session.getBoolean("test")) {
      if (session.getString("action") == "snooze") {
        check(readiness().getBoolean("exactAlarms")) { "Restore Alarms & reminders access before snoozing" }
        base.put("enabled", true).put("snoozed", true).put("wakeRetry", session.optBoolean("wakeRetry"))
          .put("snoozeCount", session.getJSONObject("alarm").optInt("snoozeCount") + 1)
          .put("nextAt", now() + base.getInt("snoozeMinutes") * 60_000L)
        schedule(base); store.put(base)
      } else wakeChecks.schedule(base)
    }
    sessions.removeAt(0); store.sessions(sessions)
    if (sessions.isEmpty()) context.stopService(Intent(context, RingingService::class.java)) else ring()
  }

  fun restore(action: String?) = synchronized(LOCK) {
    if (!readiness().getBoolean("exactAlarms")) return@synchronized
    for (alarm in store.alarms().filter { it.getBoolean("enabled") }) {
      val missedSnooze = alarm.optBoolean("snoozed") && alarm.optLong("nextAt") <= now()
      if (!alarm.optBoolean("snoozed") || missedSnooze) {
        alarm.put("nextAt", next(alarm))
        if (missedSnooze) {
          alarm.remove("snoozed"); alarm.remove("wakeRetry"); alarm.put("snoozeCount", 0)
        }
      }
      schedule(alarm); store.put(alarm)
    }
    wakeChecks.restore()
  }
  fun ring() { context.startForegroundService(Intent(context, RingingService::class.java)) }
  companion object { val LOCK = Any(); private val games = linkedMapOf<String, BreakoutGame>() }
}
