package synchronice.protocolclient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `escape JSON invalido e recusado mesmo em campo extra`() {
        val malformed = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef","extra":"\q"}"""

        assertFailsWith<IllegalArgumentException> {
            parsePairingQr(malformed)
        }
    }

    @Test
    fun `escape unicode sem quatro digitos hex e recusado mesmo em campo extra`() {
        for (escape in listOf("""\u-001""", """\u+041""")) {
            val malformed = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef","extra":"$escape"}"""

            assertFailsWith<IllegalArgumentException>(escape) {
                parsePairingQr(malformed)
            }
        }
    }

    @Test
    fun `numero JSON invalido e recusado mesmo em campo extra`() {
        val malformed = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef","extra":01}"""

        assertFailsWith<IllegalArgumentException> {
            parsePairingQr(malformed)
        }
    }

    @Test
    fun `controle JSON sem escape e recusado mesmo em campo extra`() {
        val control = 1.toChar()
        val malformed = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef","extra":"$control"}"""

        assertFailsWith<IllegalArgumentException> {
            parsePairingQr(malformed)
        }
    }

    @Test
    fun `whitespace fora do JSON e recusado no QR`() {
        val valid = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef"}"""
        val verticalTab = 11.toChar()

        for (malformed in listOf(
            valid.replace("\"token\"", "$verticalTab\"token\""),
            valid.replace("47820,", "47820$verticalTab,"),
        )) {
            assertFailsWith<IllegalArgumentException> { parsePairingQr(malformed) }
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
                parsePairingQr("""{"instance_name":"synchronice-12345678","port":$port,"token":"0123456789abcdef0123456789abcdef"}""")
            }
        }
    }

    private val base = """"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef""""

    @Test
    fun `QR com ip expoe o IPv4`() {
        assertEquals("192.168.18.12", parsePairingQr("""{$base,"ip":"192.168.18.12"}""").ip)
    }

    @Test
    fun `QR sem ip continua valido com IP nulo`() {
        assertNull(parsePairingQr("""{$base}""").ip)
    }

    @Test
    fun `ip malformado e ignorado e nao derruba o Par`() {
        for (ip in listOf("abc", "999.1.1.1", "192.168.1", "192.168.1.1.1", "::1", "", "192.168.1.1 ")) {
            val par = parsePairingQr("""{$base,"ip":"$ip"}""")
            assertNull(par.ip, "ip=$ip")
            assertEquals("synchronice-12345678", par.instanceName)
        }
    }

    @Test
    fun `o ip nao entra na identidade do Par`() {
        assertEquals(
            parsePairingQr("""{$base,"ip":"192.168.18.12"}"""),
            parsePairingQr("""{$base,"ip":"10.0.0.5"}"""),
        )
    }
}
