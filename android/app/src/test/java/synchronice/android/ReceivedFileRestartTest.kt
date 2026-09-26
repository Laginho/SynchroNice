package synchronice.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
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
    fun restartingAfterFailedAckConfirmsSavedFileWithoutAnotherDownload() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val copies = mutableListOf<File>()
        val published = mutableSetOf<Uri>()
        // The system storage boundary writes real bytes; AppState and the HTTP client are real.
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                copies.add(File(context.cacheDir, "received-${copies.size}.bin"))
                return Uri.withAppendedPath(uri, copies.size.toString())
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(copies[uri.lastPathSegment!!.toInt() - 1],
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
                if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) published.add(uri)
                return 1
            }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                if (copies[uri.lastPathSegment!!.toInt() - 1].delete()) 1 else 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(context, ProviderInfo().apply { authority = "media" }) })
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
            exchange.sendResponseHeaders(if (acks.incrementAndGet() == 1) 503 else 204, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(context)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}"""))
            state.knownAddress.save("127.0.0.1")
            val item = Item.FileMeta(42, 0, "received.bin", 7)
            assertEquals(503, assertFailsWith<ProtocolRejectedException> {
                state.receiveFile(item, state.client()!!)
            }.code)
            assertEquals(1, published.size)
            assertEquals("payload", copies.single().readText())

            val reopened = AppState(context)
            assertTrue(reopened.messages.isEmpty(), "The chat session must remain disposable")
            reopened.receiveFile(item, reopened.client()!!)
            assertEquals(2, acks.get())
            assertEquals(1, gets.get(), "A saved Item only needs another ACK")
            assertEquals(1, copies.size, "Restarting must not create another copy")
            assertEquals(Delivery.DELIVERED, reopened.messages.single().delivery)
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
