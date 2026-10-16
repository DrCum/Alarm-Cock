package com.taskalarm.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taskalarm.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtendedTaskSettings(task: TaskDraft, onChange: (TaskDraft) -> Unit) {
  val activity = LocalActivity.current as? MainActivity ?: return
  val ready = activity.engine.readiness()
  if(task.kind == "walk") {
    InfoButton("Walking task","Walk with the phone and keep the task visible. Only steps detected during the active task count. Step sensors measure movement; they cannot prove you are standing.")
    IntegerField("Steps per round",task.stepTarget,"5–200") { onChange(task.copy(stepTarget=it)) }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(20,30,40).forEach { steps -> FilterChip(task.stepTarget==steps,{onChange(task.copy(stepTarget=steps))},label={Text("$steps steps")}) } }
    Body("Step sensor: ${if(ready.optBoolean("stepSensor")) "available" else "unavailable; fallback will be offered"}")
    if(!ready.optBoolean("activityPermission")) Secondary("Allow walking permission") { activity.openAccess("activity") }
  }
  if(task.kind == "light") {
    IntegerField("Minimum light (lux)",task.lightLux,"50–2000; try the live meter in your room") { onChange(task.copy(lightLux=it)) }
    IntegerField("Continuous hold (seconds)",task.lightHoldSeconds,"2–30") { onChange(task.copy(lightHoldSeconds=it)) }
    Body("Light sensor: ${if(ready.optBoolean("lightSensor")) "available" else "unavailable; fallback will be offered"}")
    InfoButton("Light task","This task measures brightness, not location or daylight. Any value at or above the target qualifies. The alarm flashlight pauses during measurement.")
  }
  if(task.kind == "maze") {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("tilt","touch").forEach { mode -> FilterChip(task.controlMode==mode,{onChange(task.copy(controlMode=mode))},label={Text(mode.replaceFirstChar(Char::uppercase))}) } }
    InfoButton("Maze controls","Tilt the phone or use direction controls. Difficulty changes maze length and ball speed. Checkpoints must be reached in order.")
    if(!ready.optBoolean("tiltSensor") && task.controlMode=="tilt") Body("No tilt sensor detected. Choose touch controls or use the configured fallback.")
  }
  Text("If the sensor is unavailable",style=MaterialTheme.typography.titleSmall)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("math","text").forEach { kind -> FilterChip(task.fallbackKind==kind,{onChange(task.copy(fallbackKind=kind))},label={Text("${kind.replaceFirstChar(Char::uppercase)} fallback")}) } }
  InfoButton("Sensor fallback","The fallback replaces this task if sensor access fails or no initial reading arrives within 15 seconds. Walking and tilt also detect later interruptions in events. Remaining rounds and following tasks still apply.")
  if(task.kind != "maze") NativeTaskDemo(task)
}

@Composable
internal fun IntegerField(label: String, value: Int, hint: String, onChange: (Int) -> Unit) {
  OutlinedTextField(if(value==0) "" else value.toString(),{onChange(it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)},label={Text(label)},supportingText={Text(hint)},singleLine=true,
    keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),modifier=Modifier.fillMaxWidth())
}

@Composable
internal fun NativeTaskDemo(task: TaskDraft) {
  var demo by remember { mutableStateOf(false) }
  var generation by remember { mutableIntStateOf(0) }
  val valid = runCatching { WakeUpPolicy.validate(AlarmDraft(dismissTasks=listOf(task)).json()) }.isSuccess
  Secondary(if(task.kind=="maze") "Try maze demo" else "Test sensor & preview task",valid) { demo=true; generation++ }
  if(demo) androidx.compose.ui.window.Dialog(onDismissRequest={demo=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
    Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f),color=MaterialTheme.colorScheme.background) {
      Page {
        Secondary("Close demo") { demo=false }
        Text("${task.name} · Demo",style=MaterialTheme.typography.titleLarge)
        Body("This preview does not start or dismiss an alarm.")
        key(generation) { NativeTaskSession(task,null,false,true) }
        Secondary("Restart demo") { generation++ }
      }
    }
  }
}

@Composable
internal fun NativeTaskBoard(session: JSONObject, wake: Boolean) {
  val tasks = session.getJSONObject("alarm").getJSONArray(if(wake) "wakeTasks" else if(session.optString("action")=="snooze") "snoozeTasks" else "dismissTasks")
  val task = TaskDraft.from(tasks.getJSONObject(session.getInt("taskIndex")))
  NativeTaskSession(task,session,wake,false)
}

@Composable
private fun NativeTaskSession(task: TaskDraft, session: JSONObject?, wake: Boolean, demo: Boolean) {
  val activity = LocalActivity.current as? MainActivity ?: return
  val owner = LocalLifecycleOwner.current
  val challenge = session?.getString("challengeId") ?: remember { UUID.randomUUID().toString() }
  val id = session?.getString("sessionId") ?: "demo"
  val runner = remember(challenge) { if(demo) NativeChallenge(activity,task.json(),JSONObject()) else null }
  var frame by remember(challenge) { mutableStateOf<NativeFrame?>(null) }
  var error by remember(challenge) { mutableStateOf<String?>(null) }
  var x by remember(challenge) { mutableFloatStateOf(0f) }; var y by remember(challenge) { mutableFloatStateOf(0f) }
  LaunchedEffect(challenge,owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      x=0f; y=0f
      try {
        if(demo) runner!!.start() else activity.engine.startNative(id,challenge,wake)
        while(true) {
          val next = if(demo) runner!!.frame(x,y) else activity.engine.nativeFrame(id,challenge,wake,x,y)
          frame=next
          if(next.complete) break
          delay(33)
        }
      } catch(failure: Exception) {
        if(failure is CancellationException) throw failure
        error=failure.message
      } finally { if(demo) runner!!.stop() else activity.engine.stopNative(challenge) }
    }
  }
  DisposableEffect(challenge) { onDispose { if(demo) runner?.stop() else activity.engine.stopNative(challenge) } }
  Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
    when(task.kind) {
      "walk" -> {
        Text("${frame?.steps ?: 0} / ${task.stepTarget} steps",style=MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress={((frame?.steps ?: 0).toFloat()/task.stepTarget.coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
        Body("Walk with your phone. Credit pauses when you leave this task.")
      }
      "light" -> {
        Text("${frame?.lux?.toInt() ?: 0} lux · target ${task.lightLux}",style=MaterialTheme.typography.headlineSmall)
        Text("${(frame?.heldMs ?: 0)/1000f} / ${task.lightHoldSeconds} seconds")
        LinearProgressIndicator(progress={((frame?.heldMs ?: 0).toFloat()/(task.lightHoldSeconds.coerceAtLeast(1)*1000)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
        Body("Keep the sensor uncovered. Brightness must stay above the threshold; leaving the task resets the hold.")
      }
      "maze" -> {
        MazeField(frame?.maze)
        Text("Checkpoints: ${frame?.maze?.reached ?: 0} / ${frame?.maze?.checkpoints?.size ?: 0}")
        if(task.controlMode=="tilt") Secondary("Calibrate center") { if(demo) runner?.calibrate() else activity.engine.calibrateNative(challenge) }
        else DirectionControls { dx,dy -> x=dx; y=dy }
        Body(if(task.controlMode=="tilt") "Hold the phone comfortably, calibrate, then tilt toward each numbered checkpoint." else "Hold an arrow to move the ball. Reach the checkpoints in order.")
      }
    }
    if(frame?.complete==true) Text(if(demo) "Demo complete!" else "Task complete")
    frame?.unavailable?.let { reason ->
      Body(reason)
      if(!demo) Secondary("Use ${task.fallbackKind} fallback") { runCatching { activity.engine.nativeFallback(id,challenge,wake) }.onFailure { error=it.message } }
    }
    error?.let { Body(it) }
  }
}

@Composable
private fun MazeField(frame: MazeFrame?) {
  Canvas(Modifier.fillMaxWidth().height(320.dp).semantics { contentDescription="Maze field. Follow numbered checkpoints with the ball." }) {
    drawRect(Folderstorm.Inset)
    if(frame==null) return@Canvas
    frame.walls.forEach { drawRect(Folderstorm.Lavender,Offset(it.x*size.width,it.y*size.height),Size(it.width*size.width,it.height*size.height)) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK; textAlign=android.graphics.Paint.Align.CENTER; textSize=13.dp.toPx() }
    frame.checkpoints.forEachIndexed { i,p ->
      val center=Offset(p.x*size.width,p.y*size.height)
      drawCircle(if(i < frame.reached) Folderstorm.PaleLavender else Folderstorm.Gold,14.dp.toPx(),center)
      drawContext.canvas.nativeCanvas.drawText(if(i==frame.checkpoints.lastIndex) "E" else "${i+1}",center.x,center.y+5.dp.toPx(),paint)
    }
    drawCircle(Folderstorm.Text,MazeGame.RADIUS*size.width,Offset(frame.x*size.width,frame.y*size.height))
  }
}

@Composable
private fun DirectionControls(onDirection: (Float,Float) -> Unit) {
  val scope=rememberCoroutineScope()
  Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
    DirectionButton("↑",0f,-1f,onDirection) { scope.launch { onDirection(0f,-1f); delay(250); onDirection(0f,0f) } }
    Row(horizontalArrangement=Arrangement.spacedBy(60.dp)) {
      DirectionButton("←",-1f,0f,onDirection) { scope.launch { onDirection(-1f,0f); delay(250); onDirection(0f,0f) } }
      DirectionButton("→",1f,0f,onDirection) { scope.launch { onDirection(1f,0f); delay(250); onDirection(0f,0f) } }
    }
    DirectionButton("↓",0f,1f,onDirection) { scope.launch { onDirection(0f,1f); delay(250); onDirection(0f,0f) } }
  }
}
@Composable
private fun DirectionButton(label: String, x: Float, y: Float, onDirection: (Float,Float)->Unit, accessibleClick:()->Unit) {
  Surface(Modifier.size(56.dp).semantics { contentDescription="Move $label"; onClick { accessibleClick(); true } }.pointerInput(x,y) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed=false); onDirection(x,y); try { waitForUpOrCancellation() } finally { onDirection(0f,0f) } }
  },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {
    Box(contentAlignment=Alignment.Center) { Text(label,style=MaterialTheme.typography.headlineMedium) }
  }
}
