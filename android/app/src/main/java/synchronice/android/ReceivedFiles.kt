package synchronice.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import synchronice.protocolclient.Item
import synchronice.protocolclient.ProtocolClient
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

internal class FileGoneException : IOException("o arquivo não existe mais")

private val receiptMarker = Regex(""" \[syn-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}]""")

internal fun fileMimeType(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

/** Só o último segmento, sem caracteres de controle; vazio, "." ou ".." viram "arquivo". */
internal fun safeFileName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "arquivo"

internal class ReceivedFiles(context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun folder(): Uri? = preferences.getString("receive-folder", null)?.let(Uri::parse)

    fun savePhoto(file: java.io.File) {
        val tree = folder()
        val uri = if (tree == null) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SynchroNice")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, "image/jpeg", file.name)
        } ?: throw IOException("não foi possível criar a foto na Pasta")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("não foi possível abrir a Pasta")
            output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
            publish(uri)
        } catch (error: Exception) {
            try {
                if (tree == null) resolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(resolver, uri)
            } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun chooseFolder(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!preferences.edit().putString("receive-folder", uri.toString()).commit()) {
            throw IOException("não foi possível guardar a Pasta")
        }
    }

    fun receive(client: ProtocolClient, item: Item.FileMeta, existingUri: Uri?, creationIntent: String?, onCreating: (String) -> Unit, onCreated: (Uri) -> Unit, onRenaming: (Uri) -> Unit, onSaved: (Uri) -> Unit, onProgress: (Int) -> Unit) {
        val tree = folder()
        val mime = fileMimeType(item.name)
        val name = safeFileName(item.name)
        // O recibo identifica o destino pela URI, sem ambiguidade: se ele sumiu (o usuário apagou o
        // parcial, o MediaStore expirou a linha pendente), recomeçar num novo não duplica nada.
        val resumed = existingUri?.takeIf(::exists)
        val uri = resumed ?: if (tree == null && creationIntent == null) {
            // MediaStore allocates a unique filename atomically, including for repeated names.
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SynchroNice")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        } else {
            val intent = creationIntent?.let(::JSONObject) ?: JSONObject().apply {
                put("tree", tree.toString())
                put("marker", "[syn-${UUID.randomUUID()}]")
            }
            val targetTree = Uri.parse(intent.getString("tree"))
            if (creationIntent == null) {
                onCreating(intent.toString())
                val parent = DocumentsContract.buildDocumentUriUsingTree(targetTree, DocumentsContract.getTreeDocumentId(targetTree))
                val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                val markedName = "${name.take(dot)} ${intent.getString("marker")}${name.substring(dot)}"
                DocumentsContract.createDocument(resolver, parent, mime, markedName)
                    ?: throw IOException("não foi possível criar o arquivo na Pasta")
            } else recoverCreatedDocument(targetTree, intent.getString("marker"))
        } ?: throw IOException("não foi possível criar o arquivo na Pasta")

        var tracked = resumed != null || tree != null || creationIntent != null
        try {
            onCreated(uri)
            // Keep interrupted destinations so a restart writes to the same file, including SAF.
            tracked = true
            val complete = {
                val saved = unmark(uri, name, onRenaming)
                onSaved(saved)
                publish(saved)
            }
            if (resumed != null && hasCompleteContent(uri, item.size)) {
                complete()
                onProgress(100)
                client.acknowledgeFile(item.id)
            } else {
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("não foi possível abrir o destino")
                client.downloadFile(item, output, onSaved = complete, onProgress = onProgress)
            }
        } catch (error: Exception) {
            if (!tracked) {
                try {
                    val removed = if (tree == null) resolver.delete(uri, null, null) > 0
                        else DocumentsContract.deleteDocument(resolver, uri)
                    if (!removed) throw IOException("não foi possível remover o arquivo incompleto")
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    /**
     * O marcador só identifica o documento SAF enquanto ele está incompleto (critério 3); com o
     * conteúdo completo, o nome volta a ser o do PC e o provedor resolve colisão como `nome (1)`.
     * [onRenaming] guarda o recibo antes do rename: um provedor de ids por caminho troca a URI, e a
     * retomada ([finishRenaming]) precisa saber que o conteúdo já estava completo.
     */
    private fun unmark(uri: Uri, name: String, onRenaming: (Uri) -> Unit): Uri {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        val current = displayName(uri) ?: return uri
        if (!receiptMarker.containsMatchIn(current)) return uri
        onRenaming(uri)
        return rename(uri, name)
    }

    /** Retoma um [unmark] interrompido; `null` se o documento antigo sumiu, ou seja, o rename já aconteceu. */
    fun finishRenaming(uri: Uri, name: String): Uri? {
        val current = displayName(uri) ?: return null
        if (!receiptMarker.containsMatchIn(current)) return uri
        return rename(uri, safeFileName(name))
    }

    private fun rename(uri: Uri, name: String): Uri = try {
        DocumentsContract.renameDocument(resolver, uri, name) ?: uri
    } catch (_: UnsupportedOperationException) {
        uri // Provedor sem rename: o arquivo está completo, só o nome fica com o marcador.
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun exists(uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: FileNotFoundException) {
        false
    }

    private fun recoverCreatedDocument(tree: Uri, marker: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val candidates = mutableListOf<String>()
        (resolver.query(children, columns, null, null, null)
            ?: throw IOException("não foi possível consultar o recebimento na Pasta; tente novamente")).use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
                throw IOException("a Pasta ainda está carregando; tente novamente")
            }
            while (cursor.moveToNext()) {
                if (cursor.getString(1)?.contains(marker) == true) candidates.add(cursor.getString(0))
            }
        }
        val documentId = candidates.singleOrNull()
            ?: throw IOException("não foi possível identificar o arquivo recebido na Pasta; escolha a Pasta de novo em Mudar pasta… para recomeçar")
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }

    private fun hasCompleteContent(uri: Uri, size: Long): Boolean {
        (resolver.openInputStream(uri) ?: throw IOException("não foi possível ler o destino")).use { input ->
            val buffer = ByteArray(8192)
            var received = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) return received == size
                received += count
                if (received > size) return false
            }
        }
    }

    fun publish(uri: Uri) {
        if (uri.authority != MediaStore.AUTHORITY) return
        val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        if (updated == 0) throw FileGoneException()
        if (updated != 1) throw IOException("não foi possível publicar o arquivo")
    }
}
