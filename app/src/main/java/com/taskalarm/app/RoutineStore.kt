package com.taskalarm.app

import android.content.Context
import android.net.Uri
import com.taskalarm.engine.BuiltinSounds
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Routine(val id: String, val name: String, val alarm: AlarmDraft, val starter: Boolean = false)
internal class RoutineStore(private val context: Context) {
  private val prefs=context.getSharedPreferences("task_alarm_routines_v1",Context.MODE_PRIVATE)
  fun saved(): List<Routine> {
    val array=JSONArray(prefs.getString("routines","[]"))
    return (0 until array.length()).map { i -> val item=array.getJSONObject(i)
      require(item.optInt("version",1)==1) { "This routine requires a newer app" }
      Routine(item.getString("id"),item.getString("name"),AlarmDraft.from(item.getJSONObject("alarm")))
    }
  }
  fun save(name: String, alarm: AlarmDraft, id: String = UUID.randomUUID().toString()) {
    require(name.isNotBlank() && name.length<=80) { "Name your routine (up to 80 characters)" }
    val all=saved().filterNot { it.id==id }+Routine(id,name.trim(),alarm)
    require(all.size<=30) { "Keep up to 30 routines; delete one first" }
    write(all)
  }
  fun delete(id: String) = write(saved().filterNot { it.id==id })
  private fun write(all: List<Routine>) {
    val array=JSONArray(all.map { JSONObject().put("version",1).put("id",it.id).put("name",it.name).put("alarm",it.alarm.json()) })
    check(prefs.edit().putString("routines",array.toString()).commit()) { "Could not save routines" }
  }
  fun apply(routine: Routine, target: AlarmDraft): AlarmDraft {
    val source=routine.alarm
    val sounds=listOf(source.soundUri,source.playbackUri)+source.escalationTones.map { it.uri }
    sounds.filter { it.isNotBlank() }.distinct().forEach { value ->
      if(value.startsWith("builtin:")) require(value.removePrefix("builtin:") in BuiltinSounds.names) { "Missing bundled tone" }
      else {
        val uri=Uri.parse(value)
        require(uri.scheme in listOf("file","content","android.resource")) { "Unsupported routine sound" }
        val readable=runCatching { context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { true } ?: false }.getOrDefault(false)
        require(readable) { "A sound in this routine is unavailable. Edit the source alarm to replace it, then save the routine again." }
      }
    }
    return copyTo(source,target)
  }
  companion object {
    private fun fresh(tasks: List<TaskDraft>) = tasks.map { it.copy(id=UUID.randomUUID().toString()) }
    fun copyTo(source: AlarmDraft, target: AlarmDraft) = source.copy(
      id=target.id,label=target.label,hour=target.hour,minute=target.minute,days=target.days,enabled=target.enabled,
      pauseUntil=target.pauseUntil,skipAt=target.skipAt,skipOneTime=target.skipOneTime,skipDate=target.skipDate,
      dismissTasks=fresh(source.dismissTasks),snoozeTasks=fresh(source.snoozeTasks),wakeTasks=fresh(source.wakeTasks),wakeRetryTasks=fresh(source.wakeRetryTasks))
    fun starters() = listOf(
      Routine("starter-quick","Quick start",AlarmDraft(dismissTasks=listOf(TaskDraft(kind="math"),TaskDraft(kind="memory"))),true),
      Routine("starter-moving","Get moving",AlarmDraft(dismissTasks=listOf(TaskDraft(kind="math"),TaskDraft(kind="walk")),wakeEnabled=true),true),
      Routine("starter-strong","Strong wake-up",AlarmDraft(dismissTasks=listOf(TaskDraft(kind="maze"),TaskDraft(kind="barcode",barcodeMode="specific")),
        wakeEnabled=true,wakeMode="tasks",wakeTasks=listOf(TaskDraft(kind="memory")),progressiveDifficulty=true,maxSnoozes=2,escalationEnabled=true),true),
      Routine("starter-light","Lights on",AlarmDraft(dismissTasks=listOf(TaskDraft(kind="light"),TaskDraft(kind="text")),wakeEnabled=true),true)
    )
  }
}
