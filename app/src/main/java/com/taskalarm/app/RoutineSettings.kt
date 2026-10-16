package com.taskalarm.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineSettings(alarm: AlarmDraft, onChange: (AlarmDraft)->Unit) {
  val activity=LocalActivity.current ?: return
  val store=remember { RoutineStore(activity) }
  var revision by remember { mutableIntStateOf(0) }
  var name by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf<String?>(null) }
  var selected by remember { mutableStateOf<Routine?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  val saved=remember(revision) { runCatching { store.saved() }.getOrElse { error=it.message; emptyList() } }
  fun attempt(block: ()->Unit) { try { block(); revision++ } catch(failure: Exception) { error=failure.message } }
  EditorSection("Morning routines","${saved.size} saved",info="Reuse tasks, sound, snooze rules, and wake-up settings. Applying a routine keeps this alarm’s name, time, repeat days, and schedule exceptions. Saved routines are independent of alarms that previously used them.") {
      OutlinedTextField(name,{name=it.take(80)},label={Text("Routine name")},modifier=Modifier.fillMaxWidth())
      Primary("Save current settings as new routine",name.isNotBlank()) { attempt { store.save(name,alarm); name=""; editing=null } }
      editing?.let { id ->
        Secondary("Rename selected routine",name.isNotBlank()) { attempt { val existing=store.saved().first { it.id==id }; store.save(name,existing.alarm,id); editing=null; name="" } }
        Secondary("Replace selected routine with current settings",name.isNotBlank()) { attempt { store.save(name,alarm,id); editing=null; name="" } }
        InfoButton("Saved routines","Editing a routine does not change alarms that previously used it.")
      }
      (RoutineStore.starters()+saved).forEach { routine ->
        HorizontalDivider()
        Text(routine.name,style=MaterialTheme.typography.titleMedium)
        Body(routine.alarm.dismissTasks.joinToString(" → ") { it.name })
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
          TextButton(onClick={selected=routine}) { Text("Review & apply") }
          TextButton(onClick={attempt { store.save("${routine.name.take(70)} copy",routine.alarm) }}) { Text("Duplicate") }
          if(!routine.starter) {
            TextButton(onClick={editing=routine.id; name=routine.name}) { Text("Edit saved routine") }
            TextButton(onClick={attempt { store.delete(routine.id); if(editing==routine.id) editing=null }}) { Text("Delete") }
          }
        }
      }
  }
  selected?.let { routine ->
    AlertDialog(onDismissRequest={selected=null},title={Text("Apply ${routine.name}?")},text={
      Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Body("Review the replacement settings. Your alarm’s schedule and name stay as shown in the editor.")
        listOf("Dismiss" to routine.alarm.dismissTasks,"Snooze" to routine.alarm.snoozeTasks,"Wake-up" to routine.alarm.wakeTasks,"Retry" to routine.alarm.wakeRetryTasks).forEach { (label,tasks) ->
          Text("$label: ${if(tasks.isEmpty()) "No tasks" else tasks.joinToString { "${it.name} (${it.difficulty}, ${it.rounds} rounds): ${it.example}" }}")
        }
        val r=routine.alarm
        Body("Sound: ${r.soundName}. Snooze: ${r.snoozeMinutes} minutes, limit ${if(r.maxSnoozes==0) "unlimited" else r.maxSnoozes}. Progressive difficulty: ${r.progressiveDifficulty}.")
        Body("Wake checks: ${r.wakeEnabled}; ${if(r.wakeRandom) "${r.wakeMinMinutes}–${r.wakeMaxMinutes}" else r.wakeDelayMinutes} minute delay; ${r.wakeResponseMinutes} minutes to respond. Mode: ${r.wakeMode}. Reuse dismiss tasks: ${r.wakeReuseDismissTasks}.")
        Body("Flashlight: ${r.flashlightMode}. Vibration: ${r.vibrate}. Mute while solving: ${r.allowMuteWhileSolving}. Volume protection: ${r.protectVolume}. Moving dismiss: ${r.randomDismiss}. Shuffle: ${r.shuffleTasks}.")
        Body("Volume escalation: ${r.escalationEnabled}, delay ${r.escalationAfterMinutes} minutes. Playlist: ${r.escalationTones.joinToString { it.name }}.")
        if(r.dismissTasks.any { it.kind=="barcode" && it.barcodeMode=="specific" && it.barcode.isEmpty() }) Body("Enroll your own barcode before saving this alarm.")
        if((r.dismissTasks+r.snoozeTasks+r.wakeTasks+r.wakeRetryTasks).any { it.kind in listOf("walk","light","maze") }) Body("Review sensor availability, try the task demo, and choose fallback tasks in the editor.")
      }
    },confirmButton={TextButton(onClick={attempt { onChange(store.apply(routine,alarm)); selected=null }}) { Text("Apply to alarm") }},dismissButton={TextButton(onClick={selected=null}) {Text("Cancel")}})
  }
  error?.let { message -> AlertDialog(onDismissRequest={error=null},title={Text("Routine needs attention")},text={Text(message)},confirmButton={TextButton(onClick={error=null}) {Text("OK")}}) }
}
