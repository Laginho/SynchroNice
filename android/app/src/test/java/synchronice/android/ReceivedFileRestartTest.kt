package synchronice.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import synchronice.protocolclient.Item
import synchronice.protocolclient.ProtocolRejectedException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ReceivedFileRestartTest {
    @Test
    fun restartingAfterFailedAckConfirmsSavedFileWithoutAnotherDownload() = exerciseRestart(null)

    @Test
    fun restartingAtPublicationReusesTheCompletedDownload() = exerciseRestart(false)

    @Test
    fun restartingImmediatelyAfterPublicationDoesNotCreateAnotherCopy() = exerciseRestart(true)

    @Test
    fun failedReceiptRemovalIsReportedAndCanBeRetried() = exerciseRestart(null, failRemoval = true)

    @Test
    fun restartingAfterSafWriteBeforeReceiptReusesTheVisibleFile() = exerciseRestart(null, safCrash = true)

    @Test
    fun restartingBeforeSafWriteCompletesTheSameDestinationBeforeAck() = exerciseRestart(null, safCrash = true, beforeWrite = true)

    @Test
    fun restartingAfterSafCreationBeforeUriReturnsReusesTheMarkedDocument() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true)

    @Test
    fun changedSafMarkerLeavesItemPendingWithoutTouchingOtherDocuments() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true, recovery = "changed")

    @Test
    fun hiddenSafDocumentDoesNotCreateAnotherCopy() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true, recovery = "hidden")

    @Test
    fun ambiguousSafMarkerDoesNotOverwriteEitherDocument() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true, recovery = "ambiguous")

    @Test
    fun loadingSafQueryDoesNotAdoptItsPartialResult() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true, recovery = "loading")

    @Test
    fun unavailableSafQueryKeepsItsCreationIntent() =
        exerciseRestart(null, safCrash = true, beforeWrite = true, beforeUri = true, recovery = "unavailable")

    @Test
    fun realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement() =
        exerciseRestart(null, failRemoval = true, listenRecovery = true)

    @Test
    fun uninterruptedSafCreationUsesReturnedUriEvenWhenProviderChangesMarker() =
        exerciseRestart(null, normalSaf = true)

    private fun exerciseRestart(crashAfterPublication: Boolean?, failRemoval: Boolean = false, safCrash: Boolean = false, beforeWrite: Boolean = false, beforeUri: Boolean = false, recovery: String? = null, listenRecovery: Boolean = false, normalSaf: Boolean = false) = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        var rejectRemoval = failRemoval
        var crashBeforeReceipt = safCrash && !beforeWrite
        var crashBeforeWrite = beforeWrite && !beforeUri
        var crashBeforeUri = beforeUri
        val names = mutableListOf<String>()
        var recovering = false
        val copies = mutableListOf<File>()
        val preferences = application.getSharedPreferences("pairing", 0)
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "pairing") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        var removesReceipt = false
                        var writesReceipt = false
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                writesReceipt = key.startsWith("received-file:")
                                editor.putString(key, value)
                                return this
                            }
                            override fun remove(key: String): SharedPreferences.Editor {
                                removesReceipt = key.startsWith("received-file:")
                                editor.remove(key)
                                return this
                            }
                            override fun commit(): Boolean {
                                if (crashBeforeReceipt && writesReceipt && copies.singleOrNull()?.length() == 7L) {
                                    crashBeforeReceipt = false
                                    throw SimulatedProcessDeath()
                                }
                                if (rejectRemoval && removesReceipt) {
                                    rejectRemoval = false
                                    if (listenRecovery) editor.commit() // Android changes its memory cache even on disk failure.
                                    return false
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val published = mutableSetOf<Uri>()
        var crash = crashAfterPublication != null
        val authority = if (safCrash || normalSaf) "test.documents" else "media"
        if (safCrash || normalSaf) context.getSharedPreferences("settings", 0).edit()
            .putString("receive-folder", "content://test.documents/tree/root").commit()
        // The system storage boundary writes real bytes; AppState and the HTTP client are real.
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                check(method == "android:createDocument")
                copies.add(File(context.cacheDir, "received-${copies.size}.bin"))
                copies.last().writeText("")
                names.add(extras!!.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)
                if (normalSaf) names[names.lastIndex] = "received.bin"
                if (crashBeforeUri) {
                    crashBeforeUri = false
                    throw SimulatedProcessDeath()
                }
                val uri = Uri.parse("content://test.documents/document/${copies.size}")
                published.add(uri)
                return Bundle().apply { putParcelable("uri", uri) }
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                copies.add(File(context.cacheDir, "received-${copies.size}.bin"))
                return Uri.withAppendedPath(uri, copies.size.toString())
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (normalSaf && mode != "r") assertEquals(
                    "writing:$uri", preferences.all.entries.single { it.key.startsWith("received-file:") }.value,
                    "The returned URI must be persisted before opening the destination",
                )
                val descriptor = ParcelFileDescriptor.open(copies[uri.lastPathSegment!!.toInt() - 1],
                    if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
                if (crashBeforeWrite) {
                    crashBeforeWrite = false
                    descriptor.close()
                    throw SimulatedProcessDeath()
                }
                return descriptor
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
                if (crash && crashAfterPublication == false) {
                    crash = false
                    throw SimulatedProcessDeath()
                }
                if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) published.add(uri)
                if (crash) {
                    crash = false
                    throw SimulatedProcessDeath()
                }
                return 1
            }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                if (copies[uri.lastPathSegment!!.toInt() - 1].delete()) 1 else 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (recovering) assertEquals("root", DocumentsContract.getTreeDocumentId(uri))
                if (recovering && recovery == "unavailable") return null
                return MatrixCursor(projection!!).apply {
                    if (recovering && recovery == "loading") extras = Bundle().apply {
                        putBoolean(DocumentsContract.EXTRA_LOADING, true)
                    }
                    if (recovering && recovery == "hidden") return@apply
                    names.forEachIndexed { index, name ->
                        addRow(projection.map { column ->
                            when (column) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> (index + 1).toString()
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                                else -> null
                            }
                        })
                    }
                }
            }
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(context, ProviderInfo().apply { this.authority = authority }) })
        val gets = AtomicInteger()
        val acks = AtomicInteger()
        val listens = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/outbox/42/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 7)
            exchange.responseBody.use { it.write("payload".toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/42/ack") { exchange ->
            exchange.sendResponseHeaders(if (acks.incrementAndGet() == 1 && crashAfterPublication == null && !failRemoval && !safCrash && !normalSaf) 503 else 204, -1)
            exchange.close()
        }
        server.createContext("/listen") { exchange ->
            listens.incrementAndGet()
            val body = if (acks.get() == 0) """{"kind":"file","id":42,"at_ms":0,"name":"received.bin","size":7}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(context)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}"""))
            state.knownAddress.save("127.0.0.1")
            val item = Item.FileMeta(42, 0, "received.bin", 7)
            if (normalSaf) {
                state.receiveFile(item, state.client()!!)
                assertEquals(1, gets.get())
                assertEquals(1, acks.get())
                assertEquals("payload", copies.single().readText())
                assertEquals(Delivery.DELIVERED, state.messages.single().delivery)
                assertTrue(preferences.all.keys.none { it.startsWith("received-file:") })
                return@runBlocking
            }
            if (failRemoval) {
                if (listenRecovery) {
                    val listening = launch { state.listen(state.pairing!!, state.client()!!) }
                    try {
                        withTimeout(5_000) { while (state.connectionError == null) delay(10) }
                    } finally { listening.cancelAndJoin() }
                    assertEquals(Delivery.DELIVERED, state.messages.single().delivery, "A successful ACK means delivered even if receipt cleanup fails")
                    assertTrue(preferences.all.keys.any { it.startsWith("received-file:") }, "Failed removal must remain retryable")
                } else assertFailsWith<IOException> { state.receiveFile(item, state.client()!!) }
                assertTrue(state.connectionError != null, "Receipt cleanup failure must be visible")
                assertEquals(1, acks.get())
            } else if (crashAfterPublication == null && !safCrash) {
                assertEquals(503, assertFailsWith<ProtocolRejectedException> {
                    state.receiveFile(item, state.client()!!)
                }.code)
            } else {
                // Error bypasses normal exception cleanup, like a killed process.
                assertFailsWith<SimulatedProcessDeath> { state.receiveFile(item, state.client()!!) }
                assertEquals(0, acks.get())
            }
            assertEquals(if (beforeWrite) "" else "payload", copies.single().readText())

            if (beforeUri) {
                // An unrelated writer can use the original name while the app is dead.
                copies.add(File(context.cacheDir, "unrelated.bin").apply { writeText("foreign") })
                names.add(if (recovery == "ambiguous") names.first() else "received.bin")
                if (recovery == "changed") names[0] = "received.bin"
                // Recovery must use the original Pasta, even if the preference has changed.
                if (recovery == null) context.getSharedPreferences("settings", 0).edit()
                    .putString("receive-folder", "content://test.documents/tree/other").commit()
                recovering = true
            }
            val reopened = AppState(context)
            assertTrue(reopened.messages.isEmpty(), "The chat session must remain disposable")
            if (recovery != null) {
                assertFailsWith<IOException> { reopened.receiveFile(item, reopened.client()!!) }
                assertTrue(reopened.connectionError != null)
                assertEquals(0, gets.get())
                assertEquals(0, acks.get())
                assertEquals(2, copies.size)
                assertEquals("", copies.first().readText())
                assertEquals("foreign", copies.last().readText())
                assertTrue(preferences.all.keys.any { it.startsWith("received-file:") })
                return@runBlocking
            }
            if (listenRecovery) {
                val listening = launch { reopened.listen(reopened.pairing!!, reopened.client()!!) }
                try {
                    withTimeout(5_000) { while (listens.get() < 2) delay(10) }
                    assertTrue(preferences.all.keys.none { it.startsWith("received-file:") }, "Cleanup cannot depend on another file announcement")
                    assertTrue(reopened.messages.isEmpty(), "Receipt recovery must not restore chat history")
                    assertEquals(1, gets.get())
                    assertEquals(1, copies.size)
                    assertEquals("payload", copies.single().readText())
                } finally { listening.cancelAndJoin() }
                return@runBlocking
            }
            reopened.receiveFile(item, reopened.client()!!)
            assertEquals(if (crashAfterPublication == null && !safCrash) 2 else 1, acks.get())
            assertEquals(1, gets.get(), "A saved Item only needs another ACK")
            assertEquals(if (beforeUri) 2 else 1, copies.size, "Restarting must not create another copy")
            assertEquals("payload", copies.first().readText())
            if (beforeUri) assertEquals("foreign", copies.last().readText())
            assertEquals(if (beforeUri) 0 else 1, published.size, "Recovery must finish publication before acknowledging")
            assertEquals(Delivery.DELIVERED, reopened.messages.single().delivery)
            assertTrue(preferences.all.keys.none { it.startsWith("received-file:") }, "An acknowledged receipt must be removed")
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    private class SimulatedProcessDeath : Error("process killed at publication")
}
