package de.joinnoah.pi.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanner(onCode: (String) -> Unit, onClose: () -> Unit, onPaste: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
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
                        if (granted && !cameraError) R.string.remote_camera_aim
                        else R.string.remote_camera_permission
                    )
                )
                if (granted && !cameraError)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.Center)
                                .fillMaxWidth(0.72f)
                                .aspectRatio(1f)
                                .border(3.dp, Color.White, RoundedCornerShape(24.dp))
                        )
                    }
                else
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(48.dp))
                        Text(
                            stringResource(R.string.remote_design_camera_permission_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                            Icon(Icons.Default.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.remote_camera_allow))
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
