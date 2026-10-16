package com.taskalarm.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TaskDraft(
  val id: String = UUID.randomUUID().toString(),
  val kind: String = "math",
  val difficulty: String = "easy",
  val rounds: Int = 1,
  val barcodeMode: String = "any",
  val barcode: String = "",
  val gameSeconds: Int = 60,
  val gameIdleSeconds: Int = 20
) {
  fun json() = JSONObject().put("id", id).put("kind", kind).put("difficulty", difficulty).put("rounds", rounds)
    .put("barcodeMode", barcodeMode).put("barcode", barcode)
    .put("gameSeconds", gameSeconds).put("gameIdleSeconds", gameIdleSeconds)
  val name get() = when (kind) { "text" -> "Type random text"; "math" -> "Solve math"; "breakout" -> "Play Breakout"; "memory" -> "Repeat a memory sequence"; else -> "Scan a barcode" }
  val example get() = when (kind) {
    "text" -> when (difficulty) { "easy" -> "kefuma"; "medium" -> "K7mP3xRa9q"; else -> "R8!mQ2#vL9@pX4&s" }
    "breakout" -> "$gameSeconds seconds of active play · idle $gameIdleSeconds seconds resets time"
    "memory" -> when (difficulty) { "easy" -> "1 → 3 → 2"; "medium" -> "1 → 3 → 2 → 4 → 1"; else -> "1 → 3 → 2 → 4 → 1 → 2 → 3" }
    else -> when (difficulty) { "easy" -> "7 + 5 = ?"; "medium" -> "12 × 7 + 18 = ?"; else -> "(23 × 14) − (12 × 7) = ?" }
  }
  companion object {
    fun from(value: JSONObject) = TaskDraft(value.getString("id"), value.getString("kind"), value.getString("difficulty"),
      value.getInt("rounds"), value.getString("barcodeMode"), value.getString("barcode"),
      value.optInt("gameSeconds", 60), value.optInt("gameIdleSeconds", 20))
  }
}

data class AlarmTone(val uri: String, val name: String) {
  fun json() = JSONObject().put("uri", uri).put("name", name)
  companion object {
    fun defaults() = listOf(AlarmTone("builtin:beacon", "Beacon"), AlarmTone("builtin:pulse", "Pulse"), AlarmTone("builtin:chime", "Chime"))
    fun from(value: JSONObject) = AlarmTone(value.getString("uri"), value.optString("name", "Alarm tone"))
  }
}

data class AlarmDraft(
  val id: String = UUID.randomUUID().toString(),
  val label: String = "Morning alarm",
  val hour: String = "07",
  val minute: String = "00",
  val days: Set<Int> = setOf(1, 2, 3, 4, 5),
  val enabled: Boolean = true,
  val snoozeMinutes: Int = 5,
  val dismissTasks: List<TaskDraft> = listOf(TaskDraft()),
  val snoozeTasks: List<TaskDraft> = emptyList(),
  val soundUri: String = "",
  val soundName: String = "Default phone alarm",
  val normalize: Boolean = false,
  val playbackUri: String = "",
  val protectVolume: Boolean = false,
  val allowMuteWhileSolving: Boolean = false,
  val wakeEnabled: Boolean = false,
  val wakeDelayMinutes: Int = 5,
  val wakeResponseMinutes: Int = 5,
  val wakeMode: String = "yes_no",
  val wakeTasks: List<TaskDraft> = listOf(TaskDraft()),
  val wakeReuseDismissTasks: Boolean = true,
  val wakeRetryTasks: List<TaskDraft> = listOf(TaskDraft()),
  val flashlightMode: String = "off",
  val randomDismiss: Boolean = false,
  val maxSnoozes: Int = 0,
  val shuffleTasks: Boolean = false,
  val vibrate: Boolean = false,
  val escalationEnabled: Boolean = false,
  val escalationAfterMinutes: Int = 2,
  val escalationTones: List<AlarmTone> = AlarmTone.defaults()
) {
  fun json() = JSONObject().put("id", id).put("label", label).put("hour", hour).put("minute", minute)
    .put("days", JSONArray(days.sorted())).put("enabled", enabled).put("snoozeMinutes", snoozeMinutes)
    .put("dismissTasks", JSONArray(dismissTasks.map { it.json() })).put("snoozeTasks", JSONArray(snoozeTasks.map { it.json() }))
    .put("soundUri", soundUri).put("soundName", soundName).put("normalize", normalize).put("playbackUri", playbackUri)
    .put("protectVolume", protectVolume).put("allowMuteWhileSolving", allowMuteWhileSolving)
    .put("wakeEnabled", wakeEnabled).put("wakeDelayMinutes", wakeDelayMinutes).put("wakeResponseMinutes", wakeResponseMinutes)
    .put("wakeMode", wakeMode).put("wakeTasks", JSONArray(wakeTasks.map { it.json() }))
    .put("wakeReuseDismissTasks", wakeReuseDismissTasks).put("wakeRetryTasks", JSONArray(wakeRetryTasks.map { it.json() }))
    .put("flashlightMode", flashlightMode)
    .put("randomDismiss", randomDismiss)
    .put("maxSnoozes", maxSnoozes).put("shuffleTasks", shuffleTasks).put("vibrate", vibrate)
    .put("escalationEnabled", escalationEnabled).put("escalationAfterMinutes", escalationAfterMinutes)
    .put("escalationTones", JSONArray(escalationTones.map { it.json() }))

  fun validate() {
    require(hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59) { "Enter a valid 24-hour time." }
    require(snoozeMinutes in 1..60) { "Choose a snooze length from 1 to 60 minutes." }
    require(maxSnoozes in 0..10) { "Choose 0–10 snoozes; 0 means unlimited." }
    require(flashlightMode in listOf("off", "steady", "flashing")) { "Choose a valid flashlight mode." }
    if (escalationEnabled) {
      require(escalationAfterMinutes in 1..120) { "Choose 1–120 minutes before escalation." }
      require(escalationTones.size in 1..12) { "Choose 1–12 escalation tones." }
    }
    if (wakeEnabled) {
      require(wakeDelayMinutes in 1..1440 && wakeResponseMinutes in 1..1440) { "Wake-up check times must be 1–1440 minutes." }
      require(wakeMode in listOf("yes_no", "tasks"))
      require(wakeMode != "tasks" || wakeTasks.isNotEmpty()) { "Choose at least one task to confirm you’re awake." }
    }
    val configured = dismissTasks + snoozeTasks + if (wakeEnabled) (if (wakeMode == "tasks") wakeTasks else emptyList()) + (if (!wakeReuseDismissTasks) wakeRetryTasks else emptyList()) else emptyList()
    for (task in configured) {
      if (task.kind == "breakout") {
        require(task.gameSeconds in 5..3600) { "Choose 5–3600 seconds of active play." }
        require(task.gameIdleSeconds in 5..120) { "Choose a 5–120 second idle reset." }
      }
      require(task.kind != "barcode" || task.barcodeMode != "specific" || task.barcode.isNotEmpty()) { "Scan the target barcode for each specific-barcode task." }
    }
  }
  fun tasks(action: String) = when (action) { "dismiss" -> dismissTasks; "snooze" -> snoozeTasks; "wake" -> wakeTasks; "retry" -> wakeRetryTasks; else -> error("Unknown task list") }
  fun withTasks(action: String, tasks: List<TaskDraft>) = when (action) {
    "dismiss" -> copy(dismissTasks = tasks); "snooze" -> copy(snoozeTasks = tasks)
    "wake" -> copy(wakeTasks = tasks); "retry" -> copy(wakeRetryTasks = tasks); else -> error("Unknown task list")
  }
  companion object {
    fun from(value: JSONObject): AlarmDraft {
      fun tasks(key: String, fallback: List<TaskDraft> = emptyList()): List<TaskDraft> {
        val array = value.optJSONArray(key) ?: return fallback
        return (0 until array.length()).map { TaskDraft.from(array.getJSONObject(it)) }
      }
      val days = value.getJSONArray("days")
      return AlarmDraft(value.getString("id"), value.getString("label"), value.getString("hour"),
        value.getString("minute"), (0 until days.length()).map { days.getInt(it) }.toSet(),
        value.getBoolean("enabled"), value.getInt("snoozeMinutes"), tasks("dismissTasks"), tasks("snoozeTasks"),
        value.optString("soundUri"), value.optString("soundName", "Default phone alarm"), value.optBoolean("normalize"), value.optString("playbackUri"),
        value.optBoolean("protectVolume"), value.optBoolean("allowMuteWhileSolving"), value.optBoolean("wakeEnabled"),
        value.optInt("wakeDelayMinutes", 5), value.optInt("wakeResponseMinutes", 5), value.optString("wakeMode", "yes_no"),
        tasks("wakeTasks", listOf(TaskDraft())), value.optBoolean("wakeReuseDismissTasks", true), tasks("wakeRetryTasks", listOf(TaskDraft())),
        value.optString("flashlightMode", "off"), value.optBoolean("randomDismiss"),
        value.optInt("maxSnoozes", 0), value.optBoolean("shuffleTasks"), value.optBoolean("vibrate"),
        value.optBoolean("escalationEnabled"), value.optInt("escalationAfterMinutes", 2),
        value.optJSONArray("escalationTones")?.let { tones -> (0 until tones.length()).map { AlarmTone.from(tones.getJSONObject(it)) } } ?: AlarmTone.defaults())
    }
  }
}
