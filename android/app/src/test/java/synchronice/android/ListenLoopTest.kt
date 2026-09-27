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
import synchronice.protocolclient.Item
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
                3 -> """{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}"""
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
                withTimeout(5_000) { while (listens.get() < 3) delay(10) }
                delay(1_000)
                assertEquals(3, listens.get(), "a batch with only an in-flight file re-announced must wait for the empty-batch delay, not reopen immediately")
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
        val updates98 = AtomicInteger()
        registerMediaProvider(app) { uri ->
            if (uri.lastPathSegment == "98") {
                updates98.incrementAndGet()
                throw IOException("recibo falhou")
            } else 1
        }
        val preferences = app.getSharedPreferences("pairing", 0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val delivered = AtomicInteger()
        val listens = AtomicInteger()
        val acks99 = AtomicInteger()
        server.createContext("/listen") { exchange ->
            listens.incrementAndGet()
            val body = if (delivered.getAndIncrement() == 0) """{"kind":"text","id":7,"at_ms":0,"text":"novo"}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/7/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.createContext("/outbox/99/ack") { exchange -> acks99.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            preferences.edit()
                .putString("received-file:$token:98", "content://media/98")
                .putString("received-file:$token:99", "content://media/99")
                .commit()
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (state.messages.isEmpty()) delay(10) }
                assertEquals("novo", state.messages.single().text)
                withTimeout(5_000) { while (listens.get() < 2) delay(10) }
                assertTrue(preferences.contains("received-file:$token:98"), "a failing receipt stays queued for the next attempt")
                assertTrue(updates98.get() >= 2, "the failing receipt must be retried before every listen")
                assertFalse(preferences.contains("received-file:$token:99"), "another receipt in the same batch must still be acknowledged and dropped")
                assertEquals(1, acks99.get())
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
                assertEquals(1, acks.get(), "the fresh download that replaces the stale receipt must be acknowledged")
                assertFalse(preferences.contains("received-file:$token:99"))
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aDownloadCancelledMidwayIsResumedByTheRelaunchedLoop() = runBlocking {
        val app = freshContext()
        registerMediaProvider(app) { 1 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val gets = AtomicInteger()
        val stallFirstDownload = CountDownLatch(1)
        server.createContext("/listen") { exchange ->
            val body = if (gets.get() < 2) """{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}""" else ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/1/file") { exchange ->
            val attempt = gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { output ->
                if (attempt == 1) {
                    output.write("pa".toByteArray())
                    output.flush()
                    stallFirstDownload.await(5, TimeUnit.SECONDS)
                } else {
                    output.write("payl".toByteArray())
                }
            }
            exchange.close()
        }
        server.createContext("/outbox/1/ack") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            var listening = launch { state.listen(state.pairing!!, state.client()!!) }
            withTimeout(5_000) { while (gets.get() < 1) delay(10) }
            listening.cancelAndJoin()
            listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                withTimeout(5_000) { while (gets.get() < 2) delay(10) }
                withTimeout(5_000) { while (state.messages.singleOrNull()?.delivery != Delivery.DELIVERED) delay(10) }
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aDownloadThatFailsFastDoesNotTurnIntoAHotLoop() = runBlocking {
        val app = freshContext()
        registerMediaProvider(app) { 1 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val listens = AtomicInteger()
        val gets = AtomicInteger()
        server.createContext("/listen") { exchange ->
            listens.incrementAndGet()
            // A real long-poll only answers once there's something to say; this fake one
            // answers instantly, which would mask the bug by giving a fast-failing
            // download's cleanup no time to run before the next reopen is already decided.
            // A short, fixed wait after the first download attempt reaches the server puts
            // both implementations on equal footing instead of leaving it to a real timing
            // race: the buggy immediate free is long done by then, the fixed 1s hold is not.
            if (gets.get() > 0) Thread.sleep(50)
            val body = """{"kind":"file","id":5,"at_ms":0,"name":"a.bin","size":4}"""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/5/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            val listening = launch { state.listen(state.pairing!!, state.client()!!) }
            try {
                // The first /listen pays the resolver's one-time, uncached mDNS lookup (up to
                // 2s in this fake environment); measure the steady-state rate after that, while
                // the resolver's 3s cache is still warm, so it isn't mistaken for a hot loop.
                withTimeout(5_000) { while (listens.get() < 1) delay(10) }
                val listensAtWarm = listens.get()
                val getsAtWarm = gets.get()
                delay(2_000)
                val listensSince = listens.get() - listensAtWarm
                val getsSince = gets.get() - getsAtWarm
                assertTrue(listensSince <= 3, "a fast-failing download must not turn into a hot loop: listens=$listensSince")
                assertTrue(getsSince <= 3, "a fast-failing download must not turn into a hot loop: gets=$getsSince")
            } finally {
                listening.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aReAnnouncedItemWhoseSavedFileIsGoneIsDownloadedAgain() = runBlocking {
        val app = freshContext()
        val updates = AtomicInteger()
        registerMediaProvider(app) { if (updates.getAndIncrement() == 0) 0 else 1 }
        val preferences = app.getSharedPreferences("pairing", 0)
        val gets = AtomicInteger()
        val acks = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/outbox/42/file") { exchange ->
            gets.incrementAndGet()
            exchange.sendResponseHeaders(200, 7)
            exchange.responseBody.use { it.write("payload".toByteArray()) }
            exchange.close()
        }
        server.createContext("/outbox/42/ack") { exchange -> acks.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val state = AppState(app)
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$token"}"""))
            state.knownAddress.save("127.0.0.1")
            preferences.edit().putString("received-file:$token:42", "content://media/42").commit()
            val item = Item.FileMeta(42, 0, "a.bin", 7)

            assertFailsWith<FileGoneException> { state.receiveFile(item, state.client()!!) }
            assertFalse(preferences.contains("received-file:$token:42"))
            assertEquals(Delivery.FAILED, state.messages.single().delivery)
            assertNull(state.messages.single().uri)
            assertEquals(0, gets.get())
            assertEquals(0, acks.get())

            state.receiveFile(item, state.client()!!)
            assertEquals(1, gets.get())
            assertEquals(1, acks.get())
            assertEquals(Delivery.DELIVERED, state.messages.single().delivery)
            assertFalse(preferences.contains("received-file:$token:42"))
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
