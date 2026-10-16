package com.taskalarm.engine

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.AlarmTone
import com.taskalarm.app.TaskDraft
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EscalationServiceTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private lateinit var audio: AudioManager
  private var wall = System.currentTimeMillis()
  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    engine = AlarmEngine(context) { wall }; store = AlarmStore(context)
    audio = context.getSystemService(AudioManager::class.java)
    audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
    for (id in BuiltinSounds.names.keys) register(Uri.parse(BuiltinSounds.prepare(context, id)))
    register(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
  }
  // Keep the stub track longer than the simulated window: short loop events in
  // Robolectric can spin at their boundary. Android looping itself is checked below.
  private fun register(uri: Uri) = ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(600_000, 0))
  private fun idle(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
  private fun player(service: RingingService) = RingingService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(service) as MediaPlayer
  private fun alarm() = AlarmDraft(escalationEnabled = true, escalationAfterMinutes = 1)
  private fun volume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)
  private fun source(service: RingingService) = shadowOf(player(service)).sourceUri.toString()

  @Test fun unattendedAlarmRaisesVolumeThenRotatesExactlyEveryFortyFiveSeconds() {
    engine.test(alarm().json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create(); val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1)
      idle(59_999); assertEquals(2, volume()); assertFalse(store.sessions().single().optBoolean("escalated"))
      idle(1); assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), volume())
      assertEquals(BuiltinSounds.prepare(context, "beacon"), source(service))
      assertTrue(player(service).isLooping)
      val first = player(service)
      idle(44_999); assertSame(first, player(service))
      idle(1); assertEquals(BuiltinSounds.prepare(context, "pulse"), source(service)); assertNotSame(first, player(service))
      idle(45_000); assertEquals(BuiltinSounds.prepare(context, "chime"), source(service))
      idle(45_000); assertEquals(BuiltinSounds.prepare(context, "beacon"), source(service))
    } finally { controller.destroy() }
    assertEquals(2, volume()); idle(90_000); assertEquals(2, volume())
  }
  @Test fun choosingTasksBeforeTheTimeoutPreventsUnansweredEscalation() {
    engine.test(alarm().json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create(); val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1); idle(30_000)
      engine.begin(store.sessions().single().getString("sessionId"), "dismiss")
      service.onStartCommand(null, 0, 2); idle(90_000)
      assertEquals(2, volume()); assertFalse(store.sessions().single().optBoolean("escalated"))
    } finally { controller.destroy() }
  }
  @Test fun muteAndRepeatedServiceStartsDoNotRestartThePlaylistOrFinishTasks() {
    engine.testEscalation(alarm().copy(allowMuteWhileSolving = true, dismissTasks = listOf(TaskDraft())).json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create(); val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1); idle(44_000)
      val id = store.sessions().single().getString("sessionId")
      engine.begin(id, "dismiss"); engine.setMuted(id, true)
      val before = player(service); service.onStartCommand(null, 0, 2)
      assertSame(before, player(service)); assertEquals(0f, shadowOf(player(service)).leftVolume, 0f)
      idle(1000)
      assertEquals(BuiltinSounds.prepare(context, "pulse"), source(service))
      assertEquals(0f, shadowOf(player(service)).leftVolume, 0f)
      assertEquals(1, store.sessions().size)
      engine.setMuted(id, false); service.onStartCommand(null, 0, 3)
      assertEquals(1f, shadowOf(player(service)).leftVolume, 0f)
    } finally { controller.destroy() }
    assertEquals(2, volume())
  }
  @Test fun serviceRecreationRetainsTheCurrentToneAndRestoresTheOriginalVolume() {
    engine.testEscalation(alarm().json().toString())
    val first = Robolectric.buildService(RingingService::class.java).create()
    first.get().onStartCommand(null, 0, 1); idle(65_000); first.destroy()
    assertEquals(2, volume())
    val second = Robolectric.buildService(RingingService::class.java).create(); val service = second.get()
    try {
      service.onStartCommand(null, 0, 2); idle(0)
      assertEquals(BuiltinSounds.prepare(context, "pulse"), source(service))
      assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), volume())
      idle(25_000); assertEquals(BuiltinSounds.prepare(context, "chime"), source(service))
    } finally { second.destroy() }
    assertEquals(2, volume())
  }
  @Test fun anUnavailableToneFallsBackForItsSlotAndTheNextToneStillPlays() {
    val missing = Uri.parse("file:///missing-tone.wav")
    ShadowMediaPlayer.addException(DataSource.toDataSource(context, missing), IOException("Missing tone"))
    engine.testEscalation(alarm().copy(escalationTones = listOf(AlarmTone(missing.toString(), "Missing"), AlarmTone("builtin:beacon", "Beacon"))).json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create(); val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1); idle(0)
      assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString(), source(service))
      assertTrue(store.sessions().single().getBoolean("soundFallback"))
      idle(45_000); assertEquals(BuiltinSounds.prepare(context, "beacon"), source(service))
      assertFalse(store.sessions().single().getBoolean("soundFallback"))
    } finally { controller.destroy() }
  }
  @Test fun aQueuedAlarmCapturesTheRestoredVolumeInsteadOfInheritingMaximum() {
    engine.testEscalation(alarm().copy(dismissTasks = emptyList()).json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create(); val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1); idle(0)
      assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), volume())
      val next = AlarmDraft(days = emptySet())
      engine.save(next.json().toString()); wall = store.find(next.id)!!.getLong("nextAt"); engine.fire(next.id)
      assertEquals(2, store.sessions().size)
      engine.begin(store.sessions().first().getString("sessionId"), "dismiss")
      service.onStartCommand(null, 0, 2); idle(0)
      assertEquals(2, volume()); assertEquals(2, store.sessions().single().getInt("originalVolume"))
      assertFalse(store.sessions().single().optBoolean("escalated"))
    } finally { controller.destroy() }
    assertEquals(2, volume())
  }
}
