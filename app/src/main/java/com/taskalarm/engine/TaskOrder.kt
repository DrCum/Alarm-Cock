package com.taskalarm.engine

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Collections

internal object TaskOrder {
  private val random = SecureRandom()
  fun arrange(tasks: JSONArray, shuffle: Boolean): JSONArray {
    val entries = (0 until tasks.length()).map { JSONObject(tasks.getJSONObject(it).toString()) }.toMutableList()
    if (shuffle) Collections.shuffle(entries, random)
    return JSONArray(entries)
  }
}
