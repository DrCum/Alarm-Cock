package com.taskalarm.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Sound only while credential-protected task data cannot be read. Never dismisses an alarm. */
class BootRingingService : Service() {
  private var player: MediaPlayer? = null
  private var tone: ToneGenerator? = null
  private val handler = Handler(Looper.getMainLooper())
  private val beep = object : Runnable {
    override fun run() { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000); handler.postDelayed(this, 1500) }
  }
  override fun onBind(intent: Intent?) = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val boot = BootRecovery(this)
    if(!boot.hasRinging()) { stopSelf(); return START_NOT_STICKY }
    BootRecovery.channel(this)
    val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Task Alarm is ringing").setContentText("Unlock your phone to complete your alarm tasks")
      .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
      .setOngoing(true).setContentIntent(boot.open()).setFullScreenIntent(boot.open(), true).build()
    ServiceCompat.startForeground(this, 1002, notification, if(Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    boot.setAudioRunning(true)
    if(player == null && tone == null) {
      try {
        val audio = MediaPlayer()
        player = audio
        audio.also {
          audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
          audio.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
          audio.setDataSource(this, Uri.parse(BuiltinSounds.prepare(createDeviceProtectedStorageContext(), "beacon")))
          audio.isLooping = true
          audio.setOnErrorListener { _, _, _ -> fallback(); true }
          audio.prepare(); audio.start()
        }
      } catch(_: Exception) { fallback() }
      boot.event("locked_audio_started", "Pre-unlock alarm audio started")
    }
    return START_STICKY
  }
  private fun fallback() {
    player?.setOnErrorListener(null); player?.release(); player = null
    if(tone == null) {
      tone = ToneGenerator(AudioManager.STREAM_ALARM, 100); handler.post(beep)
    }
  }
  override fun onDestroy() {
    handler.removeCallbacks(beep); tone?.release(); tone = null
    player?.setOnErrorListener(null); player?.release(); player = null
    BootRecovery(this).setAudioRunning(false)
    super.onDestroy()
  }
  companion object { const val CHANNEL = "task-alarm-direct-boot-v1" }
}
