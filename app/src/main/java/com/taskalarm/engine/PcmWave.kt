package com.taskalarm.engine

import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Platform-independent PCM16 WAV writer, shared by the decoder and JVM verification. */
internal object PcmWave {
  fun write(pcm: File, destination: File, channels: Int, rate: Int, peak: Int) {
    require(channels in 1..8 && rate > 0 && peak in 0..32768)
    val total = pcm.length()
    require(total > 0 && total <= Int.MAX_VALUE - 36 && total % (channels * 2) == 0L)
    val gain = if (peak == 0) 1.0 else 32112.0 / peak
    DataOutputStream(destination.outputStream().buffered()).use { output ->
      fun intLE(value: Int) { output.writeInt(Integer.reverseBytes(value)) }
      fun shortLE(value: Int) { output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt()) }
      output.writeBytes("RIFF"); intLE(total.toInt() + 36); output.writeBytes("WAVEfmt "); intLE(16)
      shortLE(1); shortLE(channels); intLE(rate); intLE(rate * channels * 2); shortLE(channels * 2); shortLE(16)
      output.writeBytes("data"); intLE(total.toInt())
      pcm.inputStream().buffered().use { input ->
        val bytes = ByteArray(32768)
        while (true) {
          val count = input.read(bytes); if (count < 0) break
          val samples = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
          while (samples.hasRemaining()) shortLE((samples.get() * gain).roundToInt().coerceIn(-32768, 32767))
        }
      }
    }
  }
}
