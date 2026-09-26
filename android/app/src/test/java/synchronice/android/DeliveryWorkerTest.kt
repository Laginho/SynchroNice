package synchronice.android

import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
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
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.PendingItem
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryWorkerTest {
    @Test
    fun unauthorizedDeliveryDiscardsPairAndPendingFiles() = checkResponse(401)

    @Test
    fun unavailableDeliveryKeepsPairAndPendingFilesForRetry() = checkResponse(503)

    private fun checkResponse(status: Int) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/items/text") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val app = RuntimeEnvironment.getApplication() as SynchroNiceApplication
            val state = app.state
            assertTrue(state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}"""))
            state.knownAddress.save("127.0.0.1")
            val client = state.client()!!
            client.enqueueText("pending")
            val source = File(app.cacheDir, "worker.bin").apply { writeText("payload") }
            assertTrue(client.enqueueFile(source))
            val queue = File(app.filesDir, "pending-items.txt")
            val pending = FilePendingQueueStore(queue).load()
            val payload = File((pending.last() as PendingItem.File).path)
            val pair = state.pairing

            val result = TestListenableWorkerBuilder<DeliveryWorker>(app).build().doWork()

            if (status == 401) {
                assertEquals(Result.success(), result)
                assertNull(state.pairing)
                assertNull(AppState(app).pairing)
                assertFalse(state.hasPending())
                assertTrue(FilePendingQueueStore(queue).load().isEmpty())
                assertFalse(payload.exists())
            } else {
                assertEquals(Result.retry(), result)
                assertEquals(pair, state.pairing)
                assertEquals(pair, AppState(app).pairing)
                assertEquals(pending, FilePendingQueueStore(queue).load())
                assertEquals("payload", payload.readText())
            }
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
