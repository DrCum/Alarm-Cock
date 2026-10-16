package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun MemoryBoard(session: JSONObject, busy: Boolean, onSubmit: (String) -> Unit) {
  val challenge = session.getString("challengeId")
  val owner = LocalLifecycleOwner.current
  val sequence = session.getJSONArray("memorySequence")
  var showing by remember(challenge) { mutableStateOf(true) }
  var highlighted by remember(challenge) { mutableIntStateOf(0) }
  var selected by remember(challenge) { mutableStateOf("") }
  var replay by remember(challenge) { mutableIntStateOf(0) }
  LaunchedEffect(challenge, replay, owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      try {
        showing = true; selected = ""; highlighted = 0
        delay(600)
        for (index in 0 until sequence.length()) {
          highlighted = sequence.getInt(index); delay(450)
          highlighted = 0; delay(220)
        }
        showing = false
        awaitCancellation()
      } finally { highlighted = 0; showing = true }
    }
  }
  val colors = listOf(Color(0xff574075), Color(0xff724866), Color(0xff3f635c), Color(0xff785a2d))
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(if (showing) "Watch the ${sequence.length()} flashes" else "Your turn · ${selected.length} / ${sequence.length()}", style = MaterialTheme.typography.titleMedium)
    for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      for (column in 0..1) {
        val number = row * 2 + column + 1
        Button(onClick = {
          val next = selected + number
          selected = next
          if (next.length == sequence.length()) { onSubmit(next); selected = "" }
        }, enabled = !showing && !busy, modifier = Modifier.weight(1f).height(76.dp),
          colors = ButtonDefaults.buttonColors(containerColor = colors[number - 1], contentColor = Folderstorm.Text,
            disabledContainerColor = if (highlighted == number) Folderstorm.Gold else Folderstorm.Inset,
            disabledContentColor = if (highlighted == number) Folderstorm.Background else Folderstorm.Muted)) {
          Text(number.toString(), style = MaterialTheme.typography.headlineMedium)
        }
      }
    }
    OutlinedButton(onClick = { replay++ }, enabled = !showing && !busy) { Text("Show sequence again") }
    Text("Watch the numbered tiles, then tap them in the same order. You can replay the sequence.", style = MaterialTheme.typography.bodySmall)
  }
}
