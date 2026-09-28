package synchronice.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.PendingItem
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UnpairTest {
    private lateinit var context: Context
    private lateinit var queue: File
    private lateinit var payload: File

    @Before
    fun prepare() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pairing", 0).edit().clear().commit()
        queue = File(context.filesDir, "pending-items.txt")
        queue.delete()
        File(context.filesDir, "pending-items.txt.payloads").deleteRecursively()
        val source = File(context.cacheDir, "unpair.bin").apply { writeText("keep me") }
        val stored = FilePendingQueueStore(queue).enqueue(PendingItem.File(source.path, source.name)) as PendingItem.File
        payload = File(stored.path)
    }

    @After
    fun finish() { Dispatchers.resetMain() }

    @Test
    fun unpairDiscardsPersistedPairAndPayloadAndAcceptsAnotherQr() = runBlocking {
        val state = AppState(context)
        assertTrue(state.acceptQr(qr))
        state.unpair()
        assertNull(state.pairing)
        assertNull(AppState(context).pairing)
        assertFalse(state.hasPending())
        assertTrue(FilePendingQueueStore(queue).load().isEmpty())
        assertFalse(payload.exists())
        assertTrue(state.acceptQr(qr.replace("1234abcd", "5678abcd")))
        assertEquals("synchronice-5678abcd", AppState(context).pairing?.instanceName)
    }

    @Test
    fun failedPairWriteKeepsTheOldPairAndPendingPayloadTogether() = runBlocking {
        val preferences = context.getSharedPreferences("pairing", 0)
        val failingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "pairing") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }
        val state = AppState(failingContext)
        assertTrue(state.acceptQr(qr))
        val pair = state.pairing
        val pending = FilePendingQueueStore(queue).load()
        state.unpair()
        assertEquals(pair, state.pairing)
        assertEquals(pair, AppState(context).pairing)
        assertEquals(pending, FilePendingQueueStore(queue).load())
        assertTrue(state.hasPending())
        assertEquals("keep me", payload.readText())
        assertNotNull(state.connectionError)
        Unit
    }

    @Test
    fun failedPayloadCleanupCannotSendOldPendingItemsToANewPair() = runBlocking {
        val state = AppState(context)
        assertTrue(state.acceptQr(qr))
        assertTrue(payload.delete())
        assertTrue(payload.mkdir())
        val blocker = File(payload, "locked").apply { writeText("blocked") }
        state.unpair()
        val reopened = AppState(context)
        assertFalse(reopened.acceptQr(qr.replace("1234abcd", "5678abcd")))
        assertTrue(FilePendingQueueStore(queue).load().isNotEmpty())
        assertTrue(blocker.delete())
        reopened.unpair()
        assertFalse(reopened.hasPending())
        assertTrue(reopened.acceptQr(qr.replace("1234abcd", "5678abcd")))
        assertFalse(payload.exists())
    }

    @Test
    fun textQueuedDuringUnpairCannotReachANewPair() = queuedDuringUnpair(file = false)

    @Test
    fun fileQueuedDuringUnpairCannotReachANewPair() = queuedDuringUnpair(file = true)

    private fun queuedDuringUnpair(file: Boolean) = runBlocking {
        val preferences = context.getSharedPreferences("pairing", 0)
        val committingUnpair = CountDownLatch(1)
        val finishUnpairCommit = CountDownLatch(1)
        val controlledContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "pairing") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            private var clearing = false
                            override fun clear(): SharedPreferences.Editor {
                                clearing = true
                                editor.clear()
                                return this
                            }
                            override fun commit(): Boolean {
                                if (clearing) {
                                    committingUnpair.countDown()
                                    check(finishUnpairCommit.await(10, TimeUnit.SECONDS))
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val state = AppState(controlledContext)
        assertTrue(state.acceptQr(qr))
        val source = File(context.cacheDir, "old-pc.txt").apply { writeText("for the old PC only") }
        val id = state.addOutgoing("for the old PC only")
        val unpair = launch(Dispatchers.Default) { state.unpair() }
        try {
            assertTrue(committingUnpair.await(10, TimeUnit.SECONDS))
            // Capture the old client while unpair holds the outbound mutex.
            val enqueue = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (file) state.queueFile(source) else state.queueText(id, "for the old PC only")
                } catch (_: IOException) {
                    // A file rejected because its Par changed must never enter the queue.
                }
            }
            finishUnpairCommit.countDown()
            unpair.join()
            enqueue.join()
            assertNull(state.pairing)
            val newQr = qr.replace("1234abcd", "5678abcd")
                .replace("0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210")
            assertTrue(state.acceptQr(newQr))
            assertEquals(emptyList<PendingItem>(), FilePendingQueueStore(queue).load())
            assertFalse(state.client()!!.hasPending())
            assertTrue(File(context.filesDir, "pending-items.txt.payloads").listFiles().orEmpty().isEmpty())
        } finally {
            finishUnpairCommit.countDown()
        }
    }

    private val qr = """{"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef"}"""
}
