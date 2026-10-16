package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
internal fun MovingDismissButton(sessionId: String, enabled: Boolean, onClick: () -> Unit) {
  var position by remember(sessionId) { mutableIntStateOf(Random.nextInt(9)) }
  val targetHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
  val targetWidth = 112.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
  LaunchedEffect(sessionId) {
    while (true) {
      delay(5000)
      val next = Random.nextInt(8)
      position = if (next >= position) next + 1 else next
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Find Dismiss to start your tasks. It moves every five seconds.", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxWidth().height(targetHeight + 104.dp)) {
      val width = minOf(targetWidth, maxWidth)
      val horizontalTravel = (maxWidth - width).coerceAtLeast(0.dp)
      Button(onClick, enabled = enabled, modifier = Modifier
        .offset { IntOffset((horizontalTravel * (position % 3 / 2f)).roundToPx(), (104.dp * (position / 3 / 2f)).roundToPx()) }
        .width(width).height(targetHeight)) { Text("Dismiss") }
    }
  }
}
