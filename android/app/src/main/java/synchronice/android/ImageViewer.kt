package synchronice.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun decodePreview(source: ImageDecoder.Source): Bitmap? = try {
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // ponytail: previews cap the longest edge at 2048px; tile decoding if full-resolution zoom is needed.
        val ratio = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageViewer(message: ChatMessage, onClose: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(message.localId) { mutableStateOf(message.preview) }
    var loading by remember(message.localId) { mutableStateOf(message.preview == null && message.uri != null) }
    var scale by remember(message.localId) { mutableStateOf(1f) }
    var offset by remember(message.localId) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(message.localId) {
        message.uri?.let { uri ->
            bitmap = withContext(Dispatchers.IO) {
                decodePreview(ImageDecoder.createSource(context.contentResolver, uri))
            }
        }
        loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.semantics { testTagsAsResourceId = true }.testTag("image-viewer").fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            bitmap?.let { image ->
                Image(
                    image.asImageBitmap(), contentDescription = message.text,
                    modifier = Modifier.fillMaxSize().pointerInput(message.localId) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 8f)
                            val relative = centroid - Offset(size.width / 2f, size.height / 2f)
                            val moved = relative - (relative - offset) * (nextScale / scale) + pan
                            val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                            val limitX = maxOf(0f, (image.width * fit * nextScale - size.width) / 2f)
                            val limitY = maxOf(0f, (image.height * fit * nextScale - size.height) / 2f)
                            offset = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                            scale = nextScale
                        }
                    }.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            } ?: Text(if (loading) "Carregando…" else "Não foi possível abrir a imagem.", color = Color.White)
            TextButton(onClick = onClose, modifier = Modifier.testTag("image-viewer-back").align(Alignment.TopStart).padding(16.dp)) {
                Text("Voltar", color = Color.White)
            }
        }
    }
}
