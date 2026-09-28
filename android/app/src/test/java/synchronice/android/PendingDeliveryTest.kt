package synchronice.android

import androidx.work.testing.WorkManagerTestInitHelper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.put(block)
    }

    fun runNext() {
        (tasks.poll(5, TimeUnit.SECONDS) ?: error("coroutine did not reach the queued continuation")).run()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeliveryTest {
    @Test
    fun aFileDeliveredBeforeQueueFileResumesStillAppearsDelivered() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/items/file") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        val queueFile = context.filesDir.resolve("pending-items.txt")
        queueFile.delete()
        File(queueFile.parentFile, "${queueFile.name}.payloads").deleteRecursively()
        val original = File(context.cacheDir, "race.bin").apply { writeText("arquivo") }
        try {
            context.getSharedPreferences("pairing", 0).edit().clear().commit()
            val state = AppState(context)
            state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}""")
            state.knownAddress.save("127.0.0.1")
            val dispatcher = QueuedDispatcher()
            val enqueue = CoroutineScope(dispatcher).launch { state.queueFile(original) }

            dispatcher.runNext()
            withTimeout(5_000) {
                while (!state.hasPending()) delay(10)
            }
            state.retryPending()
            dispatcher.runNext()
            enqueue.join()

            assertEquals(listOf(Delivery.DELIVERED), state.messages.map { it.delivery })
        } finally {
            original.delete()
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun aConfirmedTextIsDeliveredWhileTheNextTextRemainsPending() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val status = AtomicInteger(503)
        val sent = AtomicInteger()
        server.createContext("/items/text") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(if (status.get() == 201 && sent.incrementAndGet() > 1) 503 else status.get(), -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val context = RuntimeEnvironment.getApplication()
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            context.getSharedPreferences("pairing", 0).edit().clear().commit()
            context.filesDir.resolve("pending-items.txt").delete()
            val state = AppState(context)
            state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}""")
            state.knownAddress.save("127.0.0.1")

            val first = state.addOutgoing("first")
            state.queueText(first, "first")
            val second = state.addOutgoing("second")
            state.queueText(second, "second")
            state.retryPending()
            assertEquals(listOf(Delivery.PENDING, Delivery.PENDING), state.messages.map { it.delivery })

            sent.set(0)
            status.set(201)
            state.retryPending()

            assertEquals(listOf(Delivery.DELIVERED, Delivery.PENDING), state.messages.map { it.delivery })
        } finally {
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
