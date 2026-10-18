package com.taskalarm.engine

import android.content.Context
import com.taskalarm.app.AlarmDraft
import org.json.JSONObject
import java.util.UUID

/** Serial native edit journal: autosaves are reversible and stale workers cannot save after exit. */
internal class AlarmEdits(context: Context, private val save: (String)->Unit, private val delete: (String)->Unit, private val now: ()->Long = System::currentTimeMillis) {
  private val prefs=context.getSharedPreferences("task_alarm_edits_v1",Context.MODE_PRIVATE)
  private val store=AlarmStore(context)
  private fun definition(raw: JSONObject) = AlarmDraft.from(raw)
  private fun same(a: JSONObject?, b: JSONObject?) = if(a==null || b==null) a==null && b==null else definition(a)==definition(b) &&
    listOf("nextAt","snoozed","wakeRetry","snoozeCount","escalationLatched").all { a.opt(it)?.toString()==b.opt(it)?.toString() }
  private fun journal(token: String) = JSONObject(prefs.getString("edit_$token",null) ?: error("This edit session has closed. Reopen the alarm."))
  private fun write(token: String, record: JSONObject) { check(prefs.edit().putString("edit_$token",record.toString()).commit()) { "Could not save edit recovery" } }
  private fun raw(record: JSONObject, key: String) = record.optJSONObject(key)
  private fun imminent(alarm: JSONObject?) = alarm?.optBoolean("enabled") == true && alarm.optLong("nextAt",Long.MAX_VALUE) <= now()+1000
  private fun assertCurrent(record: JSONObject) {
    check(same(store.find(record.getString("id")),raw(record,"last")) && !imminent(store.find(record.getString("id")))) { "This alarm rang or changed while editing. Exit and reopen it before making more changes." }
  }
  fun begin(json: String): String {
    val draft=AlarmDraft.from(JSONObject(json)); val original=store.find(draft.id)
    val token=UUID.randomUUID().toString()
    val record=JSONObject().put("id",draft.id).put("initial",draft.json()).put("original",original ?: JSONObject.NULL)
      .put("last",original ?: JSONObject.NULL).put("previousHistory",prefs.getString("history_${draft.id}",null) ?: JSONObject.NULL).put("wrote",false)
    // Only one editor owns an alarm. Close abandoned workers from a previous page/process.
    prefs.getString("owner_${draft.id}",null)?.let { old -> prefs.edit().remove("edit_$old").commit() }
    write(token,record); check(prefs.edit().putString("owner_${draft.id}",token).commit())
    return token
  }
  fun status(token: String): String {
    val record=journal(token); val id=record.getString("id")
    val history=prefs.getString("history_$id",null)?.let(::JSONObject)
    val last=raw(record,"last")
    val initial=AlarmDraft.from(record.getJSONObject("initial"))
    val canRevert=(last!=null && definition(last)!=initial) || (history!=null && last!=null && definition(history)!=definition(last))
    return JSONObject().put("canRevert",canRevert).put("exists",last!=null).toString()
  }
  fun save(token: String, json: String): String {
    val record=journal(token); val draft=AlarmDraft.from(JSONObject(json))
    check(draft.id==record.getString("id")) { "Alarm changed" }; assertCurrent(record)
    val last=raw(record,"last")
    if(last!=null && definition(last)==draft) return last.toString()
    draft.validate(); save(json)
    if(!record.optBoolean("wrote")) raw(record,"original")?.let { _ ->
      check(prefs.edit().putString("history_${draft.id}",record.getJSONObject("initial").toString()).commit())
    }
    record.put("wrote",true).put("last",store.find(draft.id))
    write(token,record)
    return record.getJSONObject("last").toString()
  }
  fun revert(token: String, opening: Boolean): String {
    val record=journal(token); assertCurrent(record)
    val id=record.getString("id")
    val target=if(opening) record.getJSONObject("initial") else JSONObject(prefs.getString("history_$id",null) ?: error("No earlier edit to revert"))
    val last=raw(record,"last")
    if(last!=null && definition(last)!=definition(target)) save(AlarmDraft.from(target).json().toString())
    record.put("last",store.find(id) ?: JSONObject.NULL)
    if(!opening) { prefs.edit().remove("history_$id").commit(); record.put("initial",target).put("wrote",false) }
    write(token,record)
    return AlarmDraft.from(target).json().toString()
  }
  /** False means the alarm advanced independently: close safely without rolling it back. */
  fun discard(token: String): Boolean {
    val record=journal(token); val id=record.getString("id")
    if(!same(store.find(id),raw(record,"last"))) { close(token); return false }
    val original=raw(record,"original"); val last=raw(record,"last")
    val wouldChange=if(original==null) last!=null else last==null || definition(original)!=definition(last)
    if(wouldChange && imminent(store.find(id))) {close(token); return false}
    if(original==null) { if(last!=null) delete(id) }
    else if(last==null || definition(original)!=definition(last)) save(AlarmDraft.from(original).json().toString())
    val history=record.optString("previousHistory").takeUnless { record.isNull("previousHistory") }
    val editor=prefs.edit(); if(history==null) editor.remove("history_$id") else editor.putString("history_$id",history)
    check(editor.commit()); close(token); return true
  }
  fun close(token: String) {
    val record=journal(token); val editor=prefs.edit().remove("edit_$token")
    if(prefs.getString("owner_${record.getString("id")}",null)==token) editor.remove("owner_${record.getString("id")}")
    check(editor.commit())
  }
}
