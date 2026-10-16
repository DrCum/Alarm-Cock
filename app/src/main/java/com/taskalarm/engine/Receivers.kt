package com.taskalarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try { intent.data?.lastPathSegment?.let { AlarmEngine(context).fire(it) } }
    catch (error: Exception) { Log.e("TaskAlarm", "Unable to deliver alarm", error) }
  }
}

class RestoreReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try { AlarmEngine(context).restore(intent.action) }
    catch (error: Exception) { Log.e("TaskAlarm", "Unable to restore alarms", error) }
  }
}

class WakeCheckReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val path = intent.data?.pathSegments ?: return
    if (path.size != 2) return
    try { AlarmEngine(context).wakeEvent(path[0], path[1]) }
    catch (error: Exception) { Log.e("TaskAlarm", "Unable to handle wake-up check", error) }
  }
}
