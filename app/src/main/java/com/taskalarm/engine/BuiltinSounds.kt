package com.taskalarm.engine

import android.content.Context
import android.net.Uri
import java.io.File

internal object BuiltinSounds {
  val names = linkedMapOf("beacon" to "Beacon", "pulse" to "Pulse", "chime" to "Chime")
  @Synchronized fun prepare(context: Context, id: String): String {
    require(id in names) { "Unknown bundled sound" }
    val directory = File(context.filesDir, "builtin-sounds").apply { mkdirs() }
    val target = File(directory, "$id-v1.wav")
    if (!target.exists()) {
      val temp = File(directory, "$id.tmp")
      try {
        context.assets.open("sounds/$id.wav").use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        check(temp.renameTo(target)) { "Could not save bundled sound" }
      } finally { temp.delete() }
    }
    return Uri.fromFile(target).toString()
  }
}
