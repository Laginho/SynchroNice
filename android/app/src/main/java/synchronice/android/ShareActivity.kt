package synchronice.android

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Recebe o Compartilhar sem abrir o chat; a URI só é usada enquanto a Activity vive. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = (application as SynchroNiceApplication).state
        val incoming = intent
        lifecycleScope.launch {
            try {
                if (state.pairing != null) {
                    sharedUris(incoming).forEach { uri ->
                        val staging = withContext(Dispatchers.IO) { copyToStaging(uri) }
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
                    android.widget.Toast.makeText(this@ShareActivity, "Abra o SynchroNice para parear com o PC.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                android.util.Log.e("SynchroNice", "Compartilhar falhou", error)
                android.widget.Toast.makeText(this@ShareActivity, "Não foi possível compartilhar este item.", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun sharedUris(incoming: Intent): List<Uri> = when (incoming.action) {
        Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }.ifEmpty {
        incoming.clipData?.let { clips -> (0 until clips.itemCount).mapNotNull { clips.getItemAt(it).uri } } ?: emptyList()
    }.filter(::isForeignContentUri)

    /**
     * Recusa file:// e content:// do próprio app: nem as preferências, nem a fila, nem o
     * FileProvider da câmera. O ContentResolver do AOSP endereça um provider por
     * "<userId>@authority" e tira o prefixo antes de resolver, então a busca pelo provider
     * também precisa tirar -- senão um Intent malicioso disfarça a authority própria com um
     * prefixo e o filtro deixa passar.
     */
    @Suppress("DEPRECATION")
    private fun isForeignContentUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority?.substringAfterLast('@') ?: return false
        return packageManager.resolveContentProvider(authority, 0)?.packageName != packageName
    }

    private fun copyToStaging(uri: Uri): File {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "arquivo"
        val candidate = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }
        val safeName = candidate.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "arquivo"
        val directory = File(cacheDir, "incoming/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, safeName)
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("URI sem conteúdo")
            input.use { source -> file.outputStream().use { destination -> source.copyTo(destination) } }
            return file
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }
}
