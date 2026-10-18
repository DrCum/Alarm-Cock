package com.taskalarm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun InfoButton(title: String, text: String) {
  var open by rememberSaveable(title) { mutableStateOf(false) }
  TextButton(onClick={open=true},modifier=Modifier.semantics { contentDescription="$title information" }) { Text("Info") }
  if(open) AlertDialog(onDismissRequest={open=false},title={Text(title)},text={
    Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) { Text(text) }
  },confirmButton={TextButton(onClick={open=false}) {Text("Close")}})
}

@Composable
internal fun EditorSection(title: String, summary: String = "", initiallyOpen: Boolean = false, info: String? = null, content: @Composable ColumnScope.()->Unit) {
  var expanded by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
  CardBlock {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
      Column(Modifier.weight(1f).heightIn(min=48.dp).semantics { stateDescription=if(expanded) "Expanded" else "Collapsed" }
        .clickable(role=Role.Button,onClickLabel=if(expanded) "Collapse $title" else "Expand $title") {expanded=!expanded}.padding(vertical=8.dp)) {
        Text("${if(expanded) "▾" else "▸"} $title",style=MaterialTheme.typography.titleMedium)
        if(!expanded && summary.isNotBlank()) Text(summary,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
      }
      if(info!=null) InfoButton(title,info)
    }
    if(expanded) content()
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorActions(status: String, busy: Boolean, canRevert: Boolean, onDone: ()->Unit, onDiscard: ()->Unit, onRevert: ()->Unit) {
  Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
      Text(status,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
      InfoButton("Saving & recovery","Valid changes save automatically after a short pause. Incomplete fields keep the last saved version. Done saves the latest valid changes and returns to your alarms. Exit without saving restores the settings from when this visit began; a new alarm is removed. Revert restores this visit’s opening settings, or the previous edit if you have just reopened the page. An alarm that already rang or changed elsewhere is protected from rollback.")
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      Button(enabled=!busy,onClick=onDone) {Text("Done")}
      OutlinedButton(enabled=!busy && canRevert,onClick=onRevert) {Text("Revert")}
      TextButton(enabled=!busy,onClick=onDiscard) {Text("Exit without saving")}
    }
  }
}
