package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
internal fun MovingAlarmActions(sessionId: String, showDismiss: Boolean, enabled: Boolean,
  showSnooze: Boolean, snoozeEnabled: Boolean, snoozeLabel: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
  var x by rememberSaveable(sessionId) { mutableFloatStateOf(Random.nextFloat()) }
  var y by rememberSaveable(sessionId) { mutableFloatStateOf(Random.nextFloat()) }
  val owner = LocalLifecycleOwner.current
  val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  LaunchedEffect(sessionId, owner, showDismiss) {
    if (!showDismiss) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(5000)
        // A continuous position across the viewport, with a noticeable move each time.
        var nextX: Float
        var nextY: Float
        do {
          nextX = Random.nextFloat(); nextY = Random.nextFloat()
        } while ((nextX - x) * (nextX - x) + (nextY - y) * (nextY - y) < .0625f)
        x = nextX; y = nextY
      }
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
    val dismissWidth = minOf(112.dp * scale, maxWidth)
    val dismissHeight = minOf(48.dp * scale, ((maxHeight - 8.dp).coerceAtLeast(0.dp)) * .57f)
    val snoozeWidth = minOf(80.dp * scale, dismissWidth * .72f)
    val snoozeHeight = minOf(36.dp * scale, dismissHeight * .75f)
    val horizontalTravel = (maxWidth - dismissWidth).coerceAtLeast(0.dp)
    val verticalTravel = (maxHeight - dismissHeight).coerceAtLeast(0.dp)
    val left = horizontalTravel * x
    var top = verticalTravel * y
    // Leave a gap around the fixed Snooze target, even after font/window changes.
    if (showSnooze && left + dismissWidth + 8.dp > maxWidth - snoozeWidth &&
      top + dismissHeight + 8.dp > maxHeight - snoozeHeight) {
      top = (maxHeight - snoozeHeight - 8.dp - dismissHeight).coerceAtLeast(0.dp)
    }
    if (showDismiss) Button(onDismiss, enabled = enabled, modifier = Modifier
      .offset { IntOffset(left.roundToPx(), top.roundToPx()) }
      .size(dismissWidth, dismissHeight), contentPadding = PaddingValues(horizontal = 8.dp)) {
      Text("Dismiss", maxLines = 1)
    }
    if (showSnooze) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
      OutlinedButton(onSnooze, enabled = snoozeEnabled, modifier = Modifier.align(Alignment.BottomEnd)
        .size(snoozeWidth, snoozeHeight).semantics { contentDescription = snoozeLabel },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        Text("Snooze", style = MaterialTheme.typography.labelSmall, maxLines = 1)
      }
    }
  }
}
