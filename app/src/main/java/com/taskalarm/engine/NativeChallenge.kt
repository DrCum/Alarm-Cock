package com.taskalarm.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject

internal data class NativeFrame(val kind: String, val steps: Int, val lux: Float, val heldMs: Long, val maze: MazeFrame?, val unavailable: String?, val complete: Boolean)

/** Foreground-only listener and native game state, scoped to one challenge. */
internal class NativeChallenge(private val context: Context, val task: JSONObject, record: JSONObject) : SensorEventListener {
  private val manager = context.getSystemService(SensorManager::class.java)
  private val kind = task.getString("kind")
  private val progress = SensorProgress(kind, record.optInt("nativeSteps"))
  private val maze = if (kind == "maze") MazeGame(task.getString("difficulty"),record) else null
  private var sensor: Sensor? = null
  private var resumedAt = 0L
  private var lastReading = 0L
  private var eventStamp = -1L
  private var running = false
  private var unavailable: String? = null
  private var gx = 0f; private var gy = 0f
  private var offsetX = 0f; private var offsetY = 0f
  private var smoothX = 0f; private var smoothY = 0f
  private var hasReading = false
  @Synchronized fun start() {
    if (running) return
    running = true; resumedAt = SystemClock.elapsedRealtime(); lastReading = 0
    progress.pause(); maze?.pause(); eventStamp = -1; hasReading = false; unavailable = null
    if (kind == "maze" && task.optString("controlMode", "tilt") == "touch") return
    if (kind == "walk" && Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
      unavailable = "Activity recognition permission is unavailable. Use your configured fallback."; return
    }
    sensor = when (kind) {
      "walk" -> manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
      "light" -> manager.getDefaultSensor(Sensor.TYPE_LIGHT)
      else -> manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    try {
      if (sensor == null || !manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME)) unavailable = "This task's sensor is unavailable. Use your configured fallback."
      else unavailable = null
    } catch (_: SecurityException) { unavailable = "Sensor access was denied. Use your configured fallback." }
  }
  @Synchronized fun stop() { manager.unregisterListener(this); running = false; progress.pause(); maze?.pause(); gx = 0f; gy = 0f }
  @Synchronized fun calibrate() { offsetX = smoothX; offsetY = smoothY }
  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
  @Synchronized override fun onSensorChanged(event: SensorEvent) {
    if (!running || event.values.isEmpty() || event.sensor.type != sensor?.type) return
    val timestamp = event.timestamp / 1_000_000
    val now = SystemClock.elapsedRealtime()
    if (event.timestamp <= eventStamp || timestamp > now || event.timestamp < 0) return
    // An on-change light sensor may deliver its latest value with an older timestamp
    // when activated. Accept the value but start the hold at receipt, never in the past.
    if (kind != "light" && (timestamp < resumedAt || now-timestamp > 2000)) return
    val value = event.values[0]
    if (!value.isFinite()) return
    eventStamp = event.timestamp; lastReading = now; hasReading = true
    unavailable = null
    if (kind == "maze") {
      if (event.values.size < 2 || !event.values[1].isFinite()) return
      val x = event.values[0]; val y = event.values[1]
      @Suppress("DEPRECATION")
      val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
      val axes = when (rotation) { Surface.ROTATION_90 -> -y to -x; Surface.ROTATION_180 -> x to -y; Surface.ROTATION_270 -> y to x; else -> -x to y }
      smoothX = .8f*smoothX + .2f*axes.first
      smoothY = .8f*smoothY + .2f*axes.second
      gx = ((smoothX-offsetX)/4f).coerceIn(-1f,1f); gy = ((smoothY-offsetY)/4f).coerceIn(-1f,1f)
    } else {
      progress.sample(event.sensor.type, value, if(kind == "light") now else timestamp, task.optInt("lightLux",200))
    }
  }
  @Synchronized fun frame(x: Float, y: Float): NativeFrame {
    val now = SystemClock.elapsedRealtime()
    if (running && unavailable == null && !(kind == "maze" && task.optString("controlMode", "tilt") == "touch")) {
      val silence = now - maxOf(resumedAt,lastReading)
      if (silence > 15_000 && (kind != "light" || !hasReading)) unavailable = "No recent sensor response. You can use your configured fallback."
    }
    progress.advance(now)
    val touch = task.optString("controlMode", "tilt") == "touch"
    val responding = running && (touch || now-lastReading in 0..1000)
    val mazeFrame = maze?.frame(now,if (responding) { if(touch) x else gx } else 0f,if (responding) { if(touch) y else gy } else 0f)
    val complete = running && when(kind) { "walk" -> progress.steps >= task.optInt("stepTarget",20); "light" -> progress.heldMs >= task.optInt("lightHoldSeconds",3)*1000L; else -> mazeFrame?.complete == true }
    return NativeFrame(kind,progress.steps,progress.lux,progress.heldMs,mazeFrame,unavailable,complete)
  }
  @Synchronized fun checkpoint(record: JSONObject, frame: NativeFrame) {
    record.put("nativeSteps",progress.steps).put("nativeComplete",frame.complete)
    if (unavailable != null) record.put("nativeUnavailable",unavailable) else record.remove("nativeUnavailable")
    maze?.checkpoint(record)
  }
}
