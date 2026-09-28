package synchronice.android

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Recebe o Compartilhar sem abrir o chat; a URI só é usada enquanto a Activity vive. O trabalho
 * fica no [ShareModel], que sobrevive a girar a tela: a Activity recriada chega com o mesmo
 * Intent, e refazer o lote enfileiraria de novo, com ids novos, o que já tinha entrado.
 */
class ShareActivity : ComponentActivity() {
    private val share: ShareModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        share.start(application as SynchroNiceApplication, intent)
        lifecycleScope.launch {
            share.done.await()
            finish()
        }
    }
}

internal class ShareModel : ViewModel() {
    val done = CompletableDeferred<Unit>()
    private var started = false

    fun start(application: SynchroNiceApplication, incoming: Intent) {
        if (started) return
        started = true
        val state = application.state
        viewModelScope.launch {
            try {
                if (state.pairing != null) {
                    sharedUris(application, incoming).forEach { uri ->
                        val staging = withContext(Dispatchers.IO) { copyToStaging(application, uri) }
                        try {
                            state.queueFile(staging)
                        } finally {
                            staging.parentFile?.deleteRecursively()
                        }
                    }
                    incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
                        val id = state.addOutgoing(text)
                        state.queueText(id, text)
                    }
                } else {
                    Toast.makeText(application, "Abra o SynchroNice para parear com o PC.", Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("SynchroNice", "Compartilhar falhou", error)
                Toast.makeText(application, "Não foi possível compartilhar este item.", Toast.LENGTH_LONG).show()
            } finally {
                done.complete(Unit)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun sharedUris(context: Context, incoming: Intent): List<Uri> = when (incoming.action) {
    Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
    else -> emptyList()
}.ifEmpty {
    incoming.clipData?.let { clips -> (0 until clips.itemCount).mapNotNull { clips.getItemAt(it).uri } } ?: emptyList()
}.filter { isForeignContentUri(context, it) }

/**
 * Recusa file:// e content:// do próprio app: nem as preferências, nem a fila, nem o
 * FileProvider da câmera. O ContentResolver do AOSP endereça um provider por
 * "<userId>@authority" e tira o prefixo antes de resolver, então a busca pelo provider
 * também precisa tirar -- senão um Intent malicioso disfarça a authority própria com um
 * prefixo e o filtro deixa passar.
 */
@Suppress("DEPRECATION")
private fun isForeignContentUri(context: Context, uri: Uri): Boolean {
    if (uri.scheme != "content") return false
    val authority = uri.authority?.substringAfterLast('@') ?: return false
    return context.packageManager.resolveContentProvider(authority, 0)?.packageName != context.packageName
}

private fun copyToStaging(context: Context, uri: Uri): File {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "arquivo"
    val directory = File(context.cacheDir, "incoming/${UUID.randomUUID()}").apply { mkdirs() }
    val file = File(directory, safeFileName(name))
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("URI sem conteúdo")
        input.use { source -> file.outputStream().use { destination -> source.copyTo(destination) } }
        return file
    } catch (error: Exception) {
        directory.deleteRecursively()
        throw error
    }
}
