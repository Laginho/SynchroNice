package synchronice.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ListenLoopTest {
    private val token = "0123456789abcdef0123456789abcdef"

    private fun freshContext() = RuntimeEnvironment.getApplication().also { app ->
        File(app.filesDir, "pending-items.txt").delete()
        app.getSharedPreferences("pairing", 0).edit().clear().commit()
    }

    private fun registerMediaProvider(app: android.app.Application, onUpdate: (Uri) -> Int) {
        val copies = mutableListOf<File>()
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                copies.add(File(app.cacheDir, "listen-loop-${copies.size}.bin"))
                return Uri.withAppendedPath(uri, copies.size.toString())
            }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(
                copies[uri.lastPathSegment!!.toInt() - 1],
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = onUpdate(uri)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(app, ProviderInfo().apply { authority = "media" }) })
    }

    @Test
    fun listenReopensWhileALargeDownloadIsStillInProgress() = runBlocking {
        val app = freshContext()
        registerMediaProvider(app) { 1 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val listens = AtomicInteger()
        val gets = AtomicInteger()
        val releaseDownload = CountDownLatch(1)
        server.createContext("/listen") { exchange ->
            val n = listens.incrementAndGet()
            val body = when (n) {
                1 -> """{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}"""
                2 -> """{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}
{"kind":"text","id":2,"at_ms":0,"text":"meio"}"""
                else -> ""
            }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/1/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { output ->
                output.write("pa".toByteArray())
                output.flush()
                releaseDownload.await(5, TimeUnit.SECONDS)
                output.write("yl".toByteArray())
            }
            exchange.close()
        }
        server.createContext("/outbox/1/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.createContext("/outbox/2/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (state.messages.none { it.text == "meio" }) delay(10) }
                val fileMessage = state.messages.first { it.file != null }
                assertEquals(Delivery.PENDING, fileMessage.delivery, "the download must still be running when the reopened listen delivers new text")
                releaseDownload.countDown()
                delay(200)
                assertEquals(1, gets.get(), "a file re-announced while its download is in flight must not start a second download")
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun receivingABatchOfItemsDoesNotChangeThePresence() = runBlocking {
        val app = freshContext()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val delivered = AtomicInteger()
        server.createContext("/listen") { exchange ->
            val body = if (delivered.getAndIncrement() == 0) """{"kind":"text","id":1,"at_ms":0,"text":"oi"}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/1/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            state.updateOnline(true)
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (state.messages.isEmpty()) delay(10) }
                assertTrue(state.online, "presence is decided only by the HEAD /listen poller, not by receiving a batch")
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aReceiptThatKeepsFailingToPublishDoesNotBlockNewTextFromArriving() = runBlocking {
        val app = freshContext()
        registerMediaProvider(app) { throw IOException("recibo falhou") }
        val preferences = app.getSharedPreferences("pairing", 0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val delivered = AtomicInteger()
        server.createContext("/listen") { exchange ->
            val body = if (delivered.getAndIncrement() == 0) """{"kind":"text","id":7,"at_ms":0,"text":"novo"}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/7/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            preferences.edit().putString("received-file:$token:99", "content://media/99").commit()
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (state.messages.isEmpty()) delay(10) }
                assertEquals("novo", state.messages.single().text)
                assertTrue(preferences.contains("received-file:$token:99"), "a failing receipt stays queued for the next attempt")
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aReceiptPointingAtADeletedFileIsDroppedAndTheItemIsDownloadedAgain() = runBlocking {
        val app = freshContext()
        registerMediaProvider(app) { uri -> if (uri.lastPathSegment == "99") 0 else 1 }
        val preferences = app.getSharedPreferences("pairing", 0)
        val acks = AtomicInteger()
        val gets = AtomicInteger()
        val listens = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/listen") { exchange ->
            val body = if (listens.incrementAndGet() == 1) """{"kind":"file","id":99,"at_ms":0,"name":"a.bin","size":7}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/99/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 7)
            exchange.responseBody.use { it.write("payload".toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/99/ack") { exchange ->
            acks.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            preferences.edit().putString("received-file:$token:99", "content://media/99").commit()
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (state.messages.singleOrNull()?.delivery != Delivery.DELIVERED) delay(10) }
                assertEquals(1, gets.get(), "the stale receipt must not skip a fresh download")
                assertEquals(1, acks.get(), "the stale, undeletable receipt must never be acknowledged")
                assertFalse(preferences.contains("received-file:$token:99"))
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
