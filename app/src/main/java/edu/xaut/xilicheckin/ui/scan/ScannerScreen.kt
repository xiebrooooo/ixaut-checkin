package edu.xaut.xilicheckin.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机扫二维码：CameraX 取帧 → ML Kit 条码识别（bundled 模型，自带 .so，不依赖 Google Play 服务）。
 *
 * 为什么是 ML Kit 而不是 ZXing：后排拍投影时码又小又糊又低对比，ML Kit 内部会做多尺度搜索，
 * 比 ZXing 的单一二值化稳得多。
 *
 * 三条对付「坐后排」的路子：
 *   1. 双指缩放 / 顶部变焦条 —— 变焦同样作用于分析帧，码会被放大后再送进识别器；
 *   2. 切换摄像头 —— 能枚举到长焦就切长焦（按焦距自动标注「长焦 / 超广角」）；
 *   3. 从相册选图 —— 用系统相机（可用潜望长焦）拍好再回来识别。
 */
@Composable
fun ScannerScreen(
    onDecoded: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    var tip by remember { mutableStateOf<String?>(null) }

    // ---- 相册里选一张 → 解码 ----
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            tip = "正在识别相册图片…"
            QrImageDecoder.decodeUri(ctx, uri) { text ->
                if (text.isNullOrBlank()) {
                    tip = "这张图里没找到二维码"
                } else {
                    onDecoded(text)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(onDecoded = onDecoded)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "需要相机权限才能扫码",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                FilledTonalButton(
                    onClick = { ask.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("去授权") }
            }
        }

        // 取景框
        Box(
            Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tip ?: "把二维码放进取景框；扫不上就双指放大或拖上面那根变焦条",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }.onFailure { tip = "打不开相册：${it.message}" }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("从相册选图", color = Color.White) }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }
    }
}

/** 把 CameraInfo 按焦距分类，方便用户认出哪颗是长焦 */
private data class CamChoice(val id: String, val facing: Int, val label: String, val focal: Float)

private fun describeCameras(provider: ProcessCameraProvider): List<CamChoice> {
    val list = mutableListOf<CamChoice>()
    for (info in provider.availableCameraInfos) {
        val c2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull() ?: continue
        val id = c2.cameraId
        val facing = runCatching {
            c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        }.getOrNull() ?: CameraCharacteristics.LENS_FACING_BACK
        val focals = runCatching {
            c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        }.getOrNull() ?: floatArrayOf(0f)
        val focal = focals.maxOrNull() ?: 0f
        val label = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"
        list += CamChoice(id, facing, label, focal)
    }
    // 后置之间按焦距分类：最长的那颗就是长焦
    val backs = list.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    if (backs.size > 1) {
        val minFocal = backs.minOf { it.focal }
        backs.forEach { b ->
            val idx = list.indexOf(b)
            val tag = when {
                b.focal >= minFocal * 2.5f -> "长焦"
                b.focal <= minFocal * 0.8f -> "超广角"
                else -> "主摄"
            }
            list[idx] = b.copy(label = "后置·$tag")
        }
    }
    // 默认落在一进就能用的那颗：主摄 → 长焦 → 超广角 → 前置
    val order = listOf("后置", "后置·主摄", "后置·长焦", "后置·超广角", "前置")
    return list.sortedBy { c -> order.indexOf(c.label).let { if (it < 0) order.size else it } }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cams by remember { mutableStateOf<List<CamChoice>>(emptyList()) }
    var camIdx by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var zoomMin by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(Modifier.fillMaxSize()) {

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 双指缩放（透明层盖在预览上吃手势）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(zoomMin, zoomMax) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(zoomMin, zoomMax)
                    }
                }
        )

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val p = future.get()
                provider = p
                cams = describeCameras(p)
                camIdx = 0
            }, ContextCompat.getMainExecutor(ctx))
        }

        // 绑定 / 切换摄像头
        LaunchedEffect(cams, camIdx) {
            val p = provider ?: return@LaunchedEffect
            val pick = cams.getOrNull(camIdx)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { text ->
                if (handled.compareAndSet(false, true)) onDecoded(text)
            })

            val selector = if (pick == null) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.Builder()
                    .requireLensFacing(pick.facing)
                    .addCameraFilter { infos ->
                        val hit = infos.filter {
                            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == pick.id
                        }
                        hit.ifEmpty { infos }
                    }
                    .build()
            }

            runCatching {
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera = cam
                val zs = cam.cameraInfo.zoomState.value
                zoomMin = zs?.minZoomRatio ?: 1f
                zoomMax = zs?.maxZoomRatio ?: 1f
                zoom = 1f.coerceIn(zoomMin, zoomMax)
                cam.cameraControl.setZoomRatio(zoom)
            }.onFailure {
                // 个别机型允许枚举但拒绝打开某颗镜头，回退默认后置
                runCatching {
                    p.unbindAll()
                    val cam = p.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                    camera = cam
                    val zs = cam.cameraInfo.zoomState.value
                    zoomMin = zs?.minZoomRatio ?: 1f
                    zoomMax = zs?.maxZoomRatio ?: 1f
                    zoom = 1f.coerceIn(zoomMin, zoomMax)
                }
            }
        }

        LaunchedEffect(zoom) {
            runCatching { camera?.cameraControl?.setZoomRatio(zoom) }
        }

        // 变焦条 + 摄像头切换
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 64.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${"%.1f".format(zoom)}×",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (zoomMax > zoomMin) {
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = zoomMin..zoomMax,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (cams.size > 1) {
                    OutlinedButton(onClick = { camIdx = (camIdx + 1) % cams.size }) {
                        Text(cams.getOrNull(camIdx)?.label ?: "摄像头", color = Color.White)
                    }
                }
            }
        }
    }
}

/** 相机帧 → ML Kit。识别到第一个 QR 就回调。 */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let(onText)
            }
            .addOnCompleteListener { image.close() }
    }
}

/** 从一张静止图片里抠二维码（相册选图）。同样走 ML Kit。 */
private object QrImageDecoder {

    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    private const val MAX_SIDE = 2600

    fun decodeUri(ctx: Context, uri: Uri, onResult: (String?) -> Unit) {
        val bmp = runCatching { rotateByExif(ctx, uri, loadScaled(ctx, uri)) }.getOrNull()
        if (bmp == null) {
            onResult(null)
            return
        }
        scanner.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { codes ->
                onResult(codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= MAX_SIDE) s *= 2
        return s
    }

    private fun loadScaled(ctx: Context, uri: Uri): Bitmap? {
        ctx.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            val bytes = input.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    private fun rotateByExif(ctx: Context, uri: Uri, bmp: Bitmap?): Bitmap? {
        if (bmp == null) return null
        val deg = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
