package com.taskalarm.engine

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject

class RingingService : Service() {
  private var player: MediaPlayer? = null
  private var focus: AudioFocusRequest? = null
  private var tone: ToneGenerator? = null
  private var sessionId: String? = null
  private var muted = false
  private var guardRunning = false
  private var guardedVolume = 0
  private var restoreVolume: Int? = null
  private var soundKey: String? = null
  private var escalatedAudio = false
  private var pulseRunning = false
  private var escalationWakeLock: PowerManager.WakeLock? = null
  private var scannerActive = false
  private var flashlightWakeLock: PowerManager.WakeLock? = null
  private val vibration by lazy { AlarmVibration(this) }
  private val handler = Handler(Looper.getMainLooper())
  private val flashlight by lazy { AlarmFlashlight(handler, CameraTorch(this)::set) { blocked ->
    synchronized(AlarmEngine.LOCK) {
      val store = AlarmStore(this)
      val sessions = store.sessions()
      if (sessions.firstOrNull()?.getString("sessionId") == sessionId) {
        sessions.first().put("flashlightBlocked", blocked); store.sessions(sessions)
      }
    }
  } }
  private val fallbackBeep = object : Runnable {
    override fun run() {
      if (!muted) tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
      handler.postDelayed(this, 1500)
    }
  }
  private val volumeGuard = object : Runnable {
    override fun run() {
      if (!guardRunning) return
      val audio = getSystemService(AudioManager::class.java)
      try {
        check(!audio.isVolumeFixed) { "This device controls its own volume" }
        val level = guardedVolume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != level) audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
      } catch (_: RuntimeException) {
        guardRunning = false
        synchronized(AlarmEngine.LOCK) {
          val store = AlarmStore(this@RingingService)
          val sessions = store.sessions()
          if (sessions.firstOrNull()?.getString("sessionId") == sessionId) {
            sessions.first().put("volumeGuardBlocked", true); store.sessions(sessions)
          }
        }
      }
      if (guardRunning) handler.postDelayed(this, 250)
    }
  }
  private val escalationPulse = object : Runnable {
    override fun run() {
      if (!pulseRunning) return
      val session = activeRecord() ?: return
      updateAudio(session)
      val delay = if (session.optBoolean("escalated"))
        minOf(1000L, EscalationPolicy.untilNextTone(session, AlarmClockStamp.current(this@RingingService))) else 1000L
      handler.postDelayed(this, delay)
    }
  }
  private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

  override fun onBind(intent: Intent?) = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    var session = synchronized(AlarmEngine.LOCK) { AlarmStore(this).sessions().firstOrNull() }
    if (session == null) { releaseAudio(); releaseFlashlight(); vibration.stop(); stopSelf(); return START_NOT_STICKY }
    val alarm = session.getJSONObject("alarm")
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Active alarms and their task screen"; setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    })
    val open = PendingIntent.getActivity(this, 0, AlarmEngine(this).launchIntent(),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(alarm.getString("label"))
      .setContentText("Open to complete your alarm tasks").setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true).setContentIntent(open).setFullScreenIntent(open, true).build()
    val serviceType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    ServiceCompat.startForeground(this, 1001, notification, serviceType)
    if (intent?.hasExtra("scannerActive") == true) scannerActive = intent.getBooleanExtra("scannerActive", false)
    if (sessionId != session.getString("sessionId")) {
      releaseFlashlight()
      vibration.stop()
      releaseAudio(); sessionId = session.getString("sessionId")
      session = activeRecord(startAudio = true) ?: run { stopSelf(); return START_NOT_STICKY }
      val audio = getSystemService(AudioManager::class.java)
      muted = session.optBoolean("muted")
      if (alarm.optBoolean("protectVolume")) {
        restoreVolume = session.optInt("originalVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
        guardedVolume = session.optInt("guardVolume", VolumePolicy.guardedLevel(restoreVolume!!, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)))
        guardRunning = true; handler.post(volumeGuard)
      }
      focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
    }
    session = activeRecord() ?: run { stopSelf(); return START_NOT_STICKY }
    updateAudio(session)
    if (alarm.optBoolean("escalationEnabled") && !pulseRunning) {
      escalationWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaskAlarm:escalation").apply { acquire() }
      pulseRunning = true; handler.postDelayed(escalationPulse, 1000)
    }
    val flashlightMode = alarm.optString("flashlightMode", "off")
    if (flashlightMode != "off" && !scannerActive) {
      if (flashlightWakeLock == null) flashlightWakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaskAlarm:flashlight").apply { acquire() }
    } else {
      flashlightWakeLock?.let { if (it.isHeld) it.release() }; flashlightWakeLock = null
    }
    flashlight.update(flashlightMode, scannerActive)
    vibration.update(alarm.optBoolean("vibrate"))
    return START_STICKY
  }

  private fun activeRecord(startAudio: Boolean = false): JSONObject? = synchronized(AlarmEngine.LOCK) {
    val store = AlarmStore(this)
    val sessions = store.sessions()
    val session = sessions.firstOrNull()?.takeIf { it.getString("sessionId") == sessionId } ?: return@synchronized null
    var changed = false
    if (startAudio && !session.optBoolean("audioStarted")) {
      // Queued alarms capture their own starting volume after the previous alarm restores it.
      val audio = getSystemService(AudioManager::class.java)
      val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
      session.put("originalVolume", volume).put("guardVolume", VolumePolicy.guardedLevel(volume, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)))
        .put("audioStarted", true)
      changed = true
    }
    changed = EscalationPolicy.refresh(session, AlarmClockStamp.current(this)) || changed
    if (changed) store.sessions(sessions)
    session
  }
  private fun audioStatus(fallback: Boolean, toneName: String?) = synchronized(AlarmEngine.LOCK) {
    val store = AlarmStore(this); val sessions = store.sessions()
    val session = sessions.firstOrNull()?.takeIf { it.getString("sessionId") == sessionId } ?: return@synchronized
    if (session.optBoolean("soundFallback") != fallback || session.optString("escalationToneName") != toneName.orEmpty()) {
      session.put("soundFallback", fallback).put("escalationToneName", toneName ?: "")
      store.sessions(sessions)
    }
  }
  private fun updateAudio(session: JSONObject) {
    val alarm = session.getJSONObject("alarm")
    muted = session.optBoolean("muted")
    var name: String? = null
    val escalated = session.optBoolean("escalated") && alarm.optBoolean("escalationEnabled")
    if (escalated && !escalatedAudio) {
      val audio = getSystemService(AudioManager::class.java)
      restoreVolume = restoreVolume ?: session.optInt("originalVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
      guardedVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
      guardRunning = true; handler.removeCallbacks(volumeGuard); handler.post(volumeGuard)
      escalatedAudio = true
    }
    val raw: String
    val key: String
    if (escalated) {
      val tones = alarm.getJSONArray("escalationTones")
      val hop = EscalationPolicy.hop(session, AlarmClockStamp.current(this))
      val selected = tones.getJSONObject((hop % tones.length()).toInt())
      raw = selected.getString("uri"); name = selected.optString("name", "Alarm tone")
      key = "playlist:$hop:$raw"
    } else {
      raw = if (alarm.optBoolean("normalize")) alarm.optString("playbackUri", alarm.optString("soundUri")) else alarm.optString("soundUri")
      key = "base:$raw"
    }
    if (soundKey != key) {
      stopPlayback(); soundKey = key
      val chosen = runCatching {
        if (raw.startsWith("builtin:")) Uri.parse(BuiltinSounds.prepare(this, raw.removePrefix("builtin:")))
        else raw.takeIf { it.isNotBlank() }?.let(Uri::parse)
      }.getOrNull()
      val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      var fallback = false
      if (!play(chosen ?: default)) {
        fallback = true
        if (chosen == null || !play(default)) startFallback()
      }
      audioStatus(fallback, name)
    }
    player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
    if (muted) tone?.stopTone()
  }
  private fun startFallback() {
    handler.removeCallbacks(fallbackBeep); tone?.release()
    tone = ToneGenerator(AudioManager.STREAM_ALARM, 100); handler.post(fallbackBeep)
  }

  private fun play(uri: Uri): Boolean = try {
    val candidate = MediaPlayer()
    player = candidate
    candidate.setAudioAttributes(attributes)
    candidate.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
    candidate.setDataSource(this, uri)
    candidate.isLooping = true
    candidate.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
    candidate.setOnErrorListener { _, _, _ ->
      if (player === candidate) {
        candidate.release(); player = null; startFallback()
        audioStatus(true, null)
      }
      true
    }
    candidate.prepare(); candidate.start(); true
  } catch (_: Exception) { player?.release(); player = null; false }

  private fun stopPlayback() {
    handler.removeCallbacks(fallbackBeep); tone?.release(); tone = null
    player?.setOnErrorListener(null); player?.release(); player = null
  }
  private fun releaseAudio() {
    pulseRunning = false; handler.removeCallbacks(escalationPulse)
    escalationWakeLock?.let { if (it.isHeld) it.release() }; escalationWakeLock = null
    handler.removeCallbacks(volumeGuard); guardRunning = false
    stopPlayback(); soundKey = null; escalatedAudio = false
    focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    restoreVolume?.let {
      try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } catch (_: RuntimeException) { }
    }
    restoreVolume = null
  }

  private fun releaseFlashlight() {
    flashlight.close()
    flashlightWakeLock?.let { if (it.isHeld) it.release() }; flashlightWakeLock = null
  }

  override fun onDestroy() { releaseFlashlight(); vibration.stop(); releaseAudio(); super.onDestroy() }
  companion object { const val CHANNEL = "task-alarm-ringing-v1" }
}
