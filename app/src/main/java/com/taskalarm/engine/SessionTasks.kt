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

  private fun challenge(session: JSONObject, tasks: JSONArray) {
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    val kind = task.getString("kind")
    session.put("kind", kind).put("challengeId", UUID.randomUUID().toString())
    if (kind == "barcode") {
      session.put("prompt", if (task.getString("barcodeMode") == "any") "Scan any barcode" else "Scan your saved barcode")
        .put("answer", task.getString("barcode"))
    } else {
      val generated = Tasks.generate(kind, task.getString("difficulty"))
      session.put("prompt", generated.prompt).put("answer", generated.answer)
    }
  }

  fun submit(session: JSONObject, tasks: JSONArray, challengeId: String, answer: String): Result {
    check(session.getString("challengeId") == challengeId) { "This task has already advanced" }
    val task = tasks.getJSONObject(session.getInt("taskIndex"))
    val kind = task.getString("kind")
    val correct = if (kind == "barcode" && task.getString("barcodeMode") == "any") answer.isNotEmpty()
      else Tasks.matches(kind, session.getString("answer"), answer)
    if (!correct) return Result.WRONG
    if (session.getInt("round") < task.getInt("rounds")) session.put("round", session.getInt("round") + 1)
    else session.put("taskIndex", session.getInt("taskIndex") + 1).put("round", 1)
    if (session.getInt("taskIndex") == tasks.length()) return Result.COMPLETE
    challenge(session, tasks)
    return Result.NEXT
  }
}
