package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
internal fun MovingDismissButton(sessionId: String, enabled: Boolean, onClick: () -> Unit) {
  var position by remember(sessionId) { mutableIntStateOf(Random.nextInt(9)) }
  LaunchedEffect(sessionId) {
    while (true) {
      delay(5000)
      val next = Random.nextInt(8)
      position = if (next >= position) next + 1 else next
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Find Dismiss to start your tasks. It moves every five seconds.", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxWidth().height(156.dp)) {
      Button(onClick, enabled = enabled, modifier = Modifier
        .offset(x = (maxWidth - 112.dp).coerceAtLeast(0.dp) * (position % 3 / 2f), y = 104.dp * (position / 3 / 2f))
        .width(112.dp).heightIn(min = 48.dp)) { Text("Dismiss") }
    }
  }
}
