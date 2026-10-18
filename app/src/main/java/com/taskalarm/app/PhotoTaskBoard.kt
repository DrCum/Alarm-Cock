package com.taskalarm.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun PhotoTaskBoard(session: JSONObject? = null, wake: Boolean = false) {
  val activity = LocalActivity.current as? MainActivity ?: return
  val challenge = session?.getString("challengeId")
  var open by remember(challenge) { mutableStateOf(false) }
  var demoCaptured by remember { mutableStateOf(false) }
  Body("Photograph your morning stream or toilet bowl. Keep your body out of frame.")
  InfoButton("Photo privacy", "A fresh in-app camera photo is required for each round. The app checks that capture succeeded, not what the image contains. Images stay in memory, are immediately discarded, and are never saved or uploaded. Screenshots are blocked while the camera is open. Cancelling or leaving the camera does not finish a round.")
  Secondary(if (session == null) "Try camera preview" else "Open camera") { open = true }
  if (session == null && demoCaptured) Body("Preview photo captured and discarded. No alarm was changed.")
  if (open) PhotoCaptureDialog(activity, onStart = {
    if (session == null) UUID.randomUUID().toString()
    else activity.engine.startPhotoCapture(session.getString("sessionId"), challenge!!, wake)
  }, onCaptured = { token ->
    if (session == null) demoCaptured = true else activity.engine.completePhotoCapture(token)
    open = false
  }, onCancelCapture = { activity.engine.cancelPhotoCapture(it) }, onClose = { open = false })
}

@Composable
private fun PhotoCaptureDialog(activity: MainActivity, onStart: () -> String, onCaptured: (String) -> Unit,
  onCancelCapture: (String) -> Unit, onClose: () -> Unit) {
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
    securePolicy = SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false)) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val provider = remember(context) { ProcessCameraProvider.getInstance(context) }
    val previewView = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val closed = remember { AtomicBoolean(false) }
    val currentStart by rememberUpdatedState(onStart)
    val currentCaptured by rememberUpdatedState(onCaptured)
    val currentCancel by rememberUpdatedState(onCancelCapture)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    fun cancelPending() { pending?.let { currentCancel(it) }; pending = null }
    LaunchedEffect(pending) {
      val token = pending ?: return@LaunchedEffect
      delay(30_000)
      if(pending == token) { cancelPending(); error = "Capture timed out. Take a fresh photo." }
    }
    DisposableEffect(owner, view) {
      val window = (view.parent as? DialogWindowProvider)?.window
      @Suppress("DEPRECATION")
      window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          resumed = true
          allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
          activity.setCameraActive(true)
        } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
          resumed = false; cancelPending(); activity.setCameraActive(false)
        }
      }
      owner.lifecycle.addObserver(observer)
      if (resumed) activity.setCameraActive(true)
      onDispose {
        closed.set(true); cancelPending(); owner.lifecycle.removeObserver(observer)
        activity.setCameraActive(false)
      }
    }
    DisposableEffect(allowed, owner, previewView) {
      val disposed = AtomicBoolean(false)
      var boundProvider: ProcessCameraProvider? = null
      var boundPreview: Preview? = null
      var boundCapture: ImageCapture? = null
      if (allowed) provider.addListener({
        if (!disposed.get() && !closed.get()) try {
          val cameraProvider = provider.get()
          check(cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "A rear camera is required for this task." }
          val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
          val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF).build()
          cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, photo)
          boundProvider = cameraProvider; boundPreview = preview; boundCapture = photo
          capture = photo; error = null
        } catch (failure: Exception) { error = failure.message ?: "Camera unavailable. Close and retry." }
      }, main)
      onDispose {
        disposed.set(true); capture = null; cancelPending()
        val useCases = listOfNotNull(boundPreview, boundCapture).toTypedArray()
        if (useCases.isNotEmpty()) boundProvider?.unbind(*useCases)
      }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Morning stream photo", style = MaterialTheme.typography.headlineSmall)
        Body("Keep your body out of frame. Photo discarded immediately; no content recognition.")
        if (allowed) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(280.dp))
        else {
          Body("Camera access is needed. If denied permanently, enable Camera in Android app settings.")
          Secondary("Allow camera") { permission.launch(Manifest.permission.CAMERA) }
          Secondary("Open app settings") { activity.openAccess("appSettings") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Primary(if (pending == null) "Take photo" else "Taking photo…", allowed && resumed && capture != null && pending == null) {
          var token: String? = null
          try {
            val camera = capture ?: kotlin.error("Camera is not ready")
            val request = currentStart()
            token = request; pending = request; error = null
            camera.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
              override fun onCaptureSuccess(image: ImageProxy) {
                val valid = try { runCatching { image.width > 0 && image.height > 0 && image.planes.any { it.buffer.hasRemaining() } }.getOrDefault(false) }
                  finally { image.close() }
                if (closed.get() || pending != request) return
                pending = null
                if (!resumed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !valid) {
                  currentCancel(request); error = "Capture interrupted. Take a fresh photo."; return
                }
                try { currentCaptured(request) }
                catch (failure: Exception) { error = failure.message ?: "Task changed. Close and retry." }
              }
              override fun onError(exception: ImageCaptureException) {
                currentCancel(request)
                if (!closed.get() && pending == request) { pending = null; error = "Photo failed. Close and retry the camera." }
              }
            })
          } catch (failure: Exception) {
            token?.let { currentCancel(it) }; pending = null
            error = failure.message ?: "Could not take photo. Try again."
          }
        }
        Secondary("Cancel photo") { cancelPending(); onClose() }
      }
    }
  }
}
