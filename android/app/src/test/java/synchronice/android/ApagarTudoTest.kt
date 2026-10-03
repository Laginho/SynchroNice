package synchronice.android

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.work.testing.WorkManagerTestInitHelper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TOKEN = "0123456789abcdef0123456789abcdef"

private data class AtAck(val chat: List<String>, val pending: Boolean)

/** O PC falso: serve `/listen` por lote, aceita envios e confirmações, e guarda o que o app fez. */
private class FakePc {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        .apply { executor = Executors.newCachedThreadPool() }
    val batches = LinkedBlockingQueue<String>()
    val textStatus = AtomicInteger(201)
    val deleteStatus = AtomicInteger(204)
    val textIds = CopyOnWriteArrayList<String>()
    val deletes = CopyOnWriteArrayList<JSONObject>()
    val acks = ConcurrentHashMap<Long, AtAck>()
    val downloads = AtomicInteger()
    var observe: () -> AtAck = { AtAck(emptyList(), false) }
    var fileBody: (HttpExchange) -> Unit = { exchange ->
        exchange.sendResponseHeaders(200, 4)
        exchange.responseBody.use { it.write("payl".toByteArray()) }
    }

    init {
        server.createContext("/listen") { exchange ->
            val body = batches.poll(3, TimeUnit.SECONDS) ?: ""
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.createContext("/items/text") { exchange ->
            textIds.add(exchange.requestHeaders.getFirst("X-Item-Id"))
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(textStatus.get(), -1)
            exchange.close()
        }
        server.createContext("/items/delete") { exchange ->
            deletes.add(JSONObject(exchange.requestBody.readBytes().decodeToString()))
            exchange.sendResponseHeaders(deleteStatus.get(), -1)
            exchange.close()
        }
        server.createContext("/outbox/") { exchange ->
            val parts = exchange.requestURI.path.split('/')
            if (parts[3] == "ack") {
                acks[parts[2].toLong()] = observe()
                exchange.sendResponseHeaders(204, -1)
            } else {
                downloads.incrementAndGet()
                fileBody(exchange)
            }
            exchange.close()
        }
        server.start()
    }

    fun pair(app: Application): AppState {
        val state = AppState(app)
        assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"$TOKEN"}"""))
        state.knownAddress.save("127.0.0.1")
        observe = { AtAck(state.messages.map { it.text }, state.hasPending()) }
        return state
    }
}

/** O MediaStore falso: os arquivos vivem no cache, e cada `delete` fica registrado. */
private class MediaFake(app: Application) {
    val files = CopyOnWriteArrayList<File>()
    val deleted = CopyOnWriteArrayList<Uri>()
    @Volatile var deleteError: RuntimeException? = null

    init {
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            private fun backing(uri: Uri) = files[uri.lastPathSegment!!.toInt() - 1]

            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                files.add(File(app.cacheDir, "apagar-tudo-${files.size}-${System.nanoTime()}.bin"))
                return Uri.withAppendedPath(uri, files.size.toString())
            }
            override fun openFile(uri: Uri, mode: String) =
                ParcelFileDescriptor.open(backing(uri), ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.SIZE)).also { cursor ->
                    backing(uri).takeIf { it.exists() }?.let { cursor.addRow(arrayOf<Any?>(it.length())) }
                }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deleteError?.let { throw it }
                deleted.add(uri)
                return 1
            }
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(app, ProviderInfo().apply { authority = "media" }) })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ApagarTudoTest {
    private val receivedFile = """{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}"""

    private fun deleteLine(id: Long, doPc: String = "", doCelular: String = "") =
        """{"kind":"delete","id":$id,"at_ms":0,"do_pc":[$doPc],"do_celular":[$doCelular]}"""

    private fun withPc(block: suspend CoroutineScope.(FakePc, Application) -> Unit) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "pending-items.txt").delete()
        File(app.filesDir, "pending-items.txt.payloads").deleteRecursively()
        app.getSharedPreferences("pairing", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val pc = FakePc()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            block(this, pc, app)
        } finally {
            Dispatchers.resetMain()
            pc.server.stop(0)
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    private suspend fun CoroutineScope.whileListening(state: AppState, block: suspend () -> Unit) {
        val listening = launch { state.listen(state.pairing!!, state.client()!!) }
        try {
            block()
        } finally {
            listening.cancelAndJoin()
        }
    }

    private suspend fun AppState.send(text: String) = queueText(addOutgoing(text), text)

    private fun JSONObject.strings(key: String) = getJSONArray(key).let { array -> (0 until array.length()).map(array::getString) }

    private fun JSONObject.longs(key: String) = getJSONArray(key).let { array -> (0 until array.length()).map(array::getLong) }

    private suspend fun AppState.waitForDownloadedFile() =
        waitUntil { messages.singleOrNull()?.let { it.uri != null && it.delivery == Delivery.DELIVERED } == true }

    @Test
    fun aDeleteFromThePcRemovesTheListedBubblesIgnoresUnknownIdsAndAcksAfterApplying() = withPc { pc, app ->
        val state = pc.pair(app)
        pc.batches.put("""{"kind":"text","id":5,"at_ms":0,"text":"cinco"}
{"kind":"text","id":6,"at_ms":0,"text":"seis"}""")
        whileListening(state) {
            waitUntil { state.messages.size == 2 }
            pc.batches.put(deleteLine(9, doPc = "5,999", doCelular = "\"00000000-0000-0000-0000-000000000000\""))
            waitUntil { pc.acks.containsKey(9L) }
            assertEquals(listOf("seis"), state.messages.map { it.text })
            assertEquals(listOf("seis"), pc.acks.getValue(9).chat, "o ack só sai depois de a bolha sair do chat")
        }
    }

    @Test
    fun aDeleteFromThePcRemovesSentBubblesAndPendingItemsAndQueuesNothingBack() = withPc { pc, app ->
        val state = pc.pair(app)
        state.send("enviado")
        state.retryPending()
        pc.textStatus.set(503)
        state.send("pendente")
        state.retryPending()
        assertEquals(listOf(Delivery.DELIVERED, Delivery.PENDING), state.messages.map { it.delivery })
        val (sent, pending) = pc.textIds.distinct()
        pc.batches.put(deleteLine(9, doCelular = "\"$sent\",\"$pending\""))
        whileListening(state) {
            waitUntil { pc.acks.containsKey(9L) }
            assertTrue(state.messages.isEmpty())
            assertFalse(pc.acks.getValue(9).pending, "o ack só sai depois de o Pendente listado sair da fila")
        }
        pc.textStatus.set(201)
        state.retryPending()
        assertEquals(2, pc.textIds.size, "o Pendente listado nunca é reenviado")
        assertTrue(pc.deletes.isEmpty(), "uma exclusão vinda do PC não volta ao PC")
    }

    @Test
    fun deleteAllEmptiesTheChatDropsPendingItemsAndQueuesOneDeleteWithEveryProtocolId() = withPc { pc, app ->
        val state = pc.pair(app)
        pc.batches.put("""{"kind":"text","id":5,"at_ms":0,"text":"cinco"}""")
        whileListening(state) { waitUntil { state.messages.size == 1 } }
        state.send("enviado")
        state.retryPending()
        pc.textStatus.set(503)
        state.send("pendente")
        state.retryPending()
        val (sent, pending) = pc.textIds.distinct()

        state.deleteAll()

        assertTrue(state.messages.isEmpty())
        pc.textStatus.set(201)
        state.retryPending()
        assertEquals(2, pc.textIds.size, "o Pendente do chat saiu da fila")
        val body = pc.deletes.single()
        assertEquals(listOf(5L), body.longs("do_pc"))
        assertEquals(setOf(sent, pending), body.strings("do_celular").toSet())
    }

    @Test
    fun deleteAllOnAnEmptyChatQueuesNothing() = withPc { pc, app ->
        val state = pc.pair(app)

        state.deleteAll()

        assertFalse(state.hasPending())
    }

    @Test
    fun aQueuedDeleteHoldsAQueuePositionWithoutABubbleAndDoesNotHideTheNextDelivery() = withPc { pc, app ->
        val state = pc.pair(app)
        pc.batches.put("""{"kind":"text","id":5,"at_ms":0,"text":"cinco"}""")
        whileListening(state) { waitUntil { state.messages.size == 1 } }
        val window = state.messages.map { it.localId }.toSet()
        state.send("novo")
        state.deleteAll(window)
        assertEquals(listOf("novo"), state.messages.map { it.text })

        pc.deleteStatus.set(503)
        state.retryPending()

        assertEquals(listOf(Delivery.DELIVERED), state.messages.map { it.delivery }, "só a exclusão ficou na fila")
        assertEquals(listOf(5L), pc.deletes.single().longs("do_pc"))
    }

    @Test
    fun aDeleteFromThePcRemovesTheFileTheAppSaved() = withPc { pc, app ->
        val media = MediaFake(app)
        val state = pc.pair(app)
        pc.batches.put(receivedFile)
        whileListening(state) {
            state.waitForDownloadedFile()
            val saved = state.messages.single().uri
            pc.batches.put(deleteLine(9, doPc = "1"))
            waitUntil { pc.acks.containsKey(9L) }
            assertTrue(state.messages.isEmpty())
            assertEquals(listOf(saved), media.deleted.toList())
        }
    }

    @Test
    fun aFileWhoseSizeChangedIsNotDeletedButItsBubbleIs() = withPc { pc, app ->
        val media = MediaFake(app)
        val state = pc.pair(app)
        pc.batches.put(receivedFile)
        whileListening(state) {
            state.waitForDownloadedFile()
            media.files.single().writeText("outro conteúdo")
            pc.batches.put(deleteLine(9, doPc = "1"))
            waitUntil { pc.acks.containsKey(9L) }
            assertTrue(state.messages.isEmpty())
            assertTrue(media.deleted.isEmpty(), "na dúvida, não apaga")
        }
    }

    @Test
    fun aSecurityExceptionFromTheMediaStoreStillTakesTheItemOutOfTheSession() = withPc { pc, app ->
        val media = MediaFake(app)
        val state = pc.pair(app)
        pc.batches.put(receivedFile)
        whileListening(state) {
            state.waitForDownloadedFile()
            media.deleteError = SecurityException("arquivo de uma instalação anterior")
            pc.batches.put(deleteLine(9, doPc = "1"))
            waitUntil { pc.acks.containsKey(9L) }
            assertTrue(state.messages.isEmpty())
        }
    }

    @Test
    fun aFileStillDownloadingWhenDeleteAllRunsIsRemovedWhenTheDownloadFinishes() = withPc { pc, app ->
        val media = MediaFake(app)
        val release = CountDownLatch(1)
        pc.fileBody = { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { output ->
                output.write("pa".toByteArray())
                output.flush()
                release.await(5, TimeUnit.SECONDS)
                output.write("yl".toByteArray())
            }
        }
        val state = pc.pair(app)
        pc.batches.put(receivedFile)
        whileListening(state) {
            waitUntil { pc.downloads.get() == 1 && state.messages.size == 1 }

            state.deleteAll()

            assertTrue(state.messages.isEmpty())
            assertTrue(media.deleted.isEmpty(), "o download ainda não terminou")
            release.countDown()
            waitUntil { media.deleted.size == 1 }
            assertTrue(state.messages.isEmpty(), "a bolha não volta com o fim do download")
        }
    }

    @Test
    fun deleteAllCoversPendingItemsRestoredAfterTheAppRestarted() = withPc { pc, app ->
        pc.textStatus.set(503)
        val before = pc.pair(app)
        before.send("de antes")
        before.retryPending()
        val pendingId = pc.textIds.distinct().single()
        pc.textStatus.set(201)

        val restarted = AppState(app)
        assertEquals(listOf("de antes"), restarted.messages.map { it.text })
        restarted.deleteAll()
        restarted.retryPending()

        assertEquals(1, pc.textIds.size, "o Pendente restaurado saiu da fila sem ser entregue")
        assertEquals(listOf(pendingId), pc.deletes.single().strings("do_celular"))
    }
}
