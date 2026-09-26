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
import java.io.IOException

internal fun fileMimeType(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

internal class ReceivedFiles(context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun folder(): Uri? = preferences.getString("receive-folder", null)?.let(Uri::parse)

    fun chooseFolder(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!preferences.edit().putString("receive-folder", uri.toString()).commit()) {
            throw IOException("não foi possível guardar a Pasta")
        }
    }

    fun receive(client: ProtocolClient, item: Item.FileMeta, onSaved: (Uri) -> Unit, onProgress: (Int) -> Unit) {
        val tree = folder()
        val mime = fileMimeType(item.name)
        val name = item.name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' }.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "arquivo"
        val uri = if (tree == null) {
            // MediaStore allocates a unique filename atomically, including for repeated names.
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SynchroNice")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val names = mutableSetOf<String>()
            (resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?: throw IOException("não foi possível ler a Pasta")).use { cursor ->
                while (cursor.moveToNext()) names.add(cursor.getString(0))
            }
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var unique = name
            var suffix = 1
            while (unique in names) unique = "${name.take(dot)} (${suffix++})${name.substring(dot)}"
            DocumentsContract.createDocument(resolver, parent, mime, unique)
        } ?: throw IOException("não foi possível criar o arquivo na Pasta")

        var saved = false
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("não foi possível abrir o destino")
            client.downloadFile(item, output, onSaved = {
                if (tree == null && resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) != 1) throw IOException("não foi possível publicar o arquivo")
                onSaved(uri)
                saved = true
            }, onProgress = onProgress)
        } catch (error: Exception) {
            if (!saved) {
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
}
