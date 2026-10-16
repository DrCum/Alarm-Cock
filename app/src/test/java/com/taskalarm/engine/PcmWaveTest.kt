package com.taskalarm.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PcmWaveTest {
  private fun render(samples: List<Int>, channels: Int = 1): ByteArray {
    val pcm = File.createTempFile("test-input", ".pcm")
    val wave = File.createTempFile("test-output", ".wav")
    try {
      val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      samples.forEach { bytes.putShort(it.toShort()) }; pcm.writeBytes(bytes.array())
      PcmWave.write(pcm, wave, channels, 48000, samples.maxOf { abs(it) })
      return wave.readBytes()
    } finally { pcm.delete(); wave.delete() }
  }
  @Test fun quietAudioReachesTargetAndHeaderDescribesActualSamples() {
    val bytes = render(listOf(-1000, 0, 500, 1000))
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
    assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
    assertEquals(bytes.size - 8, data.getInt(4))
    assertEquals(1, data.getShort(22).toInt())
    assertEquals(48000, data.getInt(24))
    assertEquals(16, data.getShort(34).toInt())
    assertEquals(8, data.getInt(40))
    assertEquals(-32112, data.getShort(44).toInt())
    assertEquals(0, data.getShort(46).toInt())
    assertEquals(16056, data.getShort(48).toInt())
    assertEquals(32112, data.getShort(50).toInt())
  }
  @Test fun negativeFullScaleDoesNotOverflowOrClip() {
    val bytes = render(listOf(-32768, 32767), 2)
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(2, data.getShort(22).toInt())
    assertEquals(-32112, data.getShort(44).toInt())
    assertTrue(data.getShort(46).toInt() in 32110..32112)
  }
  @Test fun silenceRemainsSilence() {
    assertTrue(render(List(32) { 0 }).drop(44).all { it == 0.toByte() })
  }
}
