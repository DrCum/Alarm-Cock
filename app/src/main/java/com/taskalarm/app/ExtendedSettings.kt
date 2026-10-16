package com.taskalarm.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskalarm.engine.WakeUpPolicy
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
internal fun HealthScreen(state: JSONObject?, onBack: () -> Unit, onAccess: (String) -> Unit, onTest: () -> Unit) {
  Page {
    Secondary("← Alarms", onClick = onBack)
    Text("Alarm health", style = MaterialTheme.typography.headlineLarge)
    val ready = state?.optJSONObject("readiness")
    listOf(Triple("exactAlarms","Exact alarms","exact"),Triple("notifications","Alarm notifications","notifications"),
      Triple("wakeNotifications","Wake-up notifications","wakeNotifications"),Triple("fullScreen","Full-screen alarms","fullScreen"),
      Triple("batteryUnrestricted","Battery unrestricted","battery")).forEach { (key,label,access) ->
      CardBlock {
        Text("$label · ${if(ready?.optBoolean(key) == true) "Ready" else "Check settings"}")
        Secondary("Open $label settings") { onAccess(access) }
      }
    }
    CardBlock {
      Text("Task capabilities",style = MaterialTheme.typography.titleMedium)
      listOf("stepSensor" to "Step sensor", "activityPermission" to "Walking permission", "lightSensor" to "Ambient-light sensor", "tiltSensor" to "Tilt sensor", "flashlightAvailable" to "Flashlight", "cameraPermission" to "Camera permission").forEach { (key,label) -> Body("$label: ${if(ready?.optBoolean(key) == true) "Available" else "Unavailable"}") }
      if(ready?.optBoolean("activityPermission") != true) Secondary("Allow walking permission") { onAccess("activity") }
      Secondary("App permissions") { onAccess("appSettings") }
      val array = state?.optJSONArray("alarms")
      if(array != null) for(i in 0 until array.length()) {
        val alarm = AlarmDraft.from(array.getJSONObject(i))
        val kinds = (alarm.dismissTasks+alarm.snoozeTasks+(if(alarm.wakeEnabled) alarm.wakeTasks+alarm.wakeRetryTasks else emptyList())).map { it.kind }.toSet()
        if(kinds.any { it in listOf("walk","light","maze","barcode") }) Body("${alarm.label}: ${kinds.joinToString()}")
      }
    }
    val alarms = state?.optJSONArray("alarms")
    val next = if(alarms == null) null else (0 until alarms.length()).map { alarms.getJSONObject(it) }.filter { it.optBoolean("enabled") && it.has("nextAt") }.minByOrNull { it.getLong("nextAt") }
    Body(if(next == null) "No alarm scheduled." else "Next scheduled ring: ${next.getString("label")} · ${DateFormat.getDateTimeInstance().format(Date(next.getLong("nextAt")))}")
    state?.optJSONArray("wakeChecks")?.let { checks ->
      for(i in 0 until checks.length()) {
        val check=checks.getJSONObject(i)
        Body("${check.getString("label")}: check at ${DateFormat.getTimeInstance().format(Date(check.getLong("dueAt")))}; repeat ring if unanswered at ${DateFormat.getTimeInstance().format(Date(check.getLong("deadlineAt")))}.")
      }
    }
    CardBlock {
      Text("Locked-phone delivery test",style=MaterialTheme.typography.titleMedium)
      Body("Schedule a test in 30 seconds, then lock your phone. Dismiss it to record acknowledgement. This does not change your normal alarms or create follow-up checks.")
      Primary("Schedule delivery test", onClick = onTest)
      val test = state?.optJSONObject("deliveryTest")
      if(test?.has("status") == true) {
        Body("Last test: ${test.optString("status")} · ${DateFormat.getDateTimeInstance().format(Date(test.optLong("updatedAt")))}")
        if(test.optString("status") == "scheduled" && test.optLong("scheduledAt") < System.currentTimeMillis()) Body("Scheduled time has passed; delivery has not been recorded.")
      }
      Body("A successful test confirms that delivery worked at that time. Battery rules and permissions can change. Alarms before the first unlock after reboot are not supported yet. Power-off and Android Force stop prevent delivery.")
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleSettings(alarm: AlarmDraft, onChange: (AlarmDraft) -> Unit) {
  val activity = LocalActivity.current ?: return
  Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
    OutlinedButton(onClick = {
      val selected = Calendar.getInstance().apply { timeInMillis = maxOf(System.currentTimeMillis()+60_000,alarm.pauseUntil) }
      DatePickerDialog(activity,{_,year,month,day ->
        selected.set(year,month,day)
        TimePickerDialog(activity,{_,hour,minute ->
          selected.set(Calendar.HOUR_OF_DAY,hour); selected.set(Calendar.MINUTE,minute); selected.set(Calendar.SECOND,0); selected.set(Calendar.MILLISECOND,0)
          onChange(alarm.copy(pauseUntil=selected.timeInMillis.coerceAtLeast(System.currentTimeMillis())))
        },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true).show()
      },selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH)).show()
    }) {Text("Pause until…")}
    InfoButton("Pause until","Pause affects future regular alarms. An already active snooze or wake-up check continues. The next alarm preview appears in Time / days / name. ${if(alarm.skipAt>0) "Skipped occurrence: ${alarm.skipDate}. Undo it from the alarm list." else "Use Skip next on the alarm list to skip one occurrence."}")
    }
    if(alarm.pauseUntil>System.currentTimeMillis()) {
      Text("Paused until ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(alarm.pauseUntil))}",style=MaterialTheme.typography.bodySmall)
      TextButton(onClick={onChange(alarm.copy(pauseUntil=0))}) {Text("Clear pause")}
    }
  }
}

@Composable
internal fun DifficultySettings(alarm: AlarmDraft, onChange: (AlarmDraft) -> Unit) {
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    ToggleRow("Increase difficulty after snoozes",alarm.progressiveDifficulty) { onChange(alarm.copy(progressiveDifficulty=it)) }
    InfoButton("Progressive difficulty","Each completed snooze raises text, math, memory, Breakout, and maze difficulty one level, capped at Hard. Task durations, steps, and light settings stay as configured.")
    if(alarm.progressiveDifficulty) EditorSection("Difficulty previews") {
      val tasks = alarm.dismissTasks+alarm.snoozeTasks+(if(alarm.wakeEnabled) alarm.wakeTasks+alarm.wakeRetryTasks else emptyList())
      tasks.filter { it.kind in listOf("text","math","memory","breakout","maze") }.forEach { task ->
        Text(task.name, style=MaterialTheme.typography.titleSmall)
        for(count in 0..2) {
          val effective = task.copy(difficulty=WakeUpPolicy.level(task.difficulty,count))
          Body("${if(count==0) "Initially" else "After $count snooze(s)"}: ${effective.difficulty} · ${effective.example}")
        }
      }
    }
  }
}
