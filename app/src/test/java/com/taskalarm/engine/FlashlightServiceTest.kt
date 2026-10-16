package com.taskalarm.engine

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Vibrator
import com.taskalarm.app.AlarmDraft
import com.taskalarm.app.TaskDraft
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FlashlightServiceTest {
  @Test fun serviceKeepsLightOnWhenMutedPausesForScannerAndReleasesItWhenStopped() {
    val context: Application = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1", 0).edit().clear().commit()
    shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH, true)
    val cameras = shadowOf(context.getSystemService(CameraManager::class.java))
    val back = ShadowCameraCharacteristics.newCameraCharacteristics()
    shadowOf(back).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
    shadowOf(back).set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
    cameras.addCamera("back", back)
    val engine = AlarmEngine(context)
    val store = AlarmStore(context)
    val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
    vibrator.setHasVibrator(true)
    engine.test(AlarmDraft(flashlightMode = "steady", vibrate = true, allowMuteWhileSolving = true, dismissTasks = listOf(TaskDraft())).json().toString())
    val controller = Robolectric.buildService(RingingService::class.java).create()
    val service = controller.get()
    try {
      service.onStartCommand(null, 0, 1)
      assertTrue(cameras.getTorchMode("back"))
      assertTrue(vibrator.isVibrating)
      val id = store.sessions().single().getString("sessionId")
      engine.begin(id, "dismiss")
      engine.setMuted(id, true)
      service.onStartCommand(null, 0, 2)
      assertTrue(cameras.getTorchMode("back"))
      assertTrue(vibrator.isVibrating)
      service.onStartCommand(Intent().putExtra("scannerActive", true), 0, 3)
      assertFalse(cameras.getTorchMode("back"))
      service.onStartCommand(Intent().putExtra("scannerActive", false), 0, 4)
      assertTrue(cameras.getTorchMode("back"))
    } finally { controller.destroy() }
    assertFalse(cameras.getTorchMode("back"))
    assertFalse(vibrator.isVibrating)
  }
  @Test fun lightTaskPausesTorchAndFallbackResumesItWithoutStoppingAlarm() {
    val context: Application=RuntimeEnvironment.getApplication()
    context.getSharedPreferences("task_alarm_v1",0).edit().clear().commit()
    shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_FLASH,true)
    val cameras=shadowOf(context.getSystemService(CameraManager::class.java))
    val back=ShadowCameraCharacteristics.newCameraCharacteristics()
    shadowOf(back).set(CameraCharacteristics.FLASH_INFO_AVAILABLE,true)
    shadowOf(back).set(CameraCharacteristics.LENS_FACING,CameraCharacteristics.LENS_FACING_BACK)
    cameras.addCamera("back",back)
    val engine=AlarmEngine(context); val store=AlarmStore(context)
    engine.test(AlarmDraft(flashlightMode="steady",dismissTasks=listOf(TaskDraft(kind="light"))).json().toString())
    val controller=Robolectric.buildService(RingingService::class.java).create(); val service=controller.get()
    try {
      service.onStartCommand(null,0,1); assertTrue(cameras.getTorchMode("back"))
      val id=store.sessions().single().getString("sessionId"); engine.begin(id,"dismiss")
      service.onStartCommand(null,0,2); assertFalse(cameras.getTorchMode("back"))
      val challenge=store.sessions().single().getString("challengeId")
      engine.startNative(id,challenge,false); engine.nativeFrame(id,challenge,false)
      engine.nativeFallback(id,challenge,false)
      service.onStartCommand(null,0,3); assertTrue(cameras.getTorchMode("back"))
      assertEquals("math",store.sessions().single().getString("kind"))
    } finally {controller.destroy()}
    assertFalse(cameras.getTorchMode("back"))
  }

}
