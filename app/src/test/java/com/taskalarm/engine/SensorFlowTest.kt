package com.taskalarm.engine

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SensorFlowTest {
  private lateinit var context: Application
  private lateinit var engine: AlarmEngine
  private lateinit var store: AlarmStore
  private lateinit var manager: SensorManager
  @Before fun setup() {
    context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("task_alarm_v1",0).edit().clear().commit()
    engine=AlarmEngine(context); store=AlarmStore(context); manager=context.getSystemService(SensorManager::class.java)
  }
  private fun sensor(type: Int) = ShadowSensor.newInstance(type).also { shadowOf(manager).addSensor(it) }
  private fun emit(sensor: Sensor, vararg values: Float) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    val event=SensorEventBuilder.newBuilder().setSensor(sensor).setTimestamp(SystemClock.elapsedRealtimeNanos()).setValues(values).build()
    shadowOf(manager).sendSensorEventToListeners(event,sensor)
  }
  private fun start(task: TaskDraft, following: List<TaskDraft> = emptyList()): Pair<String,String> {
    engine.test(AlarmDraft(dismissTasks=listOf(task)+following).json().toString())
    val id=store.sessions().single().getString("sessionId"); engine.begin(id,"dismiss")
    val challenge=store.sessions().single().getString("challengeId"); engine.startNative(id,challenge,false)
    return id to challenge
  }
  @Test fun hardwareStepsAdvanceOnlyTheCurrentRoundAndPreserveFollowingTasks() {
    val detector=sensor(Sensor.TYPE_STEP_DETECTOR)
    val (id,challenge)=start(TaskDraft(kind="walk",stepTarget=5,rounds=2),listOf(TaskDraft(kind="math")))
    repeat(4) {emit(detector,1f)}
    assertEquals(4,engine.nativeFrame(id,challenge,false).steps)
    assertEquals(1,store.sessions().single().getInt("round"))
    emit(detector,1f); assertTrue(engine.nativeFrame(id,challenge,false).complete)
    assertTrue(shadowOf(manager).listeners.isEmpty())
    val next=store.sessions().single().getString("challengeId"); assertNotEquals(challenge,next)
    assertThrows(IllegalStateException::class.java) {engine.nativeFrame(id,challenge,false)}
    engine.startNative(id,next,false); repeat(5) {emit(detector,1f)}; engine.nativeFrame(id,next,false)
    assertEquals("math",store.sessions().single().getString("kind")); assertEquals(1,store.sessions().single().getInt("taskIndex"))
    assertTrue(shadowOf(manager).listeners.isEmpty())
  }
  @Test fun stoppingFlushesStepsAndResumingDoesNotCountBackgroundEvents() {
    val detector=sensor(Sensor.TYPE_STEP_DETECTOR)
    val (id,challenge)=start(TaskDraft(kind="walk",stepTarget=20))
    repeat(3) {emit(detector,1f)}
    engine.stopNative(challenge); assertTrue(shadowOf(manager).listeners.isEmpty())
    repeat(4) {emit(detector,1f)}
    val recreated=AlarmEngine(context); recreated.startNative(id,challenge,false)
    assertEquals(3,recreated.nativeFrame(id,challenge,false).steps)
    emit(detector,1f); assertEquals(4,recreated.nativeFrame(id,challenge,false).steps)
    recreated.stopNative(challenge)
  }
  @Test fun steadyLightNeedsFreshReadingsAndFinishesThroughNativeValidation() {
    val light=sensor(Sensor.TYPE_LIGHT)
    val (id,challenge)=start(TaskDraft(kind="light",lightLux=200,lightHoldSeconds=2))
    emit(light,300f); engine.nativeFrame(id,challenge,false)
    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
    assertEquals(0,engine.nativeFrame(id,challenge,false).heldMs.toInt())
    assertEquals(1,store.sessions().size)
    repeat(22) {emit(light,300f)}
    assertTrue(engine.nativeFrame(id,challenge,false).complete)
    assertTrue(store.sessions().isEmpty()); assertTrue(shadowOf(manager).listeners.isEmpty())
  }
  @Test fun staleTiltReadingsStopMovingTheMazeBall() {
    val gravity=sensor(Sensor.TYPE_GRAVITY)
    val (id,challenge)=start(TaskDraft(kind="maze",controlMode="tilt"))
    emit(gravity,-4f,0f,9.8f); engine.nativeFrame(id,challenge,false)
    ShadowSystemClock.advanceBy(Duration.ofMillis(33)); val moving=engine.nativeFrame(id,challenge,false).maze!!
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); val paused=engine.nativeFrame(id,challenge,false).maze!!
    repeat(20) {ShadowSystemClock.advanceBy(Duration.ofMillis(33)); engine.nativeFrame(id,challenge,false)}
    val after=engine.nativeFrame(id,challenge,false).maze!!
    assertEquals(paused.x,after.x,0f); assertEquals(paused.y,after.y,0f)
    assertTrue(moving.x > .14f)
    engine.stopNative(challenge)
  }
  @Test fun unsupportedWakeTaskOffersFallbackWithoutChangingItsDeadline() {
    val alarm=AlarmDraft(wakeEnabled=true,wakeMode="tasks",wakeTasks=listOf(TaskDraft(kind="walk")))
    engine.testWake(alarm.json().toString())
    val check=store.checks().single(); val id=check.getString("sessionId"); val challenge=check.getString("challengeId"); val deadline=check.getLong("deadlineAt")
    engine.startNative(id,challenge,true); assertNotNull(engine.nativeFrame(id,challenge,true).unavailable)
    engine.nativeFallback(id,challenge,true)
    val fallback=store.checks().single(); assertEquals("math",fallback.getString("kind")); assertEquals(deadline,fallback.getLong("deadlineAt"))
    assertTrue(engine.submitWake(id,fallback.getString("challengeId"),fallback.getString("answer")))
    assertTrue(store.checks().isEmpty())
  }
}
