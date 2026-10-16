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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Collections

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
    var editToken by rememberSaveable { mutableStateOf<String?>(null) }
    var openingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var savedEditJson by rememberSaveable { mutableStateOf<String?>(null) }
    var canRevertSaved by rememberSaveable { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf("Changes save automatically") }
    var editorSaving by remember { mutableStateOf(false) }
    var editorClosing by remember { mutableStateOf(false) }
    val editMutex = remember { Mutex() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var health by rememberSaveable { mutableStateOf(false) }
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
    val draftToken = editToken
    val draft = draftJson?.let { AlarmDraft.from(JSONObject(it)) }
    LaunchedEffect(active?.optString("sessionId"), wakeCheck?.optString("sessionId")) { scanAction = null }
    fun submit(session: JSONObject, check: Boolean, answer: String) {
      work {
        val correct = withContext(Dispatchers.IO) {
          if (check) activity.engine.submitWake(session.getString("sessionId"), session.getString("challengeId"), answer)
          else activity.engine.submit(session.getString("sessionId"), session.getString("challengeId"), answer)
        }
        if (!correct) error = if (session.getString("kind") == "barcode") "That barcode doesn’t match your saved barcode." else "That answer didn’t match. Give it another try."
      }
    }
    suspend fun prepareDraft(current: AlarmDraft): AlarmDraft = withContext(Dispatchers.IO) {
      current.validate()
      val sound = if(current.normalize && current.soundUri.isNotBlank()) current.playbackUri.ifBlank { SoundProcessor.prepare(activity,current.soundUri,true) } else ""
      current.copy(playbackUri=sound)
    }
    fun openEditor(alarm: AlarmDraft) {
      work {
        val token=withContext(Dispatchers.IO) { activity.engine.beginEdit(alarm.json().toString()) }
        val status=JSONObject(activity.engine.editStatus(token))
        editToken=token; openingJson=alarm.json().toString(); savedEditJson=openingJson; draftJson=openingJson
        canRevertSaved=status.getBoolean("canRevert"); saveNotice=if(status.getBoolean("exists")) "Saved · autosave on" else "New alarm · autosave on"
      }
    }
    fun finishEditor(discard: Boolean) {
      val token=editToken ?: return
      val current=draft ?: return
      if(editorClosing || busy) return
      editorClosing=true
      scope.launch {
        try {
          var restored=true
          editMutex.withLock {
            if(discard) restored=withContext(Dispatchers.IO) { activity.engine.discardEdit(token) }
            else {
              val prepared=prepareDraft(current)
              withContext(Dispatchers.IO) { activity.engine.saveEdit(token,prepared.json().toString()); activity.engine.closeEdit(token) }
            }
          }
          if(editToken==token) { draftJson=null; editToken=null; openingJson=null; savedEditJson=null }
          refresh()
          if(!restored) error="The alarm rang or changed while editing. Its current settings were kept; reopen it to edit again."
        } catch(failure: Exception) { error=failure.message ?: "Could not finish this edit" }
        finally {editorClosing=false}
      }
    }
    fun revertEditor() {
      val token=editToken ?: return
      if(editorClosing || busy) return
      val opening=draftJson!=openingJson
      editorClosing=true
      scope.launch {
        try {
          val restored=editMutex.withLock { withContext(Dispatchers.IO) { activity.engine.revertEdit(token,opening) } }
          draftJson=restored; savedEditJson=restored; openingJson=restored
          canRevertSaved=JSONObject(activity.engine.editStatus(token)).getBoolean("canRevert")
          saveNotice="Reverted · autosave on"; refresh()
        } catch(failure: Exception) {error=failure.message ?: "Could not revert this edit"}
        finally {editorClosing=false}
      }
    }
    LaunchedEffect(editToken,draftJson,busy,editorClosing,active?.optString("sessionId"),wakeCheck?.optString("sessionId")) {
      val token=editToken ?: return@LaunchedEffect
      val captured=draftJson ?: return@LaunchedEffect
      if(captured==savedEditJson || busy || editorClosing || active!=null || wakeCheck!=null) return@LaunchedEffect
      saveNotice="Changes pending…"
      delay(800)
      editorSaving=true
      try {
        val saved=editMutex.withLock {
          val prepared=prepareDraft(AlarmDraft.from(JSONObject(captured)))
          withContext(Dispatchers.IO) { AlarmDraft.from(JSONObject(activity.engine.saveEdit(token,prepared.json().toString()))).json().toString() }
        }
        savedEditJson=saved
        if(editToken==token && draftJson==captured) draftJson=saved
        canRevertSaved=JSONObject(activity.engine.editStatus(token)).getBoolean("canRevert")
        saveNotice="Saved · autosave on"
      } catch(cancelled: CancellationException) {throw cancelled}
      catch(failure: Exception) {saveNotice="Not saved · ${failure.message ?: "Check these settings"}"}
      finally {editorSaving=false}
    }
    BackHandler(enabled=scanAction!=null || active!=null || wakeCheck!=null || draft!=null || health) {
      if(scanAction!=null) scanAction=null
      else if(active==null && wakeCheck!=null) activity.moveTaskToBack(true)
      else if(active==null && !busy && !editorClosing) {if(health) health=false else finishEditor(false)}
    }
    fun prepareAndRun(test: Boolean, wake: Boolean = false, escalation: Boolean = false) {
      val current=draft ?: return
      work {
        val prepared=editMutex.withLock {
          val result=prepareDraft(current)
          withContext(Dispatchers.IO) {
            if(escalation) activity.engine.testEscalation(result.json().toString())
            else if(wake) activity.engine.testWake(result.json().toString())
            else activity.engine.test(result.json().toString())
          }
          result
        }
        draftJson=prepared.json().toString()
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
          health -> HealthScreen(state, onBack = { health = false },
            onAccess = { try { activity.openAccess(it) } catch(failure: Exception) { error = failure.message } },
            onTest = { work { withContext(Dispatchers.IO) { activity.engine.testDelivery() } } })
          draft != null -> AlarmEditorContent(draft, state?.optJSONObject("readiness"), busy || editorClosing,
            onChange = { if (!busy && !editorClosing) draftJson = it.json().toString() },
            saveStatus=if(editorClosing) "Updating…" else if(editorSaving) "Saving…" else saveNotice, canRevert=canRevertSaved || draftJson!=openingJson,
            onDiscard={finishEditor(true)}, onRevert={revertEditor()},
            onBack = { finishEditor(false) }, onSave = { finishEditor(false) }, onTest = { prepareAndRun(true) },
            onTestWake = { prepareAndRun(true, true) },
            onTestEscalation = { prepareAndRun(true, escalation = true) },
            onPreset = { id -> work {
              val uri = withContext(Dispatchers.IO) { BuiltinSounds.prepare(activity, id) }
              draftJson = draft.copy(soundUri = uri, soundName = "Task Alarm · ${BuiltinSounds.names[id]}", normalize = false, playbackUri = "").json().toString()
            } },
            onAccess = { kind -> try { activity.openAccess(kind) } catch (failure: Exception) { error = failure.message } },
            onSound = { custom -> activity.chooseSound(custom, { uri, name ->
              draftJson?.let { saved ->
                val current = AlarmDraft.from(JSONObject(saved))
                if (current.id == draft.id && editToken==draftToken) draftJson = current.copy(soundUri = uri, soundName = name, playbackUri = "").json().toString()
              }
            }, { error = it }) },
            onPlaylistSound = { custom -> activity.chooseSound(custom, { uri, name ->
              draftJson?.let { saved ->
                val current = AlarmDraft.from(JSONObject(saved))
                if (current.id == draft.id && editToken==draftToken && current.escalationTones.size < 12)
                  draftJson = current.copy(escalationTones = current.escalationTones + AlarmTone(uri, name.take(120))).json().toString()
              }
            }, { error = it }) },
            onEnroll = { action, id -> if (!busy) scanAction = { value ->
              draftJson?.let { saved ->
                val current = AlarmDraft.from(JSONObject(saved))
                if(editToken==draftToken) draftJson = current.withTasks(action, current.tasks(action).map { if (it.id == id) it.copy(barcode = value) else it }).json().toString()
              }
            } })
          else -> HomeContent(state, busy,
            onHealth = { health = true }, onSkip = { alarm, undo -> work { withContext(Dispatchers.IO) { activity.engine.skipNext(alarm.id,undo) } } },
            onAdd = { openEditor(AlarmDraft()) }, onEdit = { openEditor(it) },
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
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable
internal fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
  Surface(color = Panel, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}
@Composable
internal fun Eyebrow(text: String) { Text(text, color = Gold, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp) }
@Composable
internal fun Body(text: String) { Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium) }
@Composable
internal fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(text) }
}
@Composable
internal fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
  onHealth: () -> Unit, onSkip: (AlarmDraft, Boolean) -> Unit, onToggle: (AlarmDraft, Boolean) -> Unit, onDelete: (AlarmDraft) -> Unit, onTest: (AlarmDraft) -> Unit, onAccess: (String) -> Unit) {
  Page {
    Spacer(Modifier.height(10.dp)); Eyebrow("TASK ALARM")
    Text("A better start.", style = MaterialTheme.typography.headlineLarge)
    Body("A little challenge between you and the snooze button.")
    AccessCard(state?.optJSONObject("readiness"), onAccess)
    Secondary("Alarm health & delivery test", !busy, onHealth)
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
    val alarms = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }.filterNot { it.optBoolean("deliveryTest") }
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
          if((alarm.skipDate.isNotEmpty() && alarm.skipDate >= java.time.LocalDate.now().toString()) || alarm.skipAt > System.currentTimeMillis() || alarm.skipOneTime) TextButton(enabled = !busy, onClick = { onSkip(alarm,true) }) { Text("Undo skip") }
          else if(alarm.enabled) TextButton(enabled = !busy, onClick = { onSkip(alarm,false) }) { Text("Skip next") }
          TextButton(enabled = !busy, onClick = { onEdit(alarm) }) { Text("Edit") }
          TextButton(enabled = !busy, onClick = { onTest(alarm) }) { Text("Test now") }
          TextButton(enabled = !busy, onClick = { onDelete(alarm) }) { Text("Delete") }
        }
      }
    }
    val gameStats = state?.optJSONObject("gameStats")
    if (gameStats != null && (gameStats.optInt("breakoutBest") > 0 || gameStats.optInt("breakoutCompleted") > 0)) Body("Breakout best · ${gameStats.getInt("breakoutBest")} points · ${gameStats.getInt("breakoutCompleted")} tasks completed")
    Body("All tasks and games work offline. Sensor tasks include a configured fallback.")
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
  onSound: (Boolean) -> Unit, onEnroll: (String, String) -> Unit, onPreset: (String) -> Unit,
  onPlaylistSound: (Boolean) -> Unit, onTestEscalation: () -> Unit, saveStatus: String, canRevert: Boolean,
  onDiscard: ()->Unit, onRevert: ()->Unit) {
  val editorActivity=androidx.activity.compose.LocalActivity.current as? MainActivity
  Page {
    EditorActions(saveStatus,busy,canRevert,onBack,onDiscard,onRevert)
    Text("Edit alarm", style = MaterialTheme.typography.headlineLarge)
    AccessCard(readiness, onAccess)
    RoutineSettings(alarm,onChange)
    EditorSection("Time / days / name", "${alarm.hour.padStart(2,'0')}:${alarm.minute.padStart(2,'0')} · ${alarm.label}", initiallyOpen=true, info="Times follow the phone’s local timezone. Select repeat days or use the weekday/weekend shortcuts. An empty day selection rings once at the next matching time.") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(alarm.hour, { onChange(alarm.copy(hour = it.filter(Char::isDigit).take(2))) }, label = { Text("Hour · 24h") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.headlineLarge)
      Text(":", fontSize = 40.sp)
      OutlinedTextField(alarm.minute, { onChange(alarm.copy(minute = it.filter(Char::isDigit).take(2))) }, label = { Text("Minute") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.headlineLarge)
    }
    OutlinedTextField(alarm.label, { onChange(alarm.copy(label = it.take(120))) }, label = { Text("Alarm name") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Enabled"); Switch(alarm.enabled, { onChange(alarm.copy(enabled = it)) })
    }
    Text("Repeat", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      weekdayNames.forEachIndexed { index, label -> FilterChip(selected = index + 1 in alarm.days,
        onClick = { onChange(alarm.copy(days = if (index + 1 in alarm.days) alarm.days - (index + 1) else alarm.days + (index + 1))) }, label = { Text(label) }) }
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      TextButton(onClick={onChange(alarm.copy(days=setOf(1,2,3,4,5)))}) {Text("Weekdays")}
      TextButton(onClick={onChange(alarm.copy(days=setOf(6,7)))}) {Text("Weekends")}
      TextButton(onClick={onChange(alarm.copy(days=emptySet()))}) {Text("One time")}
    }
    val preview=runCatching { editorActivity?.engine?.preview(alarm.json().toString()) }.getOrNull()
    if(alarm.enabled && preview!=null) Body("Next: ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(preview))}")
    }
    ScheduleSettings(alarm,onChange)
    EditorSection("Task behavior", if(alarm.progressiveDifficulty) "Progressive difficulty on" else "Configured difficulty", info="Shuffle chooses a new order when an action starts. Every configured task and round remains required.") {
    DifficultySettings(alarm,onChange)
    ToggleRow("Shuffle task order", alarm.shuffleTasks) { onChange(alarm.copy(shuffleTasks = it)) }
    }
    listOf("dismiss", "snooze").forEach { action ->
      EditorSection(if(action=="dismiss") "Dismiss tasks" else "Snooze tasks", summary(alarm.tasks(action)), info="Complete every selected task and round. An empty list allows this action immediately. Switching actions starts that action’s tasks from the beginning.") {
        TaskListEditor(alarm,action,onChange,onEnroll)
      }
    }
    EditorSection("Snooze settings", "${alarm.snoozeMinutes} min · ${if(alarm.maxSnoozes==0) "unlimited" else alarm.maxSnoozes.toString()} snoozes", info="After the snooze limit, complete dismiss tasks. The count carries through wake-up retries and resets for a new regular occurrence.") {
    Text("Snooze length", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 5, 10, 15, 30, 60).forEach { minutes ->
      FilterChip(alarm.snoozeMinutes == minutes, { onChange(alarm.copy(snoozeMinutes = minutes)) }, label = { Text("$minutes min") })
    } }
    Text("Snooze limit", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 1, 2, 3, 5, 10).forEach { count ->
      FilterChip(alarm.maxSnoozes == count, { onChange(alarm.copy(maxSnoozes = count)) }, label = { Text(if (count == 0) "Unlimited" else count.toString()) })
    } }
    }
    EditorSection("Flashlight",alarm.flashlightMode.replaceFirstChar(Char::uppercase), info="Keep the flashlight steady or flash once per second until dismissal or snooze. Muting sound keeps it active. It pauses while scanning a barcode or completing a light task.") {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("off" to "Off", "steady" to "Steady", "flashing" to "Flashing").forEach { (mode, label) ->
          FilterChip(alarm.flashlightMode == mode, { onChange(alarm.copy(flashlightMode = mode)) }, label = { Text(label) },
            enabled = !busy && (mode == "off" || readiness?.optBoolean("flashlightAvailable", true) != false))
        }
      }
      if (readiness?.optBoolean("flashlightAvailable", true) == false) Body("This phone has no flashlight.")
      if (alarm.flashlightMode != "off" && readiness?.optBoolean("cameraPermission") != true) {
        InfoButton("Flashlight access","Android requires camera permission to control the flashlight. If permission was denied permanently, enable Camera in app settings.")
        Secondary("Allow camera for flashlight", !busy) { onAccess("camera") }
        Secondary("Open app settings", !busy) { onAccess("appSettings") }
      }
    }
    EditorSection("Volume & solving", info="Choose the controls and effects used while the alarm is ringing and tasks are being solved.") {
      ToggleRow("Moving dismiss button", alarm.randomDismiss) { onChange(alarm.copy(randomDismiss = it)) }
      InfoButton("Moving dismiss","Use a smaller Dismiss button that changes position every five seconds. Tapping it starts your configured dismiss tasks.")
      ToggleRow("Vibrate while ringing", alarm.vibrate) { onChange(alarm.copy(vibrate = it)) }
      InfoButton("Alarm vibration","Repeat an alarm vibration until dismissal or snooze. Muting sound leaves vibration active.")
      ToggleRow("Protect alarm volume", alarm.protectVolume) { onChange(alarm.copy(protectVolume = it)) }
      InfoButton("Volume protection","Keep the volume level present when ringing starts. If already silent, use 50%. Android system controls can still override this. The previous level is restored when ringing ends.")
      ToggleRow("Allow mute while solving", alarm.allowMuteWhileSolving) { onChange(alarm.copy(allowMuteWhileSolving = it)) }
      InfoButton("Mute while solving","Show a mute/unmute button during tasks. Muting does not finish a task or stop the alarm. Leaving the app resumes sound.")
    }
    EditorSection("Volume escalation",if(alarm.escalationEnabled) "On · 45-second tone rotation" else "Off", info="Raise alarm volume to maximum and rotate tones every 45 seconds. Unanswered means no Dismiss or Snooze action selected. The next ring after two completed snoozes escalates immediately. Once triggered, escalation carries through snoozes and wake-up retries. Mute while solving still works, and the previous volume is restored when ringing stops. Android may restrict volume changes.") {
      ToggleRow("Escalate unanswered alarms or after two snoozes", alarm.escalationEnabled) { onChange(alarm.copy(escalationEnabled = it)) }
      if (alarm.escalationEnabled) {
        OutlinedTextField(if (alarm.escalationAfterMinutes == 0) "" else alarm.escalationAfterMinutes.toString(),
          { onChange(alarm.copy(escalationAfterMinutes = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0)) },
          label = { Text("Unanswered delay (minutes)") }, supportingText = { Text("1–120 minutes") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Text("Escalation playlist · 45 seconds each", style = MaterialTheme.typography.titleMedium)
        alarm.escalationTones.forEachIndexed { index, tone ->
          Text("${index + 1}. ${tone.name}")
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy && index > 0, onClick = {
              onChange(alarm.copy(escalationTones = alarm.escalationTones.toMutableList().also { Collections.swap(it, index, index - 1) }))
            }) { Text("Move up") }
            TextButton(enabled = !busy && index < alarm.escalationTones.lastIndex, onClick = {
              onChange(alarm.copy(escalationTones = alarm.escalationTones.toMutableList().also { Collections.swap(it, index, index + 1) }))
            }) { Text("Move down") }
            TextButton(enabled = !busy, onClick = { onChange(alarm.copy(escalationTones = alarm.escalationTones.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
          }
        }
        if (alarm.escalationTones.size < 12) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BuiltinSounds.names.forEach { (id, label) ->
            OutlinedButton(enabled = !busy, onClick = { onChange(alarm.copy(escalationTones = alarm.escalationTones + AlarmTone("builtin:$id", label))) }) { Text("+ $label") }
          } }
          Secondary("Add phone tone", !busy) { onPlaylistSound(false) }
          Secondary("Add audio file", !busy) { onPlaylistSound(true) }
        }
        TextButton(enabled = !busy, onClick = { onChange(alarm.copy(escalationTones = AlarmTone.defaults())) }) { Text("Reset playlist to bundled tones") }
        InfoButton("Escalation playlist","Choose 1–12 tones. Short tones repeat until their 45-second slot ends; the playlist then loops.")
        Secondary("Test escalation now", !busy, onTestEscalation)
      }
    }
    EditorSection("Wake-up check",if(alarm.wakeEnabled) "On" else "Off", info="After dismissal, confirm that you are up. No or a missed response deadline rings the alarm again. Checks more than two minutes away get an advance notification two minutes after dismissal, with their chosen time and an early Dismiss check action. Completing confirmation tasks does not extend the response deadline.") {
      ToggleRow("Check that I’m out of bed", alarm.wakeEnabled) { onChange(alarm.copy(wakeEnabled = it)) }
      if (alarm.wakeEnabled) {
        ToggleRow("Randomize check delay",alarm.wakeRandom) { onChange(alarm.copy(wakeRandom=it)) }
        if(alarm.wakeRandom) {
          MinuteInput("Earliest check (minutes)",alarm.wakeMinMinutes) { onChange(alarm.copy(wakeMinMinutes=it)) }
          MinuteInput("Latest check (minutes)",alarm.wakeMaxMinutes) { onChange(alarm.copy(wakeMaxMinutes=it)) }
          InfoButton("Random check timing","The actual check time is chosen once at dismissal. The advance notification shows that chosen time.")
        } else MinuteInput("Ask after dismissal (minutes)", alarm.wakeDelayMinutes) { onChange(alarm.copy(wakeDelayMinutes = it)) }
        MinuteInput("Time to respond (minutes)", alarm.wakeResponseMinutes) { onChange(alarm.copy(wakeResponseMinutes = it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(alarm.wakeMode == "yes_no", { onChange(alarm.copy(wakeMode = "yes_no")) }, label = { Text("Yes / No") })
          FilterChip(alarm.wakeMode == "tasks", { onChange(alarm.copy(wakeMode = "tasks")) }, label = { Text("Complete tasks") })
        }
        if (alarm.wakeMode == "tasks") {
          TaskListEditor(alarm, "wake", onChange, onEnroll)
        }
        ToggleRow("Reuse dismiss tasks if it rings again", alarm.wakeReuseDismissTasks) { onChange(alarm.copy(wakeReuseDismissTasks = it)) }
        if (!alarm.wakeReuseDismissTasks) {
          Text("Tasks for the repeat alarm", style = MaterialTheme.typography.titleMedium)
          InfoButton("Repeat alarm tasks","These replace dismiss tasks when a wake-up check fails. Snooze tasks stay the same.")
          TaskListEditor(alarm, "retry", onChange, onEnroll)
        }
        Secondary("Test wake-up check now", !busy, onTestWake)
        InfoButton("Wake-up check test","The check opens immediately. Its response deadline still applies. No or timeout rings a test alarm; finishing that test ends it.")
      }
    }
    EditorSection("Alarm sound",alarm.soundName, info="Choose a phone alarm sound, bundled tone, or imported file. Optional normalization raises the sample peak without clipping; the phone’s alarm volume still controls playback. Files up to 5 minutes are supported; unsupported or oversized files fail before saving.") {
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
    }
    EditorSection("Test alarm",info="A test rings immediately without changing the saved schedule or creating follow-up checks. Test snooze ends the test. Use the separate wake-up check test to try that flow.") {
      Secondary("Test sound & tasks now",!busy,onTest)
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Secondary("Done",!busy,onSave)
    Spacer(Modifier.height(20.dp))
  }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChange)
  }
}
@Composable
internal fun MinuteInput(label: String, value: Int, onChange: (Int) -> Unit) {
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
    listOf("text", "math", "barcode", "breakout", "memory", "maze", "walk", "light").forEach { kind -> OutlinedButton(onClick = { onChange(alarm.withTasks(action, alarm.tasks(action) + TaskDraft(kind = kind))) }) { Text("+ ${kind.replaceFirstChar(Char::uppercase)}") } }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(index: Int, task: TaskDraft, onChange: (TaskDraft) -> Unit, onRemove: () -> Unit, onEnroll: () -> Unit) {
  CardBlock {
    Text("${index + 1}. ${task.name}", style = MaterialTheme.typography.titleMedium)
    if(task.kind in listOf("maze", "walk", "light")) ExtendedTaskSettings(task,onChange)
    if (task.kind !in listOf("barcode", "walk", "light")) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("easy", "medium", "hard").forEach { level ->
        FilterChip(task.difficulty == level, { onChange(task.copy(difficulty = level)) }, label = { Text(level.replaceFirstChar(Char::uppercase)) })
      } }
      if (task.kind == "maze") {
        NativeTaskDemo(task)
      } else if (task.kind == "breakout") {
        BreakoutPreview(task)
        InfoButton("Breakout","Difficulty changes ball speed and paddle size. Only active play time completes the task; points are optional.")
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
        InfoButton(task.name,when(task.kind) {"text" -> "Case sensitive. A fresh string each round."; "memory" -> "Watch 3, 5, or 7 numbered tiles flash, then tap them in the same order."; else -> "New numbers each round. Enter the answer."})
      }
    } else if(task.kind == "barcode") {
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
    if (active.optBoolean("escalated")) Body("Escalated · maximum alarm volume · ${active.optString("escalationToneName", "45-second playlist")}")
    if (active.optBoolean("soundFallback")) Body("The selected sound could not play. Using the phone default or fallback beep.")
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
    Body(when (kind) { "text" -> "Type the text exactly, including capitals and symbols."; "math" -> "Solve the problem to keep going."; "breakout" -> "Active play completes the task. Points are just for fun."; "memory" -> "Watch the flashes, then repeat their order."; "maze" -> "Guide the ball through the checkpoints."; "walk" -> "Walk with the phone while this task is visible."; "light" -> "Turn on a room light or move to a brighter place."; else -> "Get up and scan a barcode with your camera." })
    Text(session.getString("prompt"), fontFamily = FontFamily.Monospace, fontSize = 25.sp, color = Lavender)
    if (kind == "breakout") BreakoutBoard(session, wake)
    else if (kind in listOf("maze", "walk", "light")) NativeTaskBoard(session,wake)
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
