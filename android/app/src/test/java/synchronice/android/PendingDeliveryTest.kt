package synchronice.android

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingDeliveryTest {
    @Test
    fun confirmedPrefixIsDeliveredWhileLaterTextRemainsPending() {
        val messages = mutableListOf(
            ChatMessage(1, "same", true, Delivery.PENDING),
            ChatMessage(2, "same", true, Delivery.PENDING),
            ChatMessage(3, "not submitted yet", true, Delivery.PENDING),
        )
        val submitted = ArrayDeque(listOf(1L, 2L))

        reconcilePendingDelivery(messages, submitted, remainingTexts = 1)

        assertEquals(listOf(Delivery.DELIVERED, Delivery.PENDING, Delivery.PENDING), messages.map { it.delivery })
        assertEquals(listOf(2L), submitted.toList())
    }
}
