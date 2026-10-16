package com.taskalarm.engine

/** Progress is fed only by the registered Android listener. UI submissions cannot complete it. */
internal class SensorProgress(val kind: String, initialSteps: Int = 0) {
  var steps = initialSteps; private set
  var lux = 0f; private set
  var heldMs = 0L; private set
  private var lastTimestamp = -1L
  private var counterBaseline: Float? = null
  private var lastBright = -1L
  fun pause() { counterBaseline = null; lastBright = -1; heldMs = 0; lastTimestamp = -1 }
  fun sample(type: Int, value: Float, timeMs: Long, threshold: Int) {
    if (!value.isFinite() || timeMs <= lastTimestamp) return
    lastTimestamp = timeMs
    when (type) {
      android.hardware.Sensor.TYPE_STEP_DETECTOR -> if (value == 1f) steps++
      android.hardware.Sensor.TYPE_STEP_COUNTER -> {
        val previous = counterBaseline
        if (previous != null && value - previous in 0f..20f) steps += (value - previous).toInt()
        counterBaseline = value
      }
      android.hardware.Sensor.TYPE_LIGHT -> {
        lux = value.coerceAtLeast(0f)
        if (lux >= threshold) {
          if (lastBright >= 0 && timeMs - lastBright <= 1500) heldMs += timeMs - lastBright else heldMs = 0
          lastBright = timeMs
        } else { heldMs = 0; lastBright = -1 }
      }
    }
  }
  fun stale(nowMs: Long) { if (lastBright >= 0 && nowMs-lastBright > 1500) { lastBright = -1; heldMs = 0 } }
}
