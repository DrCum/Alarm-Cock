package com.taskalarm.engine

import android.content.Context
import org.json.JSONObject

/** One durable display order for ringing alarms and ready wake checks. Call under AlarmEngine.LOCK. */
internal class TaskQueue(context: Context) {
  private val store = AlarmStore(context)
  data class Entry(val kind: String, val sessionId: String, val label: String, val queuedAt: Long) {
    val key get() = "$kind:$sessionId"
    fun json() = JSONObject().put("kind", kind).put("sessionId", sessionId).put("label", label)
  }
  fun entries(): List<Entry> {
    val available = (store.sessions().map {
      Entry("alarm", it.getString("sessionId"), it.getJSONObject("alarm").getString("label"), it.optLong("queuedAt"))
    } + store.checks().filter { it.optString("stage") in listOf("queued", "waiting") }.map {
      Entry("wake", it.getString("sessionId"), it.getJSONObject("alarm").getString("label"), it.optLong("queuedAt", it.optLong("dueAt")))
    }).associateBy { it.key }
    val previous = store.taskOrder()
    val retained = previous.distinct().filter { it in available }
    val added = available.values.filter { it.key !in retained }.sortedWith(compareBy<Entry> { it.queuedAt }.thenBy { it.kind })
    val order = retained + added.map { it.key }
    if(order != previous) store.taskOrder(order)
    return order.map { available.getValue(it) }
  }
  fun head() = entries().firstOrNull()
  fun isHead(kind: String, id: String) = head()?.let { it.kind == kind && it.sessionId == id } == true
}
