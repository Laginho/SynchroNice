package synchronice.android

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QrCameraPreview(modifier: Modifier = Modifier, onQr: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(cameraProviderFuture) {
        cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
    }
    DisposableEffect(cameraProvider) {
        val provider = cameraProvider
        onDispose { provider?.unbindAll() }
    }
    DisposableEffect(cameraExecutor) {
        onDispose { cameraExecutor.shutdownNow() }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
        },
        update = { previewView ->
            val provider = cameraProvider ?: return@AndroidView
            if (previewView.tag != null) return@AndroidView

            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, QrAnalyzer(onQr, Handler(Looper.getMainLooper())))
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            previewView.tag = analysis
        },
    )
}

private class QrAnalyzer(
    private val onQr: (String) -> Boolean,
    private val mainHandler: Handler,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var accepted = false

    override fun analyze(image: ImageProxy) {
        try {
            if (accepted) return
            val content = decode(image) ?: return
            val handled = booleanArrayOf(false)
            val resultReady = CountDownLatch(1)
            mainHandler.post {
                try {
                    handled[0] = onQr(content)
                } finally {
                    resultReady.countDown()
                }
            }
            if (resultReady.await(2, TimeUnit.SECONDS) && handled[0]) accepted = true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val buffer = plane.buffer.duplicate()
        val start = buffer.position()
        val luminance = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                luminance[row * width + column] = buffer.get(start + row * plane.rowStride + column * plane.pixelStride)
            }
        }

        return decodeLuminance(reader, luminance, width, height)
    }
}

// Costura para teste de JVM: decodifica o quadro de luminância já extraído.
internal fun decodeLuminance(reader: MultiFormatReader, luminance: ByteArray, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    // Uma tentativa só: o detector do ZXing acha os padrões de localização em qualquer orientação,
    // e PlanarYUVLuminanceSource não suporta rotação (lançava UnsupportedOperationException).
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
