package com.taskalarm.app

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.taskalarm.engine.AlarmEngine
import com.taskalarm.engine.AlarmStore
import com.taskalarm.engine.SoundProcessor
import com.taskalarm.engine.WakeChecks
import com.taskalarm.engine.RingingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  internal val engine by lazy { AlarmEngine(this) }
  private var soundCallback: ((String, String) -> Unit)? = null
  private var soundError: ((String) -> Unit)? = null
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) importSound(uri)
  }
  private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == RESULT_OK) {
      @Suppress("DEPRECATION")
      val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
      uri?.let { soundCallback?.invoke(it.toString(), RingtoneManager.getRingtone(this, it)?.getTitle(this) ?: "Phone alarm sound") }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    volumeControlStream = AudioManager.STREAM_ALARM
    updateAlarmWindow()
    setContent { TaskAlarmApp(this) }
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); updateAlarmWindow() }
  override fun onResume() {
    super.onResume(); updateAlarmWindow()
    if (AlarmStore(this).sessions().isNotEmpty()) engine.ring()
  }
  override fun onStop() {
    super.onStop()
    // Intentional silence is scoped to solving in the foreground, not leaving an alarm muted in the background.
    val session = synchronized(AlarmEngine.LOCK) { AlarmStore(this).sessions().firstOrNull() }
    if (session?.optBoolean("muted") == true) {
      try { engine.setMuted(session.getString("sessionId"), false) } catch (_: RuntimeException) { }
    }
  }
  internal fun updateAlarmWindow() {
    val store = AlarmStore(this)
    val active = store.sessions().isNotEmpty() || store.checks().any { it.optString("stage") == "waiting" }
    if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(active); setTurnScreenOn(active) }
    else {
      @Suppress("DEPRECATION")
      val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
      if (active) window.addFlags(flags) else window.clearFlags(flags)
    }
    if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
  private fun guardVolumeKey(keyCode: Int): Boolean = keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE) &&
    synchronized(AlarmEngine.LOCK) { AlarmStore(this).sessions().firstOrNull()?.getJSONObject("alarm")?.optBoolean("protectVolume") == true }
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = if (guardVolumeKey(keyCode)) true else super.onKeyDown(keyCode, event)
  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (guardVolumeKey(keyCode)) true else super.onKeyUp(keyCode, event)
  internal fun openAccess(kind: String) {
    if (kind == "activity" && Build.VERSION.SDK_INT >= 29) { activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION); return }
    if (kind == "camera") { cameraPermission.launch(Manifest.permission.CAMERA); return }
    if (kind in listOf("notifications", "wakeNotifications") && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return
    }
    val intent = when (kind) {
      "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      "appSettings" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
      "exact" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) else return
      "fullScreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) else return
      "wakeNotifications" -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, WakeChecks.CHANNEL)
      "notifications" -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, RingingService.CHANNEL)
      else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    if (kind in listOf("exact", "fullScreen")) intent.data = Uri.parse("package:$packageName")
    startActivity(intent)
  }
  internal fun setCameraActive(active: Boolean) {
    if (synchronized(AlarmEngine.LOCK) { AlarmStore(this).sessions().isEmpty() }) return
    startForegroundService(Intent(this, RingingService::class.java).putExtra("scannerActive", active))
  }
  internal fun chooseSound(custom: Boolean, onSound: (String, String) -> Unit, onError: (String) -> Unit) {
    soundCallback = onSound; soundError = onError
    try {
      if (custom) documentPicker.launch(arrayOf("audio/*"))
      else ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false))
    } catch (error: Exception) { onError(error.message ?: "No sound picker is available") }
  }
  private fun importSound(uri: Uri) {
    lifecycleScope.launch {
      try {
        val result = withContext(Dispatchers.IO) {
          try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
          var name = "Custom sound"
          contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
          SoundProcessor.prepare(this@MainActivity, uri.toString(), false) to name
        }
        soundCallback?.invoke(result.first, result.second)
      } catch (error: Exception) { soundError?.invoke(error.message ?: "Could not import sound") }
    }
  }
}
