package com.taskalarm.engine

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.BootAlarmActivity
import com.taskalarm.app.MainActivity
import com.taskalarm.app.TaskDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootRecoveryTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private var time = 0L
  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    context.createDeviceProtectedStorageContext().getSharedPreferences("task_alarm_boot_v1", 0).edit().clear().commit()
    time = System.currentTimeMillis(); engine = AlarmEngine(context) { time }; store = AlarmStore(context)
    clearTimers(); drainServices()
  }
  private fun lock() { shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false) }
  private fun unlock() { shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true) }
  private fun clearTimers() {
    val manager = context.getSystemService(AlarmManager::class.java)
    shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let(manager::cancel) }
  }
  private fun drainServices() { while(shadowOf(context).nextStartedService != null) { } }
  private fun due(alarm: AlarmDraft = AlarmDraft(days = emptySet()), age: Long = 1000): AlarmDraft {
    engine.save(alarm.json().toString())
    store.put(store.find(alarm.id)!!.put("nextAt", time - age))
    return alarm
  }
  private class NoCredentialReads(base: Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
      error("Credential-protected preferences must not be opened before unlock: $name")
  }
  @Test fun mirrorContainsOnlyRequiredScheduleDataAndNoPrivateTaskOrAudioValues() {
    val alarm = AlarmDraft(dismissTasks = listOf(TaskDraft(kind = "barcode", barcodeMode = "specific", barcode = "private-barcode-value")),
      soundUri = "content://private-audio/path")
    engine.save(alarm.json().toString())
    val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("task_alarm_boot_v1", 0)
    val snapshot = prefs.getString("snapshot", "")!!
    assertTrue(snapshot.contains(alarm.id)); assertTrue(snapshot.contains("nextAt"))
    assertFalse(snapshot.contains("private-barcode-value")); assertFalse(snapshot.contains("private-audio"))
    assertFalse(snapshot.contains("dismissTasks")); assertFalse(snapshot.contains("answer")); assertFalse(snapshot.contains(alarm.label))
    assertEquals("private-barcode-value", AlarmDraft.from(store.find(alarm.id)!!).dismissTasks.single().barcode)
  }
  @Test fun lockedBootRestoresExactAlarmsWithoutReadingCredentialsOrStartingMediaFromBoot() {
    val alarm = AlarmDraft(); engine.save(alarm.json().toString())
    val expected = store.find(alarm.id)!!.getLong("nextAt")
    clearTimers(); lock()
    BootRecovery(NoCredentialReads(context)) { time }.restoreLocked(Intent.ACTION_LOCKED_BOOT_COMPLETED)
    val timers = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(1, timers.size); assertEquals(expected, timers.single().triggerAtTime)
    assertNull(shadowOf(context).nextStartedService)
  }
  @Test fun theLockedAlarmReceiverStartsOnlyBootAudioAndLeavesCredentialTaskStateUntouched() {
    val alarm = due()
    lock()
    AlarmReceiver().onReceive(NoCredentialReads(context), Intent().setData(Uri.parse("taskalarm://alarm/${alarm.id}")))
    val service = shadowOf(context).nextStartedService
    assertNotNull(service); assertEquals(BootRingingService::class.java.name, service.component!!.className)
    assertTrue(BootRecovery(context).wasTriggered(alarm.id))
    unlock(); assertTrue(store.sessions().isEmpty())
    assertTrue(store.find(alarm.id)!!.getBoolean("enabled"))
  }
  @Test fun unlockingHandsOffARecordedBootAlarmEvenAfterTheCatchUpWindow() {
    val alarm = due(AlarmDraft(days = emptySet(), dismissTasks = listOf(TaskDraft(kind = "text", rounds = 2))))
    lock(); BootRecovery(NoCredentialReads(context)) { time }.fireLocked(alarm.id)
    drainServices(); time += BootRecovery.CATCH_UP_MS + 1000; unlock()
    engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertEquals(1, store.sessions().size)
    assertEquals(alarm.dismissTasks, AlarmDraft.from(store.sessions().single().getJSONObject("alarm")).dismissTasks)
    assertNull(shadowOf(context).nextStartedService) // Media is resumed by an exact alarm, not the boot receiver.
    assertFalse(BootRecovery(context).wasTriggered(alarm.id))
    RingRecoveryReceiver().onReceive(context, Intent())
    assertEquals(RingingService::class.java.name, shadowOf(context).nextStartedService.component!!.className)
    engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertEquals(1, store.sessions().size)
  }
  @Test fun recentOverdueAlarmsAreQueuedOnceRatherThanMovedToTomorrow() {
    val alarm = due(age = 3 * 60 * 60_000L)
    engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertEquals(alarm.id, store.sessions().single().getJSONObject("alarm").getString("id"))
    assertFalse(store.find(alarm.id)!!.getBoolean("enabled"))
    assertNull(shadowOf(context).nextStartedService)
    engine.restore("app_open")
    assertEquals(1, store.sessions().size)
  }
  @Test fun olderMissedOccurrencesAreLoggedAndRescheduledWithoutAnUnexpectedOldRing() {
    val alarm = due(AlarmDraft(days = (1..7).toSet()), BootRecovery.CATCH_UP_MS + 1000)
    engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertTrue(store.sessions().isEmpty())
    assertTrue(store.find(alarm.id)!!.getLong("nextAt") > time)
    assertEquals(1, BootRecovery(context).summary().getJSONObject("lastRestore").getInt("skipped"))
    assertTrue(BootRecovery(context).summary().getJSONArray("events").toString().contains("missed_too_old"))
  }
  @Test fun anAppOrPackageRestartKeepsFutureOccurrencesAndRepairsMissingPlatformTimers() {
    val alarm = AlarmDraft(); engine.save(alarm.json().toString())
    val expected = store.find(alarm.id)!!.getLong("nextAt")
    clearTimers(); engine = AlarmEngine(context) { time }; engine.restore(Intent.ACTION_MY_PACKAGE_REPLACED)
    assertEquals(expected, store.find(alarm.id)!!.getLong("nextAt"))
    assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
  }
  @Test fun recoveryResumesPartialProgressAndClearsForegroundOnlyMute() {
    val alarm = due(AlarmDraft(days = emptySet(), allowMuteWhileSolving = true, dismissTasks = listOf(TaskDraft(rounds = 3))))
    engine.fire(alarm.id)
    val id = store.sessions().single().getString("sessionId"); engine.begin(id, "dismiss")
    var session = store.sessions().single()
    engine.submit(id, session.getString("challengeId"), session.getString("answer")); engine.setMuted(id, true)
    session = store.sessions().single(); drainServices()
    engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertNull(shadowOf(context).nextStartedService)
    RingRecoveryReceiver().onReceive(context, Intent())
    val after = store.sessions().single()
    assertEquals(session.getString("challengeId"), after.getString("challengeId")); assertEquals(2, after.getInt("round"))
    assertFalse(after.getBoolean("muted"))
  }
  @Test fun wakeDeadlineBeforeUnlockTriggersBootAudioWithoutReadingTaskContents() {
    val alarm = due(AlarmDraft(days = emptySet(), dismissTasks = emptyList(), wakeEnabled = true))
    engine.fire(alarm.id); engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    val check = store.checks().single(); drainServices(); lock()
    time = check.getLong("dueAt")
    BootRecovery(NoCredentialReads(context)) { time }.wakeLocked(check.getString("sessionId"), "due")
    assertNull(shadowOf(context).nextStartedService)
    time = check.getLong("deadlineAt")
    BootRecovery(NoCredentialReads(context)) { time }.wakeLocked(check.getString("sessionId"), "deadline")
    assertEquals(BootRingingService::class.java.name, shadowOf(context).nextStartedService.component!!.className)
    unlock(); assertEquals(check.getString("sessionId"), store.checks().single().getString("sessionId"))
  }
  @Test fun theTwoMinuteRestartTestUsesAnIndependentAlarmAndCanBeRecovered() {
    engine.testDelivery(120)
    val test = store.alarms().single()
    assertEquals(time + 120_000, test.getLong("nextAt"))
    lock(); time += 120_000
    BootRecovery(NoCredentialReads(context)) { time }.fireLocked(test.getString("id"))
    unlock(); engine.restore(Intent.ACTION_BOOT_COMPLETED)
    assertTrue(store.alarms().isEmpty()); assertTrue(store.sessions().single().getBoolean("test"))
    engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
    assertTrue(store.sessions().isEmpty()); assertTrue(store.checks().isEmpty())
  }
  @Test fun manifestAllowsOnlyTheMinimalPreUnlockComponentsToRunInDirectBoot() {
    val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
    for(type in listOf(AlarmReceiver::class.java, WakeCheckReceiver::class.java, RestoreReceiver::class.java, RingRecoveryReceiver::class.java)) {
      assertTrue(context.packageManager.getReceiverInfo(ComponentName(context, type), flags).directBootAware)
    }
    assertTrue(context.packageManager.getServiceInfo(ComponentName(context, BootRingingService::class.java), flags).directBootAware)
    assertTrue(context.packageManager.getActivityInfo(ComponentName(context, BootAlarmActivity::class.java), flags).directBootAware)
    assertFalse(context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), flags).directBootAware)
    assertFalse(context.packageManager.getServiceInfo(ComponentName(context, RingingService::class.java), flags).directBootAware)
  }
}
