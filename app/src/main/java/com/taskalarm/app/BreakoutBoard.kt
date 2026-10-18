package com.taskalarm.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taskalarm.engine.BreakoutFrame
import com.taskalarm.engine.BreakoutGame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun BreakoutBoard(session: JSONObject, wake: Boolean) {
  val activity = LocalActivity.current as? MainActivity ?: return
  val owner = LocalLifecycleOwner.current
  val id = session.getString("sessionId")
  val challenge = session.getString("challengeId")
  var frame by remember(challenge) { mutableStateOf<BreakoutFrame?>(null) }
  var input by remember(challenge) { mutableStateOf<Float?>(null) }
  var error by remember(challenge) { mutableStateOf<String?>(null) }
  LaunchedEffect(challenge, owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      try {
        while (currentCoroutineContext().isActive) {
          val next = activity.engine.gameFrame(id, challenge, wake, input)
          frame = next
          if (next.complete) break
          delay(33)
        }
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message ?: "Reopen the game to continue"
      }
      finally { activity.engine.pauseGame(id, challenge, wake) }
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    frame?.let { game ->
      Text("${(game.playedMs / 1000)} / ${game.targetMs / 1000} active seconds", style = MaterialTheme.typography.titleMedium)
      LinearProgressIndicator(progress = { game.playedMs.toFloat() / game.targetMs }, modifier = Modifier.fillMaxWidth())
      Text("Score ${game.score}  ·  Best ${game.best}", color = Folderstorm.Gold)
      Text(when {
        game.complete -> "Task complete"
        game.resets > 0 && game.playedMs == 0L -> "Idle reset. Move the paddle to start again."
        game.active -> "Keep moving the paddle. Score does not affect dismissal."
        else -> "Move the paddle to earn time. ${((game.idleLimitMs - game.idleMs).coerceAtLeast(0) + 999) / 1000}s until idle reset."
      }, style = MaterialTheme.typography.bodySmall)
    }
    BreakoutField(frame, Modifier.fillMaxWidth().height(320.dp).pointerInput(challenge) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        input = down.position.x / size.width.coerceAtLeast(1)
        var pressed = true
        while (pressed) {
          val event = awaitPointerEvent()
          val pointer = event.changes.firstOrNull { it.id == down.id }
          pressed = pointer?.pressed == true
          if (pointer != null && pressed) { input = pointer.position.x / size.width.coerceAtLeast(1); pointer.consume() }
        }
      }
    })
    Text("Drag or tap across the field to move the paddle. Unlimited balls. Recent movement earns time; staying still does not.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
internal fun BreakoutPreview(task: TaskDraft) {
  val frame = remember(task.difficulty) { BreakoutGame(task.json(), JSONObject(), { 0L }, { 0L }).frame() }
  BreakoutField(frame, Modifier.fillMaxWidth().height(150.dp))
}

@Composable
private fun BreakoutField(frame: BreakoutFrame?, modifier: Modifier) {
  Canvas(modifier) {
    drawRoundRect(Folderstorm.Inset, cornerRadius = CornerRadius(12.dp.toPx()))
    if (frame == null) return@Canvas
    for ((index, brick) in frame.bricks.withIndex()) drawRoundRect(
      if (index % 2 == 0) Folderstorm.Lavender else Folderstorm.Gold,
      Offset(brick.x * size.width, brick.y * size.height), Size(brick.width * size.width, brick.height * size.height), CornerRadius(3.dp.toPx()))
    drawRoundRect(Folderstorm.PaleLavender, Offset((frame.paddle - frame.paddleWidth / 2) * size.width, .92f * size.height),
      Size(frame.paddleWidth * size.width, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
    drawCircle(Folderstorm.Text, 6.dp.toPx(), Offset(frame.ballX * size.width, frame.ballY * size.height))
  }
}
