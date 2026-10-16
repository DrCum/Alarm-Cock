package com.taskalarm.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.taskalarm.engine.SoundProcessor
import com.taskalarm.engine.BuiltinSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val Night = Color(0xff11131b)
private val Panel = Color(0xff1c1f2b)
private val Gold = Color(0xffffca7a)
private val Lavender = Color(0xffc4b4ff)
private val Muted = Color(0xffa6abbc)
private val weekdayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
internal fun TaskAlarmApp(activity: MainActivity) {
  MaterialTheme(colorScheme = darkColorScheme(primary = Gold, secondary = Lavender, background = Night, surface = Panel, onPrimary = Night)) {
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var deleteTarget by remember { mutableStateOf<AlarmDraft?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
      state = withContext(Dispatchers.IO) { JSONObject(activity.engine.state()) }
      activity.updateAlarmWindow()
    }
    fun work(block: suspend () -> Unit) {
      if (busy) return
      scope.launch {
        busy = true
        try { block(); refresh() }
        catch (failure: Exception) { error = failure.message ?: "Could not complete this action" }
        finally { busy = false }
      }
    }
    LaunchedEffect(Unit) {
      activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
          try { refresh() } catch (failure: Exception) { error = failure.message }
          delay(1000)
        }
      }
    }
    val active = state?.optJSONObject("active")
    val wakeCheck = state?.optJSONObject("wakeCheck")
    val draft = draftJson?.let { AlarmDraft.from(JSONObject(it)) }
    LaunchedEffect(active?.optString("sessionId"), wakeCheck?.optString("sessionId")) { scanAction = null }
    BackHandler(enabled = scanAction != null || active != null || wakeCheck != null || draft != null) {
      if (scanAction != null) scanAction = null
      else if (active == null && wakeCheck != null) activity.moveTaskToBack(true)
      else if (active == null && !busy) draftJson = null
    }
    fun submit(session: JSONObject, check: Boolean, answer: String) {
      work {
        val correct = withContext(Dispatchers.IO) {
          if (check) activity.engine.submitWake(session.getString("sessionId"), session.getString("challengeId"), answer)
          else activity.engine.submit(session.getString("sessionId"), session.getString("challengeId"), answer)
        }
        if (!correct) error = if (session.getString("kind") == "barcode") "That barcode doesn’t match your saved barcode." else "That answer didn’t match. Give it another try."
      }
    }
    fun prepareAndRun(test: Boolean, wake: Boolean = false) {
      val current = draft ?: return
      work {
        current.validate()
        val prepared = withContext(Dispatchers.IO) {
          val sound = if (current.normalize && current.soundUri.isNotBlank()) SoundProcessor.prepare(activity, current.soundUri, true) else ""
          val result = current.copy(playbackUri = sound)
          if (wake) activity.engine.testWake(result.json().toString())
          else if (test) activity.engine.test(result.json().toString()) else activity.engine.save(result.json().toString())
          result
        }
        draftJson = if (test) prepared.json().toString() else null
      }
    }
    Surface(Modifier.fillMaxSize(), color = Night) {
      Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
          scanAction != null -> BarcodeCamera(onRead = { value -> val action = scanAction; scanAction = null; action?.invoke(value) }, onCancel = { scanAction = null })
          active != null -> RingingContent(active, busy, onAction = { action ->
            work { withContext(Dispatchers.IO) { activity.engine.begin(active.getString("sessionId"), action) } }
          }, onSubmit = { submit(active, false, it) }, onScan = { scanAction = { submit(active, false, it) } },
            onMute = { muted -> work { withContext(Dispatchers.IO) { activity.engine.setMuted(active.getString("sessionId"), muted) } } })
          wakeCheck != null -> WakeCheckContent(wakeCheck, busy,
            onReply = { awake -> work { withContext(Dispatchers.IO) { activity.engine.respondWake(wakeCheck.getString("sessionId"), awake) } } },
            onSubmit = { submit(wakeCheck, true, it) }, onScan = { scanAction = { submit(wakeCheck, true, it) } })
          draft != null -> AlarmEditorContent(draft, state?.optJSONObject("readiness"), busy,
            onChange = { if (!busy) draftJson = it.json().toString() },
            onBack = { draftJson = null }, onSave = { prepareAndRun(false) }, onTest = { prepareAndRun(true) },
            onTestWake = { prepareAndRun(true, true) },
            onPreset = { id -> work {
              val uri = withContext(Dispatchers.IO) { BuiltinSounds.prepare(activity, id) }
              draftJson = draft.copy(soundUri = uri, soundName = "Task Alarm · ${BuiltinSounds.names[id]}", normalize = false, playbackUri = "").json().toString()
            } },
            onAccess = { kind -> try { activity.openAccess(kind) } catch (failure: Exception) { error = failure.message } },
            onSound = { custom -> activity.chooseSound(custom, { uri, name ->
              draftJson?.let { saved ->
                val current = AlarmDraft.from(JSONObject(saved))
                if (current.id == draft.id) draftJson = current.copy(soundUri = uri, soundName = name, playbackUri = "").json().toString()
              }
            }, { error = it }) },
            onEnroll = { action, id -> if (!busy) scanAction = { value ->
              draftJson?.let { saved ->
                val current = AlarmDraft.from(JSONObject(saved))
                draftJson = current.withTasks(action, current.tasks(action).map { if (it.id == id) it.copy(barcode = value) else it }).json().toString()
              }
            } })
          else -> HomeContent(state, busy,
            onAdd = { draftJson = AlarmDraft().json().toString() }, onEdit = { draftJson = it.json().toString() },
            onToggle = { alarm, enabled -> work { withContext(Dispatchers.IO) { activity.engine.save(alarm.copy(enabled = enabled).json().toString()) } } },
            onDelete = { deleteTarget = it },
            onTest = { alarm -> work { withContext(Dispatchers.IO) { activity.engine.test(alarm.json().toString()) } } },
            onAccess = { kind -> try { activity.openAccess(kind) } catch (failure: Exception) { error = failure.message } })
        }
      }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Check this alarm") }, text = { Text(message) },
      confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
    deleteTarget?.let { target -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete alarm?") },
      text = { Text("${target.label} will be removed.") },
      confirmButton = { TextButton(onClick = { deleteTarget = null; work { withContext(Dispatchers.IO) { activity.engine.delete(target.id) } } }) { Text("Delete") } },
      dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }) }
  }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
  Surface(color = Panel, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}
@Composable
private fun Eyebrow(text: String) { Text(text, color = Gold, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp) }
@Composable
private fun Body(text: String) { Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium) }
@Composable
private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(text) }
}
@Composable
private fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}
@Composable
private fun AccessCard(readiness: JSONObject?, onAccess: (String) -> Unit) {
  if (readiness == null || listOf("exactAlarms", "notifications", "fullScreen", "wakeNotifications").all { readiness.optBoolean(it, true) }) return
  CardBlock {
    Text("Get ready for mornings", style = MaterialTheme.typography.titleMedium)
    if (!readiness.optBoolean("exactAlarms")) Secondary("Allow alarms & reminders") { onAccess("exact") }
    if (!readiness.optBoolean("notifications")) Secondary("Allow alarm notifications") { onAccess("notifications") }
    if (!readiness.optBoolean("wakeNotifications", true)) Secondary("Allow wake-up check notifications") { onAccess("wakeNotifications") }
    if (!readiness.optBoolean("fullScreen")) Secondary("Allow full-screen alarms") { onAccess("fullScreen") }
    Body("Android needs alarm and notification access to enable alarms. Full-screen access lets the task screen appear over the lock screen.")
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(state: JSONObject?, busy: Boolean, onAdd: () -> Unit, onEdit: (AlarmDraft) -> Unit,
  onToggle: (AlarmDraft, Boolean) -> Unit, onDelete: (AlarmDraft) -> Unit, onTest: (AlarmDraft) -> Unit, onAccess: (String) -> Unit) {
  Page {
    Spacer(Modifier.height(10.dp)); Eyebrow("TASK ALARM")
    Text("A better start.", style = MaterialTheme.typography.headlineLarge)
    Body("A little challenge between you and the snooze button.")
    AccessCard(state?.optJSONObject("readiness"), onAccess)
    Text("Your alarms", style = MaterialTheme.typography.titleLarge)
    Primary("+ Add alarm", !busy, onAdd)
    val checks = state?.optJSONArray("wakeChecks")
    if (checks != null) for (index in 0 until checks.length()) {
      val check = checks.getJSONObject(index)
      CardBlock {
        Eyebrow("WAKE-UP CHECK SCHEDULED")
        Text(check.getString("label"), style = MaterialTheme.typography.titleMedium)
        Body("Ask at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(check.getLong("dueAt")))}")
        Body("Reply by ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(check.getLong("deadlineAt")))} or the alarm rings again.")
      }
    }
    val array = state?.optJSONArray("alarms")
    val alarms = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
      .sortedBy { it.getInt("hour") * 60 + it.getInt("minute") }
    if (state != null && alarms.isEmpty()) CardBlock {
      Text("07:00", fontSize = 46.sp, color = Lavender)
      Text("Your morning starts here.", style = MaterialTheme.typography.titleLarge)
      Body("Set a time, choose your tasks, and preview what you’ll need to do.")
    }
    alarms.forEach { value ->
      val alarm = AlarmDraft.from(value)
      CardBlock {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text("${alarm.hour.padStart(2, '0')}:${alarm.minute.padStart(2, '0')}", fontSize = 48.sp, color = if (alarm.enabled) Color.White else Muted)
          Switch(checked = alarm.enabled, enabled = !busy, onCheckedChange = { onToggle(alarm, it) })
        }
        Text(alarm.label, style = MaterialTheme.typography.titleMedium)
        Body(if (alarm.days.isEmpty()) "One time" else alarm.days.sorted().joinToString("  ") { weekdayNames[it - 1] })
        HorizontalDivider()
        Body("Dismiss · ${summary(alarm.dismissTasks)}")
        Body("Snooze · ${summary(alarm.snoozeTasks)}")
        if (alarm.enabled && value.has("nextAt")) Text("Next: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.getLong("nextAt")))}", color = Gold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !busy, onClick = { onEdit(alarm) }) { Text("Edit") }
          TextButton(enabled = !busy, onClick = { onTest(alarm) }) { Text("Test now") }
          TextButton(enabled = !busy, onClick = { onDelete(alarm) }) { Text("Delete") }
        }
      }
    }
    val gameStats = state?.optJSONObject("gameStats")
    if (gameStats != null && (gameStats.optInt("breakoutBest") > 0 || gameStats.optInt("breakoutCompleted") > 0)) Body("Breakout best · ${gameStats.getInt("breakoutBest")} points · ${gameStats.getInt("breakoutCompleted")} tasks completed")
    Body("Text, math, barcodes, memory, and Breakout work offline.")
    Spacer(Modifier.height(20.dp))
  }
}
private fun summary(tasks: List<TaskDraft>) = if (tasks.isEmpty()) "No tasks" else tasks.joinToString(" · ") {
  if (it.kind == "barcode") "Barcode" else "${it.difficulty} ${it.kind}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditorContent(alarm: AlarmDraft, readiness: JSONObject?, busy: Boolean, onChange: (AlarmDraft) -> Unit,
  onBack: () -> Unit, onSave: () -> Unit, onTest: () -> Unit, onTestWake: () -> Unit, onAccess: (String) -> Unit,
  onSound: (Boolean) -> Unit, onEnroll: (String, String) -> Unit, onPreset: (String) -> Unit) {
  Page {
    Secondary("← Alarms", !busy, onBack)
    Eyebrow("MAKE THE MORNING YOURS")
    Text("Set your alarm", style = MaterialTheme.typography.headlineLarge)
    AccessCard(readiness, onAccess)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(alarm.hour, { onChange(alarm.copy(hour = it.filter(Char::isDigit).take(2))) }, label = { Text("Hour · 24h") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.headlineLarge)
      Text(":", fontSize = 40.sp)
      OutlinedTextField(alarm.minute, { onChange(alarm.copy(minute = it.filter(Char::isDigit).take(2))) }, label = { Text("Minute") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.headlineLarge)
    }
    Body("Phone’s local time zone")
    OutlinedTextField(alarm.label, { onChange(alarm.copy(label = it.take(120))) }, label = { Text("Alarm name") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Enabled"); Switch(alarm.enabled, { onChange(alarm.copy(enabled = it)) })
    }
    Text("Repeat", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      weekdayNames.forEachIndexed { index, label -> FilterChip(selected = index + 1 in alarm.days,
        onClick = { onChange(alarm.copy(days = if (index + 1 in alarm.days) alarm.days - (index + 1) else alarm.days + (index + 1))) }, label = { Text(label) }) }
    }
    Body(if (alarm.days.isEmpty()) "Once, at the next occurrence of this time." else "Repeats on selected days.")
    ToggleRow("Shuffle task order", alarm.shuffleTasks) { onChange(alarm.copy(shuffleTasks = it)) }
    Body("Choose a new order when dismiss, snooze, or wake-up tasks start. Every configured task and round is still required.")
    listOf("dismiss", "snooze").forEach { action ->
      Text(if (action == "dismiss") "To dismiss" else "To snooze", style = MaterialTheme.typography.titleLarge)
      Body("Complete every selected task and round in order. An empty list allows this action immediately.")
      TaskListEditor(alarm, action, onChange, onEnroll)
    }
    Text("Snooze length", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 5, 10, 15, 30, 60).forEach { minutes ->
      FilterChip(alarm.snoozeMinutes == minutes, { onChange(alarm.copy(snoozeMinutes = minutes)) }, label = { Text("$minutes min") })
    } }
    Text("Snooze limit", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 1, 2, 3, 5, 10).forEach { count ->
      FilterChip(alarm.maxSnoozes == count, { onChange(alarm.copy(maxSnoozes = count)) }, label = { Text(if (count == 0) "Unlimited" else count.toString()) })
    } }
    Body("After the limit, complete dismiss tasks. The count carries through wake-up retries and resets for a new regular alarm occurrence.")
    Text("Flashlight", style = MaterialTheme.typography.titleLarge)
    CardBlock {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("off" to "Off", "steady" to "Steady", "flashing" to "Flashing").forEach { (mode, label) ->
          FilterChip(alarm.flashlightMode == mode, { onChange(alarm.copy(flashlightMode = mode)) }, label = { Text(label) },
            enabled = !busy && (mode == "off" || readiness?.optBoolean("flashlightAvailable", true) != false))
        }
      }
      Body("Keep the flashlight on or flash once per second while ringing and solving tasks, until dismissal or snooze. Muting sound keeps the light active. It pauses while scanning barcodes.")
      if (readiness?.optBoolean("flashlightAvailable", true) == false) Body("This phone has no flashlight.")
      if (alarm.flashlightMode != "off" && readiness?.optBoolean("cameraPermission") != true) {
        Body("Android requires camera permission to control the flashlight. If permission was denied permanently, enable Camera in app settings.")
        Secondary("Allow camera for flashlight", !busy) { onAccess("camera") }
        Secondary("Open app settings", !busy) { onAccess("appSettings") }
      }
    }
    Text("Volume & solving", style = MaterialTheme.typography.titleLarge)
    CardBlock {
      ToggleRow("Moving dismiss button", alarm.randomDismiss) { onChange(alarm.copy(randomDismiss = it)) }
      Body("Use a smaller Dismiss button that changes position every five seconds. Tapping it starts your configured dismiss tasks.")
      ToggleRow("Vibrate while ringing", alarm.vibrate) { onChange(alarm.copy(vibrate = it)) }
      Body("Repeat an alarm vibration until dismissal or snooze. Muting sound leaves vibration active.")
      ToggleRow("Protect alarm volume", alarm.protectVolume) { onChange(alarm.copy(protectVolume = it)) }
      Body("Keep the volume level present when ringing starts. If already silent, use 50%. Android system controls can still override this. The previous level is restored when ringing ends.")
      ToggleRow("Allow mute while solving", alarm.allowMuteWhileSolving) { onChange(alarm.copy(allowMuteWhileSolving = it)) }
      Body("Show a mute/unmute button during tasks. Muting does not finish a task or stop the alarm. Leaving the app resumes sound.")
    }
    Text("Wake-up check", style = MaterialTheme.typography.titleLarge)
    CardBlock {
      ToggleRow("Check that I’m out of bed", alarm.wakeEnabled) { onChange(alarm.copy(wakeEnabled = it)) }
      Body("After dismissing, get a notification or pop-up asking if you’re up. No response by the deadline makes the alarm ring again. Choosing No rings it again immediately.")
      if (alarm.wakeEnabled) {
        MinuteInput("Ask after dismissal (minutes)", alarm.wakeDelayMinutes) { onChange(alarm.copy(wakeDelayMinutes = it)) }
        MinuteInput("Time to respond (minutes)", alarm.wakeResponseMinutes) { onChange(alarm.copy(wakeResponseMinutes = it)) }
        Body("For checks more than two minutes away, a notification appears two minutes after dismissal with the check time and a Dismiss check button. Dismissing early cancels that check and its repeat alarm.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(alarm.wakeMode == "yes_no", { onChange(alarm.copy(wakeMode = "yes_no")) }, label = { Text("Yes / No") })
          FilterChip(alarm.wakeMode == "tasks", { onChange(alarm.copy(wakeMode = "tasks")) }, label = { Text("Complete tasks") })
        }
        if (alarm.wakeMode == "tasks") {
          Body("Complete every check task before the response time ends to confirm you’re up.")
          TaskListEditor(alarm, "wake", onChange, onEnroll)
        }
        ToggleRow("Reuse dismiss tasks if it rings again", alarm.wakeReuseDismissTasks) { onChange(alarm.copy(wakeReuseDismissTasks = it)) }
        if (!alarm.wakeReuseDismissTasks) {
          Text("Tasks for the repeat alarm", style = MaterialTheme.typography.titleMedium)
          Body("These replace the dismiss tasks when a wake-up check fails. Snooze tasks stay the same.")
          TaskListEditor(alarm, "retry", onChange, onEnroll)
        }
        Secondary("Test wake-up check now", !busy, onTestWake)
        Body("The check opens immediately in test mode. Its response deadline still applies. No or a timeout rings a test alarm; finishing that test ends it.")
      }
    }
    Text("Alarm sound", style = MaterialTheme.typography.titleLarge)
    CardBlock {
      Text(alarm.soundName, style = MaterialTheme.typography.titleMedium)
      Secondary("Choose phone alarm sound", !busy) { onSound(false) }
      Secondary("Import an audio file", !busy) { onSound(true) }
      Text("Bundled sounds", style = MaterialTheme.typography.titleSmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BuiltinSounds.names.forEach { (id, label) ->
        OutlinedButton(enabled = !busy, onClick = { onPreset(id) }) { Text(label) }
      } }
      if (alarm.soundUri.isNotEmpty()) {
        TextButton(onClick = { onChange(alarm.copy(soundUri = "", soundName = "Default phone alarm", normalize = false, playbackUri = "")) }) { Text("Use phone default") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Normalize chosen sound", modifier = Modifier.weight(1f)); Switch(alarm.normalize, { onChange(alarm.copy(normalize = it)) })
        }
      }
      Body("Normalization brings the audio peak near maximum without clipping. Up to 5 minutes of audio; large or unsupported files may fail. Your phone’s alarm volume still controls playback.")
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Primary(if (busy) "Preparing…" else "Save alarm", !busy, onSave)
    Secondary("Test sound & tasks now", !busy, onTest)
    Body("An alarm test rings immediately without scheduling this draft or follow-up checks. Test snooze ends the test. Use the separate wake-up check test above to try that flow.")
    Spacer(Modifier.height(20.dp))
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChange)
  }
}
@Composable
private fun MinuteInput(label: String, value: Int, onChange: (Int) -> Unit) {
  OutlinedTextField(if (value == 0) "" else value.toString(), { onChange(it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
    label = { Text(label) }, supportingText = { Text("1–1440 minutes") }, singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskListEditor(alarm: AlarmDraft, action: String, onChange: (AlarmDraft) -> Unit, onEnroll: (String, String) -> Unit) {
  alarm.tasks(action).forEachIndexed { index, task ->
    TaskCard(index, task, onChange = { changed -> onChange(alarm.withTasks(action, alarm.tasks(action).map { if (it.id == task.id) changed else it })) },
      onRemove = { onChange(alarm.withTasks(action, alarm.tasks(action).filterNot { it.id == task.id })) }, onEnroll = { onEnroll(action, task.id) })
  }
  if (alarm.tasks(action).size < 10) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf("text", "math", "barcode", "breakout", "memory").forEach { kind -> OutlinedButton(onClick = { onChange(alarm.withTasks(action, alarm.tasks(action) + TaskDraft(kind = kind))) }) { Text("+ ${kind.replaceFirstChar(Char::uppercase)}") } }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(index: Int, task: TaskDraft, onChange: (TaskDraft) -> Unit, onRemove: () -> Unit, onEnroll: () -> Unit) {
  CardBlock {
    Text("${index + 1}. ${task.name}", style = MaterialTheme.typography.titleMedium)
    if (task.kind != "barcode") {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("easy", "medium", "hard").forEach { level ->
        FilterChip(task.difficulty == level, { onChange(task.copy(difficulty = level)) }, label = { Text(level.replaceFirstChar(Char::uppercase)) })
      } }
      if (task.kind == "breakout") {
        BreakoutPreview(task)
        Body("Difficulty changes ball speed and paddle size. Points are optional; only active play time completes this task.")
        OutlinedTextField(if (task.gameSeconds == 0) "" else task.gameSeconds.toString(), { onChange(task.copy(gameSeconds = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
          label = { Text("Active play per round (seconds)") }, supportingText = { Text("5–3600 seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(30, 60, 120, 300).forEach { seconds ->
          FilterChip(task.gameSeconds == seconds, { onChange(task.copy(gameSeconds = seconds)) }, label = { Text("${seconds}s") })
        } }
        OutlinedTextField(if (task.gameIdleSeconds == 0) "" else task.gameIdleSeconds.toString(), { onChange(task.copy(gameIdleSeconds = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0)) },
          label = { Text("Reset after idle (seconds)") }, supportingText = { Text("5–120 seconds; default 20") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
      } else Column(Modifier.fillMaxWidth().background(Night, MaterialTheme.shapes.medium).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("EXAMPLE · ${task.difficulty.uppercase()}")
        Text(task.example, color = Lavender, fontFamily = FontFamily.Monospace, fontSize = 21.sp)
        Body(when (task.kind) { "text" -> "Case sensitive. A fresh string each round."; "memory" -> "Watch 3, 5, or 7 numbered tiles flash, then tap them in the same order."; else -> "New numbers each round. Enter the answer." })
      }
    } else {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("any", "specific").forEach { mode ->
        FilterChip(task.barcodeMode == mode, { onChange(task.copy(barcodeMode = mode)) }, label = { Text(if (mode == "any") "Any barcode" else "Specific barcode") })
      } }
      if (task.barcodeMode == "specific") {
        Body(if (task.barcode.isEmpty()) "Save a barcode from something you can get up to scan." else "Saved: ${task.barcode}")
        Secondary(if (task.barcode.isEmpty()) "Scan barcode to save" else "Replace saved barcode", onClick = onEnroll)
      } else Body("Any readable product barcode or QR code counts.")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Rounds: ${task.rounds}", modifier = Modifier.weight(1f))
      OutlinedButton(onClick = { onChange(task.copy(rounds = task.rounds - 1)) }, enabled = task.rounds > 1) { Text("−") }
      OutlinedButton(onClick = { onChange(task.copy(rounds = task.rounds + 1)) }, enabled = task.rounds < 10) { Text("+") }
    }
    TextButton(onClick = onRemove) { Text("Remove task") }
  }
}

@Composable
private fun RingingContent(active: JSONObject, busy: Boolean, onAction: (String) -> Unit, onSubmit: (String) -> Unit, onScan: () -> Unit, onMute: (Boolean) -> Unit) {
  val alarm = AlarmDraft.from(active.getJSONObject("alarm"))
  val action = if (active.isNull("action")) null else active.getString("action")
  Page {
    Spacer(Modifier.height(20.dp))
    Eyebrow(if (active.getBoolean("test")) "TEST ALARM" else "TIME TO WAKE UP")
    Text("${alarm.hour.padStart(2, '0')}:${alarm.minute.padStart(2, '0')}", fontSize = 62.sp)
    Text(alarm.label, style = MaterialTheme.typography.titleLarge)
    if (action == null) Body("Choose an action to begin its tasks.")
    else ChallengeCard(active, alarm.tasks(action), action.uppercase(), busy, onSubmit, onScan)
    if (action != null && alarm.allowMuteWhileSolving) {
      val muted = active.optBoolean("muted")
      Secondary(if (muted) "Unmute alarm" else "Mute while solving", !busy) { onMute(!muted) }
      if (muted) Body("Muted while you solve. Leaving the app resumes sound.")
    }
    if (active.optBoolean("volumeGuardBlocked")) Body("Android prevented the volume guard. Check your phone’s alarm volume.")
    if (active.optBoolean("flashlightBlocked")) Body("Flashlight unavailable. Check camera access or close other camera apps. Your alarm and tasks continue.")
    if (alarm.randomDismiss && action != "dismiss") MovingDismissButton(active.getString("sessionId"), !busy) { onAction("dismiss") }
    else Primary(if (action == "dismiss") "Complete tasks to dismiss" else "Dismiss alarm", !busy && action != "dismiss") { onAction("dismiss") }
    val snoozeCount = active.getJSONObject("alarm").optInt("snoozeCount")
    val maySnooze = alarm.maxSnoozes == 0 || snoozeCount < alarm.maxSnoozes
    Secondary(if (!maySnooze) "Snooze limit reached" else if (active.getBoolean("test")) "Test snooze" else "Snooze · ${alarm.snoozeMinutes} minutes", !busy && action != "snooze" && maySnooze) { onAction("snooze") }
    if (alarm.maxSnoozes > 0) Body("Snoozes used: $snoozeCount / ${alarm.maxSnoozes}")
    if (action != null) Body("Switching actions starts that action’s tasks from the beginning.")
    if (active.getBoolean("test")) Body("To stop a test, finish its tasks or use Android Settings → Apps → Task Alarm → Force stop.")
  }
}

@Composable
private fun ChallengeCard(session: JSONObject, tasks: List<TaskDraft>, prefix: String, busy: Boolean, onSubmit: (String) -> Unit, onScan: () -> Unit, wake: Boolean = false) {
  val kind = session.optString("kind")
  var answer by remember(session.optString("challengeId")) { mutableStateOf("") }
  CardBlock {
    val task = tasks[session.getInt("taskIndex")]
    Eyebrow("$prefix · TASK ${session.getInt("taskIndex") + 1}/${tasks.size} · ROUND ${session.getInt("round")}/${task.rounds}")
    Body(when (kind) { "text" -> "Type the text exactly, including capitals and symbols."; "math" -> "Solve the problem to keep going."; "breakout" -> "Active play completes the task. Points are just for fun."; "memory" -> "Watch the flashes, then repeat their order."; else -> "Get up and scan a barcode with your camera." })
    Text(session.getString("prompt"), fontFamily = FontFamily.Monospace, fontSize = 25.sp, color = Lavender)
    if (kind == "breakout") BreakoutBoard(session, wake)
    else if (kind == "memory") MemoryBoard(session, busy, onSubmit)
    else if (kind == "barcode") Primary("Open scanner", !busy, onScan)
    else {
      OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
          keyboardType = if (kind == "math") KeyboardType.Ascii else KeyboardType.Password))
      Primary("Check answer", !busy && answer.isNotEmpty()) { onSubmit(answer) }
    }
  }
}

@Composable
private fun WakeCheckContent(check: JSONObject, busy: Boolean, onReply: (Boolean) -> Unit, onSubmit: (String) -> Unit, onScan: () -> Unit) {
  val alarm = AlarmDraft.from(check.getJSONObject("alarm"))
  val remaining = ((check.getLong("deadlineAt") - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
  Page {
    Spacer(Modifier.height(24.dp))
    Eyebrow(if (check.optBoolean("test")) "TEST WAKE-UP CHECK" else "WAKE-UP CHECK")
    Text("Are you out of bed?", style = MaterialTheme.typography.headlineLarge)
    Text(alarm.label, style = MaterialTheme.typography.titleLarge)
    Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} remaining", fontSize = 30.sp, color = Lavender)
    Body("If you don’t finish this check in time, your alarm will ring again.")
    if (alarm.wakeMode == "yes_no") Primary("Yes, I’m up", !busy) { onReply(true) }
    else ChallengeCard(check, alarm.wakeTasks, "CONFIRM YOU’RE UP", busy, onSubmit, onScan, wake = true)
    Secondary("No — ring again now", !busy) { onReply(false) }
  }
}
