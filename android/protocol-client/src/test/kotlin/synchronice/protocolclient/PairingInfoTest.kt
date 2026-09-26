package synchronice.protocolclient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingInfoTest {
    @Test
    fun `le QR do PC vira um Par`() {
        val json = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef"}"""

        assertEquals(
            PairingInfo("synchronice-12345678", 47820, "0123456789abcdef0123456789abcdef"),
            parsePairingQr(json),
        )
    }

    @Test
    fun `JSON quebrado e recusado`() {
        val malformed = """{"instance_name":"synchronice-12345678","port":47820,"token":"x"}""".dropLast(1)
        assertFailsWith<IllegalArgumentException> {
            parsePairingQr(malformed)
        }
    }

    @Test
    fun `campo ausente e recusado`() {
        assertFailsWith<IllegalArgumentException> {
            parsePairingQr("""{"instance_name":"synchronice-12345678","port":47820}""")
        }
    }

    @Test
    fun `porta fora da faixa e recusada`() {
        for (port in listOf(0, 65536)) {
            assertFailsWith<IllegalArgumentException> {
                parsePairingQr("""{"instance_name":"synchronice-12345678","port":$port,"token":"x"}""")
            }
        }
    }
}
