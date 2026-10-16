package com.taskalarm.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object SessionTasks {
  enum class Result { WRONG, NEXT, COMPLETE }

  fun start(session: JSONObject, tasks: JSONArray) {
    require(tasks.length() > 0) { "Choose at least one task" }
    session.put("taskIndex", 0).put("round", 1)
    challenge(session, tasks)
  }

  fun challenge(session: JSONObject, tasks: JSONArray) {
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    val kind = task.getString("kind")
    session.put("kind", kind).put("challengeId", UUID.randomUUID().toString())
    session.remove("nativeUnavailable"); session.put("nativeSteps", 0).put("nativeComplete", false)
    session.remove("mazeX"); session.remove("mazeY"); session.remove("mazeReached")
    if (kind in listOf("maze", "walk", "light")) {
      session.put("prompt", when(kind) { "maze" -> "Reach each numbered checkpoint, then the exit"; "walk" -> "Walk ${task.optInt("stepTarget",20)} steps"; else -> "Hold ${task.optInt("lightLux",200)} lux for ${task.optInt("lightHoldSeconds",3)} seconds" }).put("answer", JSONObject.NULL)
    } else if (kind == "breakout") {
      session.put("prompt", "Keep the ball moving for ${task.optInt("gameSeconds", 60)} active seconds")
        .put("answer", JSONObject.NULL).put("gamePlayedMs", 0).put("gameScore", 0).put("gameLastInputAt", 0).put("gameResets", 0)
    } else if (kind == "barcode") {
      session.put("prompt", if (task.getString("barcodeMode") == "any") "Scan any barcode" else "Scan your saved barcode")
        .put("answer", task.getString("barcode"))
    } else {
      val generated = Tasks.generate(kind, task.getString("difficulty"))
      session.put("prompt", generated.prompt).put("answer", generated.answer)
      if (kind == "memory") session.put("memorySequence", JSONArray(generated.answer.map { it.digitToInt() }))
    }
  }

  fun submit(session: JSONObject, tasks: JSONArray, challengeId: String, answer: String): Result {
    check(session.getString("challengeId") == challengeId) { "This task has already advanced" }
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    val kind = task.getString("kind")
    check(kind !in listOf("breakout", "maze", "walk", "light")) { "Play the game for the configured active time" }
    val correct = if (kind == "barcode" && task.getString("barcodeMode") == "any") answer.isNotEmpty()
      else Tasks.matches(kind, session.getString("answer"), answer)
    if (!correct) return Result.WRONG
    return advance(session, tasks)
  }
  fun completeGame(session: JSONObject, tasks: JSONArray, challengeId: String): Result {
    check(session.getString("challengeId") == challengeId) { "This game has already advanced" }
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    check(task.getString("kind") == "breakout")
    check(session.optLong("gamePlayedMs") >= task.optInt("gameSeconds", 60) * 1000L) { "Keep playing to finish this task" }
    return advance(session, tasks)
  }
  fun completeNative(session: JSONObject, tasks: JSONArray, challengeId: String): Result {
    check(session.getString("challengeId") == challengeId) { "This task has already advanced" }
    check(session.optString("kind") in listOf("maze", "walk", "light") && session.optBoolean("nativeComplete")) { "Complete the native task first" }
    return advance(session,tasks)
  }
  private fun advance(session: JSONObject, tasks: JSONArray): Result {
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    if (session.getInt("round") < task.getInt("rounds")) session.put("round", session.getInt("round") + 1)
    else session.put("taskIndex", session.getInt("taskIndex") + 1).put("round", 1)
    if (session.getInt("taskIndex") == tasks.length()) return Result.COMPLETE
    challenge(session, tasks)
    return Result.NEXT
  }
}
