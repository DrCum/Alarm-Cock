package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray

@Composable
internal fun TaskQueueStatus(pending: JSONArray?) {
  var open by remember { mutableStateOf(false) }
  if(pending == null || pending.length() == 0) return
  val next = pending.getJSONObject(0)
  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text("${pending.length()} waiting · Your current task stays here", style = MaterialTheme.typography.labelMedium)
        Text("Next: ${next.getString("label")}", style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
      }
      TextButton(onClick = { open = true }) { Text("Queue") }
    }
  }
  if(open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Up next") }, text = {
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Body("Finish the current task first. Every queued alarm and task remains required. A waiting wake-up check gets its response time when it reaches the front; its deadline continues while active, including in the background.")
      for(index in 0 until pending.length()) {
        val item = pending.getJSONObject(index)
        Text("${index + 1}. ${if(item.getString("kind") == "wake") "Wake-up check" else "Alarm"} · ${item.getString("label")}")
      }
    }
  }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
