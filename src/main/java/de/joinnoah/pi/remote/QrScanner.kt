package de.joinnoah.pi.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the scanner can do with the camera. */
internal enum class CameraAccess {
    /** Granted and running: the preview shows. */
    READY,

    /** Not granted yet; Android still shows its prompt. */
    ASK,

    /** Denied for good: Android answers no without a prompt, so only the app's settings can grant it. */
    SETTINGS,

    /** Granted, but the camera did not start. */
    ERROR,
}

/**
 * [deniedBefore] says a request was refused earlier. Android shows no rationale before the first
 * request either, so only together they mean that it stopped asking.
 */
internal fun cameraAccess(granted: Boolean, cameraError: Boolean, deniedBefore: Boolean, rationale: Boolean): CameraAccess =
    when {
        granted && cameraError -> CameraAccess.ERROR
        granted -> CameraAccess.READY
        deniedBefore && !rationale -> CameraAccess.SETTINGS
        else -> CameraAccess.ASK
    }

/** Opens this app's page in the system settings, where a permission denied for good is granted. */
internal fun appDetailsSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/** Whether the camera request was refused before, across scanner openings and app runs. */
internal object CameraDenials {
    private const val PREFERENCES = "qr_scanner"
    private const val DENIED_KEY = "camera_denied"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun deniedBefore(context: Context): Boolean = preferences(context).getBoolean(DENIED_KEY, false)

    fun record(context: Context, granted: Boolean) = preferences(context).edit { putBoolean(DENIED_KEY, !granted) }
}

@Composable
fun QrScanner(onCode: (String) -> Unit, onClose: () -> Unit, onPaste: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current
    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun showsRationale() =
        activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false
    var granted by remember { mutableStateOf(cameraGranted()) }
    var deniedBefore by remember { mutableStateOf(CameraDenials.deniedBefore(context)) }
    var rationale by remember { mutableStateOf(showsRationale()) }
    var cameraError by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            deniedBefore = !it
            rationale = showsRationale()
            CameraDenials.record(context, it)
        }
    // Back from the app's settings, where the camera may have been allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = cameraGranted()
        rationale = showsRationale()
        if (granted && deniedBefore) {
            deniedBefore = false
            CameraDenials.record(context, true)
        }
    }
    val access = cameraAccess(granted, cameraError, deniedBefore, rationale)
    val latestCode by rememberUpdatedState(onCode)
    val preview = remember { PreviewView(context) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.remote_scan),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(
                        when (access) {
                            CameraAccess.READY -> R.string.remote_camera_aim
                            CameraAccess.ASK -> R.string.remote_camera_permission
                            CameraAccess.SETTINGS -> R.string.remote_camera_settings
                            CameraAccess.ERROR -> R.string.remote_camera_error
                        }
                    ),
                    Modifier.testTag("cameraMessage"),
                )
                if (access == CameraAccess.READY)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.Center)
                                .fillMaxWidth(0.72f)
                                .aspectRatio(1f)
                                .border(3.dp, Color.White, MaterialTheme.shapes.large)
                        )
                    }
                else
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (access == CameraAccess.ERROR) Icons.Default.NoPhotography else Icons.Default.CameraAlt,
                            null,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            stringResource(
                                if (access == CameraAccess.ERROR) R.string.remote_camera_error_title
                                else R.string.remote_design_camera_permission_title
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // A camera that failed to start is no question of permission: only Paste helps.
                        if (access != CameraAccess.ERROR) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (access == CameraAccess.SETTINGS)
                                        // Some OEM builds have no per-app settings screen.
                                        runCatching { context.startActivity(appDetailsSettingsIntent(context.packageName)) }
                                    else permission.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.testTag("allowCamera"),
                            ) {
                                Icon(Icons.Default.QrCodeScanner, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.remote_camera_allow))
                            }
                        }
                    }
                TextButton(onClick = onPaste) { Text(stringResource(R.string.remote_paste)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.remote_cancel)) }
            }
        }
    }
    if (granted)
        DisposableEffect(preview, lifecycle) {
            val active = AtomicBoolean(true)
            val delivered = AtomicBoolean(false)
            val worker = Executors.newSingleThreadExecutor()
            val future = ProcessCameraProvider.getInstance(context)
            val analysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            val cameraPreview =
                Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val reader =
                MultiFormatReader().apply {
                    setHints(
                        mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                    )
                }
            analysis.setAnalyzer(worker) { image ->
                try {
                    if (!active.get() || delivered.get()) return@setAnalyzer
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val base = buffer.position()
                    val bytes = ByteArray(image.width * image.height)
                    for (row in 0 until image.height) for (col in 0 until image.width) bytes[
                        row * image.width + col] =
                        buffer.get(base + row * plane.rowStride + col * plane.pixelStride)
                    val source =
                        PlanarYUVLuminanceSource(
                            bytes,
                            image.width,
                            image.height,
                            0,
                            0,
                            image.width,
                            image.height,
                            false,
                        )
                    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                    if (delivered.compareAndSet(false, true))
                        ContextCompat.getMainExecutor(context).execute {
                            if (active.get()) latestCode(result.text)
                        }
                } catch (_: Exception) {} finally {
                    reader.reset()
                    image.close()
                }
            }
            future.addListener(
                {
                    if (active.get())
                        try {
                            future
                                .get()
                                .bindToLifecycle(
                                    lifecycle,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    cameraPreview,
                                    analysis,
                                )
                        } catch (_: Exception) {
                            cameraError = true
                        }
                },
                ContextCompat.getMainExecutor(context),
            )
            onDispose {
                active.set(false)
                analysis.clearAnalyzer()
                if (future.isDone) runCatching { future.get().unbind(cameraPreview, analysis) }
                worker.shutdownNow()
            }
        }
}
