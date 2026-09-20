package synchronice.protocolclient

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TOKEN = "segredo-de-teste"

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun fakeServer(port: Int, path: String, handle: (HttpExchange) -> Unit): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext(path) { exchange -> handle(exchange) }
    server.start()
    return server
}

private fun client(port: Int) = ProtocolClient(
    pairing = PairingInfo("synchronice-nao-importa", port, TOKEN),
    resolver = InstanceResolver { "127.0.0.1" },
    knownAddress = InMemoryKnownAddressStore(),
)

class ProtocolClientTest {

    @Test
    fun `envia texto com o token do Par`() {
        val port = freePort()
        val requests = mutableListOf<Pair<String?, String>>()
        val server = fakeServer(port, "/items/text") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(exchange.requestHeaders.getFirst("Authorization") to body)
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            client(port).sendText("oi")

            assertEquals(listOf<Pair<String?, String>>("Bearer $TOKEN" to "oi"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `listen entrega Itens em ordem`() {
        val port = freePort()
        val server = fakeServer(port, "/listen") { exchange ->
            val body = "{\"id\":1,\"at_ms\":100,\"kind\":\"text\",\"text\":\"primeiro\"}\n" +
                "{\"id\":2,\"at_ms\":200,\"kind\":\"file\",\"name\":\"foto.png\",\"size\":42}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val items = client(port).listen()

            assertEquals(
                listOf(
                    Item.Text(1, 100, "primeiro"),
                    Item.FileMeta(2, 200, "foto.png", 42),
                ),
                items,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `servidor indisponivel deixa o Item Pendente e envia ao reconectar`() {
        val port = freePort()
        val client = client(port)

        client.sendText("mensagem enquanto o PC estava fora")
        assertTrue(client.hasPending())

        val requests = mutableListOf<String>()
        val server = fakeServer(port, "/items/text") { exchange ->
            requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            client.retryPending()

            assertFalse(client.hasPending())
            assertEquals(listOf("mensagem enquanto o PC estava fora"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resolucao falhando usa o ultimo IP conhecido`() {
        val port = freePort()
        val requests = mutableListOf<String?>()
        val server = fakeServer(port, "/items/text") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            val client = ProtocolClient(
                pairing = PairingInfo("synchronice-nao-importa", port, TOKEN),
                resolver = InstanceResolver { null },
                knownAddress = InMemoryKnownAddressStore().apply { save("127.0.0.1") },
            )

            client.sendText("oi")

            assertEquals(listOf<String?>("Bearer $TOKEN"), requests)
            assertFalse(client.hasPending())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendText drena o Pendente em ordem antes do texto novo`() {
        val port = freePort()
        val client = client(port)

        client.sendText("A")
        assertTrue(client.hasPending())

        val requests = mutableListOf<String>()
        val server = fakeServer(port, "/items/text") { exchange ->
            requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            client.sendText("B")

            assertEquals(listOf("A", "B"), requests)
            assertFalse(client.hasPending())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resposta nao-201 nao vira Pendente`() {
        val port = freePort()
        val server = fakeServer(port, "/items/text") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        try {
            val client = client(port)

            assertFailsWith<ProtocolRejectedException> { client.sendText("oi") }
            assertFalse(client.hasPending())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `listen decodifica os escapes b e f`() {
        val port = freePort()
        val server = fakeServer(port, "/listen") { exchange ->
            val body = "{\"id\":1,\"at_ms\":100,\"kind\":\"text\",\"text\":\"linha\\b\\ffim\"}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val items = client(port).listen()

            assertEquals(listOf(Item.Text(1, 100, "linha\bfim")), items)
        } finally {
            server.stop(0)
        }
    }
}
