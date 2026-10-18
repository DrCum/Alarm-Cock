package com.taskalarm.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.taskalarm.engine.BootRecovery

/** Direct-Boot-safe screen: no credential-protected settings, task values, photos, or routines are read. */
class BootAlarmActivity : ComponentActivity() {
  private var opened = false
  private val unlock = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { openTasksIfUnlocked() }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    ContextCompat.registerReceiver(this, unlock, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
    setContent { TaskAlarmTheme {
      BackHandler { moveTaskToBack(true) }
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Page {
          Text("Unlock to complete your alarm", style = MaterialTheme.typography.headlineLarge)
          Body("Your phone restarted. Alarm sound can play before first unlock; your tasks become available after you unlock with your PIN, pattern, or password.")
          Primary("Unlock phone") {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this@BootAlarmActivity,
              object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { openTasksIfUnlocked() }
              })
          }
        }
      }
    } }
  }
  override fun onResume() { super.onResume(); openTasksIfUnlocked() }
  private fun openTasksIfUnlocked() {
    if(!opened && BootRecovery.unlocked(this)) {
      opened = true
      startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      finish()
    }
  }
  override fun onDestroy() { unregisterReceiver(unlock); super.onDestroy() }
}
