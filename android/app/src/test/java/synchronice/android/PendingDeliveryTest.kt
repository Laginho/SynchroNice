package synchronice.android

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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeliveryTest {
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
            context.getSharedPreferences("pairing", 0).edit().clear().commit()
            context.filesDir.resolve("pending-items.txt").delete()
            val state = AppState(context)
            state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}""")
            state.knownAddress.save("127.0.0.1")

            val first = state.addOutgoing("first")
            state.sendText(first, "first")
            val second = state.addOutgoing("second")
            state.sendText(second, "second")
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
