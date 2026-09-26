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

    private fun exerciseRestart(crashAfterPublication: Boolean?, failRemoval: Boolean = false, safCrash: Boolean = false, beforeWrite: Boolean = false) = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        var rejectRemoval = failRemoval
        var crashBeforeReceipt = safCrash && !beforeWrite
        var crashBeforeWrite = beforeWrite
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
        val authority = if (safCrash) "test.documents" else "media"
        if (safCrash) context.getSharedPreferences("settings", 0).edit()
            .putString("receive-folder", "content://test.documents/tree/root").commit()
        // The system storage boundary writes real bytes; AppState and the HTTP client are real.
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                check(method == "android:createDocument")
                copies.add(File(context.cacheDir, "received-${copies.size}.bin"))
                val uri = Uri.parse("content://test.documents/document/${copies.size}")
                published.add(uri)
                return Bundle().apply { putParcelable("uri", uri) }
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                copies.add(File(context.cacheDir, "received-${copies.size}.bin"))
                return Uri.withAppendedPath(uri, copies.size.toString())
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
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
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(context, ProviderInfo().apply { this.authority = authority }) })
        val gets = AtomicInteger()
        val acks = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/outbox/42/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 7)
            exchange.responseBody.use { it.write("payload".toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/42/ack") { exchange ->
            exchange.sendResponseHeaders(if (acks.incrementAndGet() == 1 && crashAfterPublication == null && !failRemoval && !safCrash) 503 else 204, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(context)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}"""))
            state.knownAddress.save("127.0.0.1")
            val item = Item.FileMeta(42, 0, "received.bin", 7)
            if (failRemoval) {
                assertFailsWith<IOException> { state.receiveFile(item, state.client()!!) }
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

            val reopened = AppState(context)
            assertTrue(reopened.messages.isEmpty(), "The chat session must remain disposable")
            reopened.receiveFile(item, reopened.client()!!)
            assertEquals(if (crashAfterPublication == null && !safCrash) 2 else 1, acks.get())
            assertEquals(1, gets.get(), "A saved Item only needs another ACK")
            assertEquals(1, copies.size, "Restarting must not create another copy")
            assertEquals("payload", copies.single().readText())
            assertEquals(1, published.size, "Recovery must finish publication before acknowledging")
            assertEquals(Delivery.DELIVERED, reopened.messages.single().delivery)
            assertTrue(preferences.all.keys.none { it.startsWith("received-file:") }, "An acknowledged receipt must be removed")
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    private class SimulatedProcessDeath : Error("process killed at publication")
}
