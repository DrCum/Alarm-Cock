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
import android.os.PowerManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

internal class AlarmEngine(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
  private val store = AlarmStore(context)
  private val taskQueue = TaskQueue(context)
  private val boot = BootRecovery(context, now)
  private val manager = context.getSystemService(AlarmManager::class.java)
  private val wakeChecks by lazy { WakeChecks(context, now, ::retryWake) }
  private fun receiver(id: String) = PendingIntent.getBroadcast(context, 0,
    Intent(context, AlarmReceiver::class.java).setData(Uri.parse("taskalarm://alarm/$id")),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  fun readiness(): JSONObject {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val nm = context.getSystemService(NotificationManager::class.java)
    fun channelEnabled(id: String) = nm.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    val sensors = context.getSystemService(SensorManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    return JSONObject().put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(context.packageName))
      .put("stepSensor", sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null || sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null)
      .put("lightSensor", sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null)
      .put("tiltSensor", sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) != null || sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
      .put("activityPermission", Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
      .put("exactAlarms", Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
      .put("notifications", enabled && channelEnabled(RingingService.CHANNEL))
      .put("wakeNotifications", enabled && channelEnabled(WakeChecks.CHANNEL))
      .put("fullScreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
      .put("flashlightAvailable", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
      .put("cameraPermission", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      .put("bootNotifications", enabled && channelEnabled(BootRingingService.CHANNEL))
      .put("alarmVolume", context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM))
      .put("alarmVolumeMax", context.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_ALARM))
      .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
  }

  fun state(): String = synchronized(LOCK) {
    // Reconcile missed broadcasts when the application is visible, in addition to native timers.
    wakeChecks.reconcile()
    val queue = taskQueue.entries()
    val focus = queue.firstOrNull()
    val active = store.sessions().firstOrNull()?.takeIf { focus?.kind == "alarm" && focus.sessionId == it.getString("sessionId") }?.let { session ->
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
      .put("deliveryTest", JSONObject(context.getSharedPreferences("task_alarm_health", Context.MODE_PRIVATE).getString("delivery", "{}")!!))
      .put("gameStats", GameScores(context).state())
      .put("wakeCheck", wakeChecks.visible() ?: JSONObject.NULL).put("wakeChecks", JSONArray(pending))
      .put("pendingTasks", JSONArray(queue.drop(1).map { it.json() }))
      .put("recovery", boot.summary())
      .put("readiness", readiness()).toString()
  }

  private fun validate(alarm: JSONObject) {
    require(alarm.getString("id").matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid alarm ID" }
    require(alarm.getInt("hour") in 0..23 && alarm.getInt("minute") in 0..59) { "Invalid time" }
    require(alarm.getInt("snoozeMinutes") in 1..60) { "Snooze must be 1–60 minutes" }
    EscalationPolicy.validate(alarm)
    WakeUpPolicy.validate(alarm)
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
      val used = key == "dismissTasks" || (key == "snoozeTasks" && alarm.optBoolean("snoozeEnabled", true)) || (alarm.optBoolean("wakeEnabled") &&
        ((key == "wakeTasks" && alarm.optString("wakeMode", "yes_no") == "tasks") || (key == "wakeRetryTasks" && !alarm.optBoolean("wakeReuseDismissTasks", true))))
      for (i in 0 until tasks.length()) {
        val task = tasks.getJSONObject(i)
        require(task.getString("kind") in listOf("text", "math", "barcode", "breakout", "memory", "maze", "walk", "light", "photo")) { "Unknown task" }
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

  private val edits by lazy { AlarmEdits(context, { save(it, true) }, ::delete, now) }
  fun beginEdit(json: String): String = synchronized(LOCK) { edits.begin(json) }
  fun editStatus(token: String): String = synchronized(LOCK) { edits.status(token) }
  fun saveEdit(token: String, json: String): String = synchronized(LOCK) { edits.save(token,json) }
  fun revertEdit(token: String, opening: Boolean): String = synchronized(LOCK) { edits.revert(token,opening) }
  fun discardEdit(token: String): Boolean = synchronized(LOCK) { edits.discard(token) }
  fun closeEdit(token: String) = synchronized(LOCK) { edits.close(token) }

  fun save(json: String, preserveOccurrence: Boolean = false) = synchronized(LOCK) {
    val alarm = JSONObject(json); validate(alarm)
    val previous = store.find(alarm.getString("id"))
    if(previous?.optBoolean("skipOneTime") == true && !previous.getBoolean("enabled") && alarm.getBoolean("enabled")) { alarm.remove("skipAt"); alarm.remove("skipDate"); alarm.remove("skipOneTime") }
    val scheduleOnly = previous != null && listOf("pauseUntil","skipAt","skipOneTime","skipDate").any { previous.opt(it)?.toString() != alarm.opt(it)?.toString() } && com.taskalarm.app.AlarmDraft.from(previous).copy(pauseUntil=alarm.optLong("pauseUntil"),skipAt=alarm.optLong("skipAt"),skipOneTime=alarm.optBoolean("skipOneTime"),skipDate=alarm.optString("skipDate")) == com.taskalarm.app.AlarmDraft.from(alarm)
    if((scheduleOnly || preserveOccurrence) && previous?.optBoolean("snoozed") == true) {
      listOf("snoozed","wakeRetry","snoozeCount","escalationLatched","nextAt").forEach { key -> if(previous.has(key)) alarm.put(key,previous.get(key)) }
    } else { alarm.remove("snoozed"); alarm.remove("wakeRetry") }
    require(store.sessions().none { it.getJSONObject("alarm").getString("id") == alarm.getString("id") }) { "Finish this alarm before editing it" }
    if (alarm.getBoolean("enabled")) {
      val access = readiness()
      check(access.getBoolean("exactAlarms")) { "Allow Alarms & reminders before enabling an alarm" }
      check(access.getBoolean("notifications")) { "Allow alarm notifications before enabling an alarm" }
      check(!alarm.optBoolean("wakeEnabled") || access.getBoolean("wakeNotifications")) { "Allow wake-up check notifications before enabling this alarm" }
      checkFlashlightAccess(alarm, access)
      if(!alarm.optBoolean("snoozed")) alarm.put("nextAt", next(alarm))
      schedule(alarm)
    } else { manager.cancel(receiver(alarm.getString("id"))); alarm.remove("nextAt") }
    store.put(alarm)
    if(!scheduleOnly) wakeChecks.cancelForAlarm(alarm.getString("id"))
  }

  fun duplicate(id: String, count: Int, intervalMinutes: Int, expected: String, startAt: Long): String = synchronized(LOCK) {
    val source = com.taskalarm.app.AlarmDraft.from(store.find(id) ?: error("Alarm no longer exists"))
    check(source == com.taskalarm.app.AlarmDraft.from(JSONObject(expected))) { "This alarm changed. Reopen Duplicate to review it." }
    check(startAt > now() + 1000 && next(source.json()) == startAt) { "The starting occurrence changed. Reopen Duplicate to review it." }
    val copies = AlarmCopies.create(source, count, intervalMinutes, startAt).map { it.json() }
    val access = readiness()
    copies.forEach { copy ->
      validate(copy)
      if(copy.getBoolean("enabled")) {
        check(access.getBoolean("exactAlarms") && access.getBoolean("notifications")) { "Allow alarms and notifications first" }
        check(!copy.optBoolean("wakeEnabled") || access.getBoolean("wakeNotifications")) { "Allow wake-up check notifications first" }
        checkFlashlightAccess(copy, access)
        copy.put("nextAt", next(copy))
      }
    }
    try {
      copies.filter { it.getBoolean("enabled") }.forEach(::schedule)
      store.putAll(copies)
    } catch(failure: Exception) {
      copies.forEach { manager.cancel(receiver(it.getString("id"))) }
      throw failure
    }
    JSONArray(copies).toString()
  }

  fun preview(json: String): Long = next(JSONObject(json))
  fun skipNext(id: String, undo: Boolean = false) = synchronized(LOCK) {
    val alarm = store.find(id) ?: error("Alarm no longer exists")
    check(!alarm.optBoolean("snoozed")) { "Finish the active snooze before changing a skip" }
    if (undo) {
      if (alarm.optBoolean("skipOneTime")) alarm.put("enabled", true)
      alarm.remove("skipAt"); alarm.remove("skipOneTime"); alarm.remove("skipDate")
    } else {
      check(alarm.getBoolean("enabled")) { "Enable this alarm first" }
      check(alarm.optLong("skipAt") <= now()) { "Undo the current skip before choosing another" }
      val skipped=next(alarm)
      alarm.put("skipAt", skipped).put("skipDate",Instant.ofEpochMilli(skipped).atZone(ZoneId.systemDefault()).toLocalDate().toString())
      if (alarm.getJSONArray("days").length() == 0) alarm.put("enabled", false).put("skipOneTime", true)
    }
    if (alarm.getBoolean("enabled")) {
      check(readiness().getBoolean("exactAlarms")) { "Restore alarm access first" }
      alarm.put("nextAt", next(alarm)); schedule(alarm)
    } else { manager.cancel(receiver(id)); alarm.remove("nextAt") }
    store.put(alarm)
  }
  private fun deliveryStatus(status: String, at: Long) {
    context.getSharedPreferences("task_alarm_health", Context.MODE_PRIVATE).edit()
      .putString("delivery", JSONObject().put("status", status).put("scheduledAt", at).put("updatedAt", now()).toString()).commit()
  }
  fun testDelivery(delaySeconds: Int = 30) = synchronized(LOCK) {
    require(delaySeconds in listOf(30, 120))
    check(readiness().getBoolean("exactAlarms") && readiness().getBoolean("notifications")) { "Allow alarms and notifications first" }
    val old = store.alarms().filter { it.optBoolean("deliveryTest") }
    old.forEach { manager.cancel(receiver(it.getString("id"))); store.delete(it.getString("id")) }
    val alarm = com.taskalarm.app.AlarmDraft(label = "Alarm delivery test", days = emptySet(), dismissTasks = emptyList()).json()
      .put("deliveryTest", true).put("nextAt", now() + delaySeconds * 1000L)
    schedule(alarm); store.put(alarm); deliveryStatus("scheduled", alarm.getLong("nextAt"))
  }

  fun delete(id: String) = synchronized(LOCK) {
    require(store.sessions().none { it.getJSONObject("alarm").getString("id") == id }) { "Finish this alarm before deleting it" }
    manager.cancel(receiver(id)); store.delete(id); wakeChecks.cancelForAlarm(id)
  }

  private fun next(alarm: JSONObject): Long {
    val days = alarm.getJSONArray("days")
    return Timing.next(alarm.getInt("hour"), alarm.getInt("minute"), (0 until days.length()).map { days.getInt(it) }.toSet(),
      Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()), alarm.optLong("pauseUntil"), alarm.optLong("skipAt"), alarm.optString("skipDate"))
  }
  private fun schedule(alarm: JSONObject) {
    val show = PendingIntent.getActivity(context, 0, launchIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.getLong("nextAt"), show), receiver(alarm.getString("id")))
  }
  fun launchIntent(): Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

  fun fire(id: String, startService: Boolean = true) = synchronized(LOCK) {
    val alarm = store.find(id) ?: return@synchronized
    if (!alarm.getBoolean("enabled") || alarm.optLong("nextAt", Long.MAX_VALUE) > now() + 2000) return@synchronized
    boot.event(if(startService) "alarm_received" else "alarm_recovered", if(startService) "Scheduled alarm receiver ran" else "Overdue alarm recovered without skipping its occurrence", id)
    wakeChecks.cancelForAlarm(id, scheduledOnly = true)
    if (alarm.optBoolean("deliveryTest")) {
      deliveryStatus(if(startService) "delivered" else "recovered", alarm.optLong("nextAt"))
      enqueue(alarm, true); store.delete(id); if(startService) ring(); return@synchronized
    }
    enqueue(alarm, false, alarm.optBoolean("wakeRetry")); if(startService) ring()
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
  fun testEscalation(json: String) = synchronized(LOCK) {
    check(store.sessions().isEmpty()) { "An alarm is already ringing" }
    val alarm = JSONObject(json); validate(alarm); checkFlashlightAccess(alarm, readiness())
    check(alarm.optBoolean("escalationEnabled")) { "Enable escalation first" }
    enqueue(alarm, true, forceEscalation = true); ring()
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

  private fun enqueue(base: JSONObject, test: Boolean, wakeRetry: Boolean = false, forceEscalation: Boolean = false) {
    val occurrence = JSONObject(base.toString())
    if (!base.optBoolean("snoozed") && !wakeRetry) {
      occurrence.put("snoozeCount", 0); occurrence.remove("escalationLatched")
    }
    if (forceEscalation) occurrence.put("escalationLatched", true)
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
        .put("kind", JSONObject.NULL).put("test", test).put("queuedAt", now()).put("muted", false).put("originalVolume", volume)
        .put("guardVolume", VolumePolicy.guardedLevel(volume, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))))
      store.sessions(sessions)
      taskQueue.entries()
    }
  }
  private fun retryWake(record: JSONObject) {
    enqueue(record.optJSONObject("baseAlarm") ?: record.getJSONObject("alarm"), record.optBoolean("test"), true)
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
    val alarm = session.getJSONObject("alarm")
    if (action == "snooze") check(alarm.optBoolean("snoozeEnabled", true)) { "Snooze is disabled for this alarm. Complete your dismiss tasks." }
    if (session.optString("action") == action) return@synchronized
    EscalationPolicy.refresh(session, AlarmClockStamp.current(context, now()))
    if (action == "snooze") check(alarm.optInt("maxSnoozes", 0) == 0 || alarm.optInt("snoozeCount") < alarm.optInt("maxSnoozes")) { "Snooze limit reached. Complete your dismiss tasks." }
    val key = if (action == "snooze") "snoozeTasks" else "dismissTasks"
    val base = session.optJSONObject("baseAlarm") ?: alarm
    val original = if (action == "dismiss" && session.optBoolean("wakeRetry") && !base.optBoolean("wakeReuseDismissTasks", true)) base.optJSONArray("wakeRetryTasks") ?: JSONArray() else base.getJSONArray(key)
    alarm.put(key, TaskOrder.arrange(WakeUpPolicy.effective(original, alarm), alarm.optBoolean("shuffleTasks")))
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
      SessionTasks.Result.NEXT -> { store.sessions(sessions); ring(); true }
      SessionTasks.Result.COMPLETE -> { finish(sessions); true }
    }
  }

  private data class PendingPhoto(val sessionId: String, val challengeId: String, val wake: Boolean, val startedAt: Long)
  // Receipts are transient: a process restart always requires another camera capture.
  private val pendingPhotos = mutableMapOf<String, PendingPhoto>()
  fun startPhotoCapture(sessionId: String, challengeId: String, wake: Boolean): String = synchronized(LOCK) {
    val record = if(wake) wakeChecks.gameRecord(sessionId) else current(store.sessions(), sessionId)
    check(record.optString("challengeId") == challengeId && record.optString("kind") == "photo") { "This photo task has changed" }
    pendingPhotos.clear()
    val token = UUID.randomUUID().toString()
    pendingPhotos[token] = PendingPhoto(sessionId, challengeId, wake, SystemClock.elapsedRealtime())
    token
  }
  fun cancelPhotoCapture(token: String) = synchronized(LOCK) { pendingPhotos.remove(token); Unit }
  fun completePhotoCapture(token: String) = synchronized(LOCK) {
    val request = pendingPhotos.remove(token) ?: error("Take a fresh photo for this round")
    check(SystemClock.elapsedRealtime() - request.startedAt in 0L..30_000L) { "Photo capture expired. Take another photo." }
    val sessions = if(request.wake) null else store.sessions()
    val record = if(request.wake) wakeChecks.gameRecord(request.sessionId) else current(sessions!!, request.sessionId)
    if(request.wake) wakeChecks.completePhoto(record, request.challengeId)
    else when(SessionTasks.completePhoto(record, tasks(record), request.challengeId)) {
      SessionTasks.Result.COMPLETE -> finish(sessions!!)
      else -> { store.sessions(sessions!!); ring() }
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
          else -> { store.sessions(sessions!!); ring() }
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

  private fun nativeRecord(sessionId: String, wake: Boolean): JSONObject =
    if(wake) wakeChecks.gameRecord(sessionId) else current(store.sessions(),sessionId)
  fun startNative(sessionId: String, challengeId: String, wake: Boolean) = synchronized(LOCK) {
    val record = nativeRecord(sessionId,wake)
    check(record.optString("challengeId") == challengeId && record.optString("kind") in listOf("maze", "walk", "light")) { "Task changed" }
    val task = (if(wake) record.getJSONObject("alarm").getJSONArray("wakeTasks") else tasks(record)).getJSONObject(record.getInt("taskIndex"))
    nativeGames.values.forEach { it.stop() }
    val runtime = nativeGames.getOrPut(challengeId) {
      if(nativeGames.size >= 16) { nativeGames.values.first().stop(); nativeGames.remove(nativeGames.keys.first()) }
      NativeChallenge(context,task,record)
    }
    runtime.start()
  }
  fun stopNative(challengeId: String) = synchronized(LOCK) {
    val runtime = nativeGames.remove(challengeId) ?: return@synchronized
    val frame = runtime.frame(0f,0f)
    val sessions=store.sessions()
    val record=sessions.firstOrNull { it.optString("challengeId")==challengeId }
    if(record != null) { runtime.checkpoint(record,frame); store.sessions(sessions) }
    else {
      val checks=store.checks()
      checks.firstOrNull { it.optString("challengeId")==challengeId }?.let { runtime.checkpoint(it,frame); store.checks(checks) }
    }
    runtime.stop()
  }
  fun calibrateNative(challengeId: String) = synchronized(LOCK) { nativeGames[challengeId]?.calibrate() }
  fun nativeFrame(sessionId: String, challengeId: String, wake: Boolean, x: Float = 0f, y: Float = 0f): NativeFrame = synchronized(LOCK) {
    val sessions = if(wake) null else store.sessions()
    val record = if(wake) wakeChecks.gameRecord(sessionId) else current(sessions!!,sessionId)
    check(record.optString("challengeId") == challengeId) { "Task changed" }
    val runtime = nativeGames[challengeId] ?: error("Task is paused")
    val frame = runtime.frame(x,y)
    val time = SystemClock.elapsedRealtime()
    if(frame.complete || time - record.optLong("nativeSavedAt",-1000) >= 1000 || record.optLong("nativeSavedAt") > time || frame.unavailable != record.optString("nativeUnavailable").ifEmpty { null }) {
      runtime.checkpoint(record,frame); record.put("nativeSavedAt",time)
      if(frame.complete) {
        runtime.stop(); nativeGames.remove(challengeId)
        if(wake) wakeChecks.completeNative(record,challengeId)
        else when(SessionTasks.completeNative(record,tasks(record),challengeId)) {
          SessionTasks.Result.COMPLETE -> finish(sessions!!)
          else -> { store.sessions(sessions!!); ring() }
        }
      } else if(wake) wakeChecks.saveGame(record) else store.sessions(sessions!!)
    }
    frame
  }
  fun nativeFallback(sessionId: String, challengeId: String, wake: Boolean) = synchronized(LOCK) {
    val sessions = if(wake) null else store.sessions()
    val record = if(wake) wakeChecks.gameRecord(sessionId) else current(sessions!!,sessionId)
    check(record.optString("challengeId") == challengeId && record.optString("nativeUnavailable").isNotEmpty()) { "Fallback becomes available if the sensor cannot respond" }
    val list = if(wake) record.getJSONObject("alarm").getJSONArray("wakeTasks") else tasks(record)
    val task = list.getJSONObject(record.getInt("taskIndex"))
    check(task.getString("kind") in listOf("walk", "light", "maze"))
    nativeGames.remove(challengeId)?.stop()
    task.put("kind",task.optString("fallbackKind","math"))
    SessionTasks.challenge(record,list)
    if(wake) wakeChecks.saveGame(record) else { store.sessions(sessions!!); ring() }
  }

  private fun finish(sessions: MutableList<JSONObject>) {
    val session = sessions.first()
    val base = JSONObject((session.optJSONObject("baseAlarm") ?: session.getJSONObject("alarm")).toString())
    store.find(base.getString("id"))?.let { latest ->
      listOf("pauseUntil","skipAt","skipOneTime","skipDate").forEach { key -> if(latest.has(key)) base.put(key,latest.get(key)) else base.remove(key) }
    }
    nativeGames.remove(session.optString("challengeId"))?.stop()
    if (base.optBoolean("deliveryTest")) deliveryStatus("acknowledged", base.optLong("nextAt"))
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
    taskQueue.entries(); wakeChecks.activateNext()
    if (sessions.isEmpty()) context.stopService(Intent(context, RingingService::class.java)) else ring()
  }

  fun restore(action: String?) = synchronized(LOCK) {
    boot.sync()
    if (!readiness().getBoolean("exactAlarms")) { boot.restored(action, 0, 0, 0, true); return@synchronized }
    val clockChanged = action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    var scheduled = 0; var recovered = 0; var skipped = 0
    for (alarm in store.alarms().filter { it.getBoolean("enabled") }) {
      val due = alarm.optLong("nextAt")
      val recent = due > 0 && due <= now() && now() - due <= BootRecovery.CATCH_UP_MS
      if(!clockChanged && due > 0 && due <= now() && (recent || boot.wasTriggered(alarm.getString("id")))) {
        // Exact-alarm delivery, not the boot broadcast, starts the media foreground service.
        fire(alarm.getString("id"), startService = false)
        recovered++
      } else if(alarm.optBoolean("deliveryTest")) {
        if(due > now()) { schedule(alarm); scheduled++ }
        else { store.delete(alarm.getString("id")); deliveryStatus("not observed", due); skipped++ }
      } else {
        if(clockChanged || due <= 0 || due <= now()) {
          if(due > 0 && due <= now()) { skipped++; boot.event("missed_too_old", "Missed occurrence was outside the 12-hour catch-up window", alarm.getString("id")) }
          if(alarm.optBoolean("snoozed")) {
            alarm.remove("snoozed"); alarm.remove("wakeRetry"); alarm.remove("escalationLatched"); alarm.put("snoozeCount", 0)
          }
          alarm.put("nextAt", next(alarm)); store.put(alarm)
        }
        schedule(alarm); scheduled++
      }
    }
    if(store.sessions().isNotEmpty()) { boot.scheduleResume(); recovered++ }
    wakeChecks.restore()
    boot.restored(action, scheduled, recovered, skipped)
  }
  fun resumeRinging() = synchronized(LOCK) {
    val sessions = store.sessions()
    if(sessions.isNotEmpty()) {
      sessions.forEach { it.put("muted", false) }
      store.sessions(sessions); ring()
    }
  }
  fun ring() { context.startForegroundService(Intent(context, RingingService::class.java)) }
  companion object { val LOCK = Any(); private val games = linkedMapOf<String, BreakoutGame>(); private val nativeGames = linkedMapOf<String, NativeChallenge>() }
}
