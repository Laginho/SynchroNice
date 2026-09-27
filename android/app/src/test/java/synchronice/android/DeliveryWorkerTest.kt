package synchronice.android

import com.google.common.util.concurrent.ListenableFuture
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.PendingItem
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun rejectedDeliveryKeepsRetryingAndSaysSoInTheChat() = checkResponse(500)

    @Test
    fun presenceComingBackDeliversWithoutWaitingForTheBackoff() = withPc { app, status ->
        val state = app.state
        state.client()!!.enqueueText("pendente")
        val backedOff = backedOffDelivery(app)
        status.set(201)

        state.updateOnline(false)
        state.updateOnline(true)
        runNextDelivery(app, except = backedOff)

        withTimeout(5_000) { while (state.hasPending()) delay(10) }
    }

    @Test
    fun aNewSendDoesNotWaitBehindABackedOffDelivery() = withPc { app, status ->
        val state = app.state
        val backedOff = backedOffDelivery(app)
        status.set(201)

        val id = state.addOutgoing("novo")
        state.queueText(id, "novo")
        runNextDelivery(app, except = backedOff)

        withTimeout(5_000) { while (state.hasPending()) delay(10) }
        assertEquals(listOf(Delivery.DELIVERED), state.messages.map { it.delivery })
    }

    // Android 12+ recusa iniciar serviço em primeiro plano com o app em segundo plano.
    @Test
    fun aLargeFileStillGoesWhenForegroundIsRefused() = withPc(path = "/items/file") { app, status ->
        status.set(201)
        val state = app.state
        val source = File(app.cacheDir, "grande.bin").apply { writeBytes(ByteArray(LARGE_FILE_BYTES.toInt())) }
        assertTrue(state.client()!!.enqueueFile(source))
        val refused = ForegroundUpdater { _, _, _ -> FailedFuture(IllegalStateException("ForegroundServiceStartNotAllowedException")) }

        val result = TestListenableWorkerBuilder<DeliveryWorker>(app).setForegroundUpdater(refused).build().doWork()

        assertEquals(Result.success(), result)
        assertFalse(state.hasPending())
    }

    /** Um worker parado no backoff: enfileirado, mas só roda daqui a horas. */
    private fun backedOffDelivery(app: SynchroNiceApplication): UUID {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>().setInitialDelay(5, TimeUnit.HOURS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("pending-delivery", ExistingWorkPolicy.REPLACE, request).result.get()
        return request.id
    }

    /** Libera as restrições do próximo envio agendado, que precisa já estar pronto para rodar. */
    private fun runNextDelivery(app: SynchroNiceApplication, except: UUID) {
        val next = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pending-delivery").get()
            .singleOrNull { it.id != except && it.state == WorkInfo.State.ENQUEUED }
            ?: error("nenhum envio pronto para rodar além do que está no backoff")
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(next.id)
    }

    private fun withPc(path: String = "/items/text", body: suspend (SynchroNiceApplication, AtomicInteger) -> Unit) = runBlocking {
        val status = AtomicInteger(503)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(status.get(), -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val app = RuntimeEnvironment.getApplication() as SynchroNiceApplication
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
            assertTrue(app.state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}"""))
            app.state.knownAddress.save("127.0.0.1")
            body(app, status)
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

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

            if (status == 500) {
                assertEquals(Result.retry(), result)
                assertEquals(pending, FilePendingQueueStore(queue).load())
                assertTrue(state.connectionError.orEmpty().contains("HTTP 500"), state.connectionError)
            } else if (status == 401) {
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

private class FailedFuture(private val error: Throwable) : ListenableFuture<Void> {
    override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = true
    override fun get(): Void = throw ExecutionException(error)
    override fun get(timeout: Long, unit: TimeUnit): Void = throw ExecutionException(error)
}
