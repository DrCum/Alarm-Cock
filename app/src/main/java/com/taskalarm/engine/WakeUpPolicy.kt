package com.taskalarm.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal object WakeUpPolicy {
  private val levels = listOf("easy", "medium", "hard")
  fun level(base: String, snoozes: Int): String = levels[(levels.indexOf(base).coerceAtLeast(0) + snoozes.coerceIn(0, 2)).coerceAtMost(2)]
  fun effective(tasks: JSONArray, alarm: JSONObject): JSONArray = JSONArray(tasks.toString()).also { result ->
    if (alarm.optBoolean("progressiveDifficulty")) for (i in 0 until result.length()) {
      val task = result.getJSONObject(i)
      if (task.optString("kind") in listOf("text", "math", "memory", "breakout", "maze"))
        task.put("difficulty", level(task.getString("difficulty"), alarm.optInt("snoozeCount")))
    }
  }
  fun delayMs(alarm: JSONObject, random: Random = Random.Default): Long {
    if (!alarm.optBoolean("wakeRandom")) return alarm.optInt("wakeDelayMinutes", 5) * 60_000L
    val min = alarm.optInt("wakeMinMinutes", 3) * 60_000L
    val max = alarm.optInt("wakeMaxMinutes", 7) * 60_000L
    require(min in 60_000L..86_400_000L && max in min..86_400_000L)
    return random.nextLong(min, max + 1)
  }
  fun validate(alarm: JSONObject) {
    require(alarm.optLong("pauseUntil") >= 0 && alarm.optLong("skipAt") >= 0) { "Invalid schedule exception" }
    if(alarm.optString("skipDate").isNotEmpty()) require(runCatching { java.time.LocalDate.parse(alarm.getString("skipDate")) }.isSuccess) { "Invalid skipped date" }
    if (alarm.optBoolean("wakeEnabled") && alarm.optBoolean("wakeRandom")) {
      require(alarm.optInt("wakeMinMinutes", 3) in 1..1440 && alarm.optInt("wakeMaxMinutes", 7) in alarm.optInt("wakeMinMinutes", 3)..1440) { "Choose a random check range of 1–1440 minutes, with maximum at least minimum" }
    }
    for (key in listOf("dismissTasks", "snoozeTasks", "wakeTasks", "wakeRetryTasks")) {
      val used = key == "dismissTasks" || (key == "snoozeTasks" && alarm.optBoolean("snoozeEnabled", true)) || (alarm.optBoolean("wakeEnabled") &&
        ((key=="wakeTasks" && alarm.optString("wakeMode","yes_no")=="tasks") || (key=="wakeRetryTasks" && !alarm.optBoolean("wakeReuseDismissTasks",true))))
      if(!used) continue
      val tasks = alarm.optJSONArray(key) ?: continue
      for (i in 0 until tasks.length()) {
        val task = tasks.getJSONObject(i)
        when (task.optString("kind")) {
          "walk" -> require(task.optInt("stepTarget", 20) in 5..200) { "Choose 5–200 steps" }
          "light" -> {
            require(task.optInt("lightLux", 200) in 50..2000) { "Choose 50–2000 lux" }
            require(task.optInt("lightHoldSeconds", 3) in 2..30) { "Choose a 2–30 second light hold" }
          }
          "maze" -> require(task.optString("controlMode", "tilt") in listOf("tilt", "touch")) { "Choose tilt or touch controls" }
        }
        if (task.optString("kind") in listOf("walk", "light", "maze"))
          require(task.optString("fallbackKind", "math") in listOf("math", "text")) { "Select a math or text fallback" }
      }
    }
  }
}
