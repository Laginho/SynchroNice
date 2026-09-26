package synchronice.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceLifecycleTest {
    @Test
    fun resetsPresenceAtCycleStartAndAfterCancellation() = runBlocking {
        var online = true
        var onlineAtStart = true
        val entered = CompletableDeferred<Unit>()
        val cycle = launch {
            clearPresenceDuringLifecycleCycle({ online = it }) {
                onlineAtStart = online
                online = true
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        assertFalse(onlineAtStart)
        assertTrue(online)
        cycle.cancelAndJoin()
        assertFalse(online)
    }
}
