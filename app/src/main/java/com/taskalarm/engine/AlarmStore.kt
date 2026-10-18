package com.taskalarm.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class AlarmStore(context: Context) {
  private val boot = BootRecovery(context)
  private val prefs = context.getSharedPreferences("task_alarm_v1", Context.MODE_PRIVATE)
  fun alarms(): List<JSONObject> {
    val array = JSONArray(prefs.getString("alarms", "[]"))
    return (0 until array.length()).map { array.getJSONObject(it) }
  }
  fun find(id: String) = alarms().find { it.getString("id") == id }
  fun put(alarm: JSONObject) = write(alarms().filterNot { it.getString("id") == alarm.getString("id") } + alarm)
  fun putAll(values: List<JSONObject>) {
    val ids = values.map { it.getString("id") }.toSet()
    require(ids.size == values.size)
    write(alarms().filterNot { it.getString("id") in ids } + values)
  }
  fun delete(id: String) = write(alarms().filterNot { it.getString("id") == id })
  private fun write(alarms: List<JSONObject>) {
    check(prefs.edit().putString("alarms", JSONArray(alarms).toString()).commit()) { "Unable to save alarms" }
    boot.sync()
  }
  fun sessions(): MutableList<JSONObject> {
    val array = JSONArray(prefs.getString("sessions", "[]"))
    return (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
  }
  fun sessions(value: List<JSONObject>) {
    check(prefs.edit().putString("sessions", JSONArray(value).toString()).commit()) { "Unable to save task progress" }
    boot.sync()
  }
  fun checks(): MutableList<JSONObject> {
    val array = JSONArray(prefs.getString("wake_checks", "[]"))
    return (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
  }
  fun checks(value: List<JSONObject>) {
    check(prefs.edit().putString("wake_checks", JSONArray(value).toString()).commit()) { "Unable to save wake-up checks" }
    boot.sync()
  }
  fun taskOrder(): List<String> {
    val array = JSONArray(prefs.getString("task_order", "[]"))
    return (0 until array.length()).map { array.getString(it) }
  }
  fun taskOrder(value: List<String>) {
    check(prefs.edit().putString("task_order", JSONArray(value).toString()).commit()) { "Unable to save task queue" }
  }
}
