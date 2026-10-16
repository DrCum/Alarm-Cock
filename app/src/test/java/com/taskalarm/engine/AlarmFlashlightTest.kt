package com.taskalarm.engine

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmFlashlightTest {
  private val output = mutableListOf<Boolean>()
  private val problems = mutableListOf<Boolean>()
  private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
  private fun controller(set: (Boolean) -> Unit = { output.add(it) }) =
    AlarmFlashlight(Handler(Looper.getMainLooper()), set, problems::add)

  @Test fun steadyLightRemainsOnAcrossUpdatesAndTurnsOffOnClose() {
    val light = controller()
    light.update("steady")
    advance(3000)
    light.update("steady") // Repeated service starts, including sound mute, keep the current mode.
    assertTrue(output.isNotEmpty())
    assertTrue(output.all { it })
    light.close()
    assertFalse(output.last())
    val count = output.size
    advance(5000)
    assertEquals(count, output.size)
  }
  @Test fun flashingPulsesOncePerSecondAndDoesNotRestartOnServiceUpdates() {
    val light = controller()
    light.update("flashing")
    advance(499)
    assertEquals(listOf(true), output)
    light.update("flashing")
    advance(1)
    assertEquals(listOf(true, false), output)
    advance(500)
    assertEquals(listOf(true, false, true), output)
    light.close()
    assertFalse(output.last())
  }
  @Test fun scannerPauseReleasesTheCameraUntilScanningCloses() {
    val light = controller()
    light.update("flashing")
    light.update("flashing", paused = true)
    assertFalse(output.last())
    val count = output.size
    advance(2000)
    assertEquals(count, output.size)
    light.update("flashing", paused = false)
    assertTrue(output.last())
    advance(500)
    assertFalse(output.last())
    light.close()
  }
  @Test fun cameraFailureRecoversWithoutThrowingAndStopsRetryingAfterClose() {
    var unavailable = true
    val light = controller {
      if (unavailable) throw SecurityException("Permission revoked or camera busy")
      output.add(it)
    }
    light.update("steady")
    assertEquals(listOf(true), problems)
    advance(2000)
    assertEquals(listOf(true), problems) // No repeated persistence of the same error.
    unavailable = false
    advance(1000)
    assertTrue(output.last())
    assertEquals(listOf(true, false), problems)
    light.close()
    unavailable = true
    advance(5000)
    assertEquals(listOf(true, false), problems)
  }
  @Test fun switchingToAnAlarmWithLightOffCancelsTheOldPulseLoop() {
    val light = controller()
    light.update("flashing")
    light.update("off")
    assertEquals(listOf(true, false), output)
    advance(5000)
    assertEquals(listOf(true, false), output)
    light.close()
  }
}
