package synchronice.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SynchroNice", text))
}

@Composable
internal fun CopyableText(text: String, copyFormatted: Boolean) {
    val context = LocalContext.current
    val trechos = remember(text) { segmentarTexto(text) }
    val annotated = remember(trechos) {
        buildAnnotatedString {
            trechos.forEachIndexed { index, trecho ->
                val start = length
                append(trecho.text)
                if (trecho.type != "text") {
                    addStringAnnotation("trecho", index.toString(), start, length)
                    addStyle(SpanStyle(
                        color = Color(0xFF1B4FA8),
                        textDecoration = if (trecho.type == "link") TextDecoration.Underline else TextDecoration.None,
                    ), start, length)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun trechoAt(position: Offset): Trecho? {
        val result = layout ?: return null
        if (annotated.isEmpty()) return null
        val offset = result.getOffsetForPosition(position)
        if (offset >= annotated.length || !result.getBoundingBox(offset).contains(position)) return null
        return annotated.getStringAnnotations("trecho", offset, offset).firstOrNull()?.let { trechos[it.item.toInt()] }
    }
    fun activate(trecho: Trecho?) {
        if (trecho?.type == "link") {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trecho.href)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Nenhum navegador disponível.", Toast.LENGTH_SHORT).show()
            }
        } else {
            copyText(context, (if (copyFormatted) trecho?.copyFormatted else trecho?.copyPlain) ?: text)
        }
    }
    Text(
        annotated,
        onTextLayout = { layout = it },
        modifier = Modifier
            .pointerInput(annotated, copyFormatted) {
                detectTapGestures(
                    onTap = { activate(trechoAt(it)) },
                    onLongPress = { position ->
                        val trecho = trechoAt(position)
                        if (trecho?.type == "link") copyText(context, trecho.text) else activate(trecho)
                    },
                )
            }
            .semantics {
                onClick("Copiar texto inteiro") { copyText(context, text); true }
                customActions = trechos.filter { it.type != "text" }.flatMap { trecho ->
                    if (trecho.type == "link") listOf(
                        CustomAccessibilityAction("Abrir ${trecho.text}") { activate(trecho); true },
                        CustomAccessibilityAction("Copiar ${trecho.text}") { copyText(context, trecho.text); true },
                    ) else listOf(CustomAccessibilityAction("Copiar ${trecho.text}") { activate(trecho); true })
                }
            },
    )
}
