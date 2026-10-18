package com.taskalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskalarm.engine.AlarmCopies
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DuplicateAlarmDialog(activity: MainActivity, source: AlarmDraft, busy: Boolean,
  onClose: () -> Unit, onCreate: (Int, Int, Long) -> Unit) {
  var count by rememberSaveable(source.id) { mutableStateOf("5") }
  var interval by rememberSaveable(source.id) { mutableStateOf("5") }
  val startAt = remember(source.id) { runCatching { activity.engine.preview(source.json().toString()) } }
  val plan = remember(source, count, interval, startAt) { runCatching {
    AlarmCopies.create(source, count.toIntOrNull() ?: 0, interval.toIntOrNull() ?: 0, startAt.getOrThrow())
  } }
  AlertDialog(onDismissRequest = { if(!busy) onClose() }, title = { Text("Duplicate alarms") }, text = {
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(source.label, style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(count, { count = it.filter(Char::isDigit).take(2) }, label = { Text("Additional copies") },
        supportingText = { Text("1–50; the original stays as it is") }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
      OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(4) }, label = { Text("Every how many minutes?") },
        supportingText = { Text("Keep the series within 24 hours") }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
      InfoButton("Duplicating alarms", "Copies keep the original’s tasks, sounds, repeat days, and wake-up rules. Each is an independent alarm. Their first rings follow the original’s next scheduled occurrence. Times and repeat days roll forward across midnight. Intervals follow local clock time; daylight-saving changes can alter elapsed spacing. Copies are enabled or disabled like the original.")
      if(!source.enabled) Body("These copies will be disabled, like the original.")
      plan.getOrNull()?.let { copies ->
        Body("First occurrence for each copy:")
        copies.take(6).forEach { copy ->
          val at = activity.engine.preview(copy.json().toString())
          Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)))
        }
        if(copies.size > 6) Body("…and ${copies.size - 6} more")
      }
      plan.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = {
    TextButton(enabled = !busy && plan.isSuccess, onClick = { onCreate(count.toInt(), interval.toInt(), startAt.getOrThrow()) }) {
      Text("Create ${plan.getOrNull()?.size ?: ""} alarms")
    }
  }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } })
}
