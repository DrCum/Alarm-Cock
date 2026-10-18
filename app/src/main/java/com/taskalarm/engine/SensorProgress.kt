package com.taskalarm.engine

/** Progress is fed only by the registered Android listener. UI submissions cannot complete it. */
internal class SensorProgress(val kind: String, initialSteps: Int = 0) {
  var steps = initialSteps; private set
  var lux = 0f; private set
  var heldMs = 0L; private set
  private var lastTimestamp = -1L
  private var counterBaseline: Float? = null
  private var lightAbove = false
  private var lastHoldTick = -1L
  fun pause() {
    counterBaseline = null; lightAbove = false; lastHoldTick = -1
    heldMs = 0; lux = 0f; lastTimestamp = -1
  }
  fun sample(type: Int, value: Float, timeMs: Long, threshold: Int) {
    val light = type == android.hardware.Sensor.TYPE_LIGHT
    // Multiple light changes can be delivered within one millisecond. The native listener
    // rejects duplicate hardware timestamps; do not discard a newer dark reading here.
    if (!value.isFinite() || timeMs < lastTimestamp || (!light && timeMs == lastTimestamp)) return
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
          if (!lightAbove) { heldMs = 0; lastHoldTick = timeMs }
          lightAbove = true
        } else { heldMs = 0; lightAbove = false; lastHoldTick = -1 }
      }
    }
  }
  fun advance(nowMs: Long) {
    if (kind != "light" || !lightAbove || lastHoldTick < 0) return
    // TYPE_LIGHT is an on-change sensor: silence means its last value still applies.
    // Count monotonic time while the foreground listener is active, not events.
    // Lifecycle pause clears this state and requires another reading on resume.
    val delta = nowMs - lastHoldTick
    if (delta >= 0) heldMs += delta else heldMs = 0
    lastHoldTick = nowMs
  }
}
