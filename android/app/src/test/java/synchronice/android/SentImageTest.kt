package synchronice.android

import android.graphics.Bitmap
import android.graphics.Color
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
import org.robolectric.annotation.GraphicsMode
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class SentImageTest {
    @Test
    fun sentImageSurvivesDeliveryOnlyInTheSessionIncludingRestoredPending() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/items/file") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        val original = context.cacheDir.resolve("photo.png")
        try {
            val bitmap = Bitmap.createBitmap(4096, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val state = AppState(context)
            state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}""")
            state.knownAddress.save("127.0.0.1")
            val before = context.filesDir.walkTopDown().filter { it.isFile }.toSet()
            state.queueFile(original)
            original.delete()
            val pendingFiles = context.filesDir.walkTopDown().filter { it.isFile }.toSet()
            val restored = AppState(context)
            assertEquals(pendingFiles, context.filesDir.walkTopDown().filter { it.isFile }.toSet())
            withTimeout(5_000) { while (restored.messages.single().preview == null) delay(10) }
            for (session in listOf(state, restored)) {
                assertEquals(Delivery.PENDING, session.messages.single().delivery)
                val preview = assertNotNull(session.messages.single().preview)
                assertTrue(preview.width <= 2048 && preview.height <= 2048)
                assertEquals(Color.RED, preview.getPixel(0, 0))
            }
            restored.retryPending()
            assertFalse(restored.hasPending())
            assertEquals(Delivery.DELIVERED, restored.messages.single().delivery)
            assertEquals(Color.RED, assertNotNull(restored.messages.single().preview).getPixel(0, 0))
            val remaining = context.filesDir.walkTopDown().filter { it.isFile }.toSet() - before
            assertTrue(remaining.all { it.name == "pending-items.txt" }, "No payload or preview may remain: $remaining")
            assertTrue(AppState(context).messages.isEmpty())
        } finally {
            original.delete()
            Dispatchers.resetMain()
            server.stop(0)
        }
    }

    @Test
    fun previewForAPendingImageIsDecodedAfterConstructionNotDuringIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        Dispatchers.setMain(Dispatchers.Unconfined)
        val original = context.cacheDir.resolve("startup.png")
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val seed = AppState(context)
            seed.acceptQr("""{"instance_name":"synchronice-1234abcd","port":${server.address.port},"token":"0123456789abcdef0123456789abcdef"}""")
            seed.knownAddress.save("127.0.0.1")
            seed.queueFile(original)
            original.delete()

            val queuedPreviewWork = ArrayDeque<Runnable>()
            val restored = AppState(context, previewExecutor = Executor { queuedPreviewWork.addLast(it) })
            assertNull(restored.messages.single().preview, "the decode must not run on the constructing thread")
            while (queuedPreviewWork.isNotEmpty()) queuedPreviewWork.removeFirst().run()
            assertEquals(Color.RED, assertNotNull(restored.messages.single().preview).getPixel(0, 0))
        } finally {
            original.delete()
            Dispatchers.resetMain()
            server.stop(0)
        }
    }
}
