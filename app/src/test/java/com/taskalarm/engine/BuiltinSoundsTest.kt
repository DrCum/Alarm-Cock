package com.taskalarm.engine

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuiltinSoundsTest {
  @Test fun presetsAreLocalValidNonSilentPCMAndPreparationIsStable() {
    val context: Application = RuntimeEnvironment.getApplication()
    for (id in BuiltinSounds.names.keys) {
      val uri = BuiltinSounds.prepare(context, id)
      val file = File(Uri.parse(uri).path!!)
      val bytes = file.readBytes()
      val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
      assertEquals(1, wave.getShort(22).toInt()); assertEquals(16000, wave.getInt(24))
      assertEquals(16, wave.getShort(34).toInt()); assertEquals(96000, wave.getInt(40))
      var peak = 0
      for (index in 44 until bytes.size step 2) peak = maxOf(peak, abs(wave.getShort(index).toInt()))
      assertTrue(peak in 1000..32766)
      assertEquals(uri, BuiltinSounds.prepare(context, id))
      assertArrayEquals(bytes, file.readBytes())
    }
  }
  @Test fun arbitraryAssetPathsAreRejected() {
    val context: Application = RuntimeEnvironment.getApplication()
    assertThrows(IllegalArgumentException::class.java) { BuiltinSounds.prepare(context, "../outside") }
  }
}
