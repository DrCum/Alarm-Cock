package com.taskalarm.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Offline peak normalization. Decodes to a bounded temporary PCM file; never loads a whole song into memory. */
internal object SoundProcessor {
  private const val MAX_BYTES = 64L * 1024 * 1024
  fun prepare(context: Context, value: String, normalize: Boolean): String {
    require(value.isNotBlank()) { "Choose a sound first" }
    val directory = File(context.filesDir, "sounds").apply { mkdirs() }
    val uri = Uri.parse(value)
    if (!normalize) {
      if (uri.scheme == "file" && File(requireNotNull(uri.path)).canonicalPath.startsWith(directory.canonicalPath + "/")) return value
      val destination = File(directory, "${UUID.randomUUID()}.audio")
      try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
          destination.outputStream().use { output ->
            val buffer = ByteArray(32768); var count: Int; var total = 0L
            while (input.read(buffer).also { count = it } != -1) {
              total += count; require(total <= MAX_BYTES) { "Choose an audio file smaller than 64 MB" }; output.write(buffer, 0, count)
            }
          }
        }
        require(destination.length() > 0) { "The audio file is empty" }
        return Uri.fromFile(destination).toString()
      } catch (error: Exception) { destination.delete(); throw error }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    val destination = File(directory, "$digest.normalized.wav")
    if (destination.exists()) return Uri.fromFile(destination).toString()
    val pcm = File.createTempFile("decode-", ".pcm", context.cacheDir)
    val partial = File(directory, "$digest.partial")
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
      extractor.setDataSource(context, uri, null)
      val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ?: error("This file has no supported audio track")
      extractor.selectTrack(track)
      val format = extractor.getTrackFormat(track)
      if (format.containsKey(MediaFormat.KEY_DURATION)) require(format.getLong(MediaFormat.KEY_DURATION) <= 300_000_000L) { "Normalize a sound up to 5 minutes long" }
      format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
      val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
      decoder = codec; codec.configure(format, null, null, 0); codec.start()
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var inputDone = false; var outputDone = false; var total = 0L; var peak = 0
      val info = MediaCodec.BufferInfo()
      val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
      pcm.outputStream().buffered().use { output ->
        while (!outputDone) {
          check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out" }
          if (!inputDone) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
              val buffer = requireNotNull(codec.getInputBuffer(index))
              val size = extractor.readSampleData(buffer, 0)
              if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
              else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
            }
          }
          val index = codec.dequeueOutputBuffer(info, 10_000)
          if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val actual = codec.outputFormat
            require(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) || actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "This decoder does not support 16-bit audio" }
            channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          } else if (index >= 0) {
            val buffer = requireNotNull(codec.getOutputBuffer(index))
            buffer.position(info.offset); buffer.limit(info.offset + info.size)
            val bytes = ByteArray(info.size); buffer.get(bytes)
            total += bytes.size
            require(total <= MAX_BYTES && total <= rate * channels * 2L * 300) { "Normalize a sound up to 5 minutes long" }
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
            output.write(bytes)
            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
          }
        }
      }
      require(total > 0 && channels in 1..8 && rate > 0) { "Could not decode this sound" }
      PcmWave.write(pcm, partial, channels, rate, peak)
      check(partial.renameTo(destination)) { "Unable to save processed sound" }
      return Uri.fromFile(destination).toString()
    } finally {
      try { decoder?.stop() } catch (_: Exception) { }
      decoder?.release(); extractor.release(); pcm.delete(); partial.delete()
    }
  }
}
