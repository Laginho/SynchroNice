package synchronice.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasteTrayTest {
    private val app get() = RuntimeEnvironment.getApplication() as SynchroNiceApplication

    private fun provider(name: String?, mime: String = "image/png"): Uri {
        val authority = "paste.test"
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val source = File(app.cacheDir, "paste-source").apply { writeText("conteudo") }
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun getType(uri: Uri) = mime
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }.apply { attachInfo(app, ProviderInfo().apply { this.authority = authority }) })
        return Uri.parse("content://$authority/item")
    }

    @Test
    fun pastingCopiesEachItemImmediatelyWithoutSending() {
        val uri = provider("foto.png")
        val state = app.state
        assertTrue(state.pasteUri(uri))
        assertTrue(state.pasteUri(uri))
        assertEquals(2, state.pasteTray.size)
        assertEquals(listOf("conteudo", "conteudo"), state.pasteTray.map { it.readText() })
        assertTrue(state.pasteTray[0] != state.pasteTray[1])
        assertFalse(state.hasPending())
        assertTrue(state.messages.isEmpty())
    }
}
