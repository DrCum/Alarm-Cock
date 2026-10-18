package com.taskalarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try { intent.data?.lastPathSegment?.let {
      if(BootRecovery.unlocked(context)) AlarmEngine(context).fire(it) else BootRecovery(context).fireLocked(it)
    } }
    catch (error: Exception) {
      BootRecovery(context).event("alarm_error", error.javaClass.simpleName)
      Log.e("TaskAlarm", "Unable to deliver alarm", error)
    }
  }
}

class RestoreReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try {
      if(BootRecovery.unlocked(context)) AlarmEngine(context).restore(intent.action) else BootRecovery(context).restoreLocked(intent.action)
    } catch (error: Exception) {
      BootRecovery(context).event("restore_error", error.javaClass.simpleName)
      Log.e("TaskAlarm", "Unable to restore alarms", error)
    }
  }
}

class WakeCheckReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val path = intent.data?.pathSegments ?: return
    if (path.size != 2) return
    try {
      if(BootRecovery.unlocked(context)) AlarmEngine(context).wakeEvent(path[0], path[1]) else BootRecovery(context).wakeLocked(path[0], path[1])
    } catch (error: Exception) {
      BootRecovery(context).event("wake_error", error.javaClass.simpleName)
      Log.e("TaskAlarm", "Unable to handle wake-up check", error)
    }
  }
}

class RingRecoveryReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try {
      if(BootRecovery.unlocked(context)) AlarmEngine(context).resumeRinging() else BootRecovery(context).resumeLocked()
    } catch(error: Exception) {
      BootRecovery(context).event("resume_error", error.javaClass.simpleName)
      Log.e("TaskAlarm", "Unable to resume ringing", error)
    }
  }
}
