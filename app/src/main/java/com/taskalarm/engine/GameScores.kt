package com.taskalarm.engine

import android.content.Context
import org.json.JSONObject

internal class GameScores(context: Context) {
  private val prefs = context.getSharedPreferences("task_alarm_game_scores_v1", Context.MODE_PRIVATE)
  fun best() = prefs.getInt("breakoutBest", 0)
  fun record(score: Int, completed: Boolean = false) {
    val edit = prefs.edit().putInt("breakoutBest", maxOf(best(), score))
    if (completed) edit.putInt("breakoutCompleted", prefs.getInt("breakoutCompleted", 0) + 1)
    edit.apply()
  }
  fun state() = JSONObject().put("breakoutBest", best()).put("breakoutCompleted", prefs.getInt("breakoutCompleted", 0))
}
