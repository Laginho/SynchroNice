package synchronice.android

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.PendingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PasteTrayTest {
    private val app get() = RuntimeEnvironment.getApplication() as SynchroNiceApplication

    private fun provider(name: String?, mime: String = "image/png", unreadable: Boolean = false): Uri {
        val authority = "paste.test"
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (unreadable) throw FileNotFoundException("permission expired")
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
    fun mixedPasteStagesImageAndLeavesCaptionForTheTextField() {
        val item = ClipData.Item("legenda", null, provider("foto.png"))
        assertFalse(app.state.pasteItem(item))
        assertEquals("legenda", item.text.toString())
        assertEquals("conteudo", app.state.pasteTray.single().readText())
        assertFalse(app.state.hasPending())
    }

    @Test
    fun textOnlyPasteIsLeftForTheTextFieldAndUriOnlyPasteIsConsumed() {
        assertFalse(app.state.pasteItem(ClipData.Item("só texto")))
        assertTrue(app.state.pasteTray.isEmpty())
        assertTrue(app.state.pasteItem(ClipData.Item(provider("foto.png"))))
        assertEquals(1, app.state.pasteTray.size)
    }

    @Test
    fun rejectedOrUnreadableUriStillPreservesItsCaptionWithoutStaging() {
        val own = Uri.parse("content://${app.packageName}.camera/camera/foto.jpg")
        assertFalse(app.state.pasteItem(ClipData.Item("legenda própria", null, own)))
        assertTrue(app.state.pasteItem(ClipData.Item(own)))
        assertFalse(app.state.pasteItem(ClipData.Item("legenda indisponível", null,
            provider("broken.png", unreadable = true))))
        assertTrue(app.state.pasteTray.isEmpty())
        assertFalse(app.state.hasPending())
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

    @Test
    fun sendQueuesFilesInOrderThenTextAndClearsComposer() = sending {
        pasteUri(provider("primeiro.txt", "text/plain"))
        pasteUri(provider("segundo.pdf", "application/pdf"))
        val copies = pasteTray.toList()
        composer.setTextAndPlaceCursorAtEnd("legenda")
        sendComposer()
        assertEquals(listOf("primeiro.txt", "segundo.pdf", "legenda"), messages.map { it.text })
        val pending = FilePendingQueueStore(File(app.filesDir, "pending-items.txt")).load()
        assertEquals(listOf("primeiro.txt", "segundo.pdf"), pending.filterIsInstance<PendingItem.File>().map { it.name })
        assertEquals("legenda", (pending.last() as PendingItem.Text).text)
        assertTrue(pending.filterIsInstance<PendingItem.File>().all { File(it.path).readText() == "conteudo" })
        assertTrue(pasteTray.isEmpty())
        assertEquals("", composer.text.toString())
        assertTrue(copies.all { !it.exists() })
    }

    @Test
    fun sendWithoutTextQueuesOnlyTheTray() = sending {
        pasteUri(provider("arquivo.txt", "text/plain"))
        sendComposer()
        assertEquals(listOf("arquivo.txt"), messages.map { it.text })
        assertTrue(pasteTray.isEmpty())
    }

    @Test
    fun textOnlyAndEmptySendPreserveExistingBehavior() = sending {
        sendComposer()
        assertFalse(hasPending())
        composer.setTextAndPlaceCursorAtEnd("só texto")
        sendComposer()
        assertEquals(listOf("só texto"), messages.map { it.text })
        assertEquals("", composer.text.toString())
    }

    @Test
    fun removeAndClearDeleteOnlyTheirStagedCopies() {
        val state = app.state
        val uri = provider("foto.png")
        state.pasteUri(uri)
        state.pasteUri(uri)
        val copies = state.pasteTray.toList()
        state.removePaste(copies[0])
        assertFalse(copies[0].exists())
        assertTrue(copies[1].exists())
        assertEquals(listOf(copies[1]), state.pasteTray)
        state.clearPasteTray()
        assertFalse(copies[1].exists())
        assertTrue(state.pasteTray.isEmpty())
        assertTrue(File(app.cacheDir, "paste-source").exists())
    }

    @Test
    fun ownProviderIncludingUserPrefixAndFileUrisAreRejected() {
        val state = app.state
        listOf("content://${app.packageName}.camera/camera/foto.jpg",
            "content://0@${app.packageName}.camera/camera/foto.jpg", "file:///secret.txt").forEach {
            assertFalse(state.pasteUri(Uri.parse(it)))
        }
        assertTrue(state.pasteTray.isEmpty())
        assertFalse(state.hasPending())
    }

    @Test
    fun missingDisplayNameUsesTimestampAndMimeExtension() {
        val state = app.state
        state.pasteUri(provider(null, "image/gif"))
        assertTrue(Regex("imagem-[0-9]+\\.gif").matches(state.pasteTray.single().name))
    }

    @Test
    fun failedReadLeavesNoTrayEntryOrPartialStaging() {
        assertFailsWith<FileNotFoundException> { app.state.pasteUri(provider("broken.png", unreadable = true)) }
        assertTrue(app.state.pasteTray.isEmpty())
        assertTrue(File(app.cacheDir, "incoming").listFiles().orEmpty().isEmpty())
    }

    private fun sending(block: suspend AppState.() -> Unit) = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef"}""")
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { app.state.block() } finally { Dispatchers.resetMain() }
    }
}
