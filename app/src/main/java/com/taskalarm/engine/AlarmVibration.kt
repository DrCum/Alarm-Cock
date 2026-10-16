package com.taskalarm.engine

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal class AlarmVibration(context: Context) {
  @Suppress("DEPRECATION")
  private val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
    else context.getSystemService(Vibrator::class.java)
  private var running = false
  fun update(enabled: Boolean) {
    if (!enabled) { stop(); return }
    if (running || !vibrator.hasVibrator()) return
    try {
      @Suppress("DEPRECATION")
      vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 450, 350, 1200), 0),
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
      running = true
    } catch (_: RuntimeException) { }
  }
  fun stop() {
    if (running) runCatching { vibrator.cancel() }
    running = false
  }
}
