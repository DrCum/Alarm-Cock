package com.taskalarm.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler

/** Owns only the torch, never opens a camera or captures images. */
internal class CameraTorch(private val context: Context) {
  private val manager = context.getSystemService(CameraManager::class.java)
  private var cameraId: String? = null

  fun set(enabled: Boolean) {
    if (!enabled && cameraId == null) return
    check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Camera permission is unavailable" }
    val id = cameraId ?: manager.cameraIdList.filter {
      manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }.sortedBy {
      if (manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
    }.firstOrNull()?.also { cameraId = it } ?: error("No flashlight is available")
    manager.setTorchMode(id, enabled)
  }
}

/** Service-owned pulse loop, independent of the activity and audio mute state. */
internal class AlarmFlashlight(
  private val handler: Handler,
  private val setTorch: (Boolean) -> Unit,
  private val onBlocked: (Boolean) -> Unit
) {
  private var mode = "off"
  private var paused = false
  private var running = false
  private var lit = false
  private var touched = false
  private var blocked: Boolean? = null
  private val pulse = object : Runnable {
    override fun run() {
      if (!running) return
      val target = mode == "steady" || !lit
      var failed = false
      try {
        touched = true
        setTorch(target); lit = target
        report(false)
      } catch (_: Exception) {
        failed = true
        // Do not let an unavailable camera interrupt the alarm or task flow.
        runCatching { setTorch(false) }; lit = false
        report(true)
      }
      if (running) handler.postDelayed(this, if (failed || mode == "steady") 1000L else 500L)
    }
  }
  private fun report(value: Boolean) {
    if (blocked != value) { blocked = value; onBlocked(value) }
  }
  fun update(mode: String, paused: Boolean = false) {
    require(mode in listOf("off", "steady", "flashing"))
    if (this.mode == mode && this.paused == paused) return
    stop()
    this.mode = mode; this.paused = paused
    if (mode != "off" && !paused) { running = true; pulse.run() }
  }
  private fun stop() {
    running = false; handler.removeCallbacks(pulse)
    if (touched) runCatching { setTorch(false) }
    touched = false; lit = false
  }
  fun close() { stop(); mode = "off"; paused = false; blocked = null }
}
