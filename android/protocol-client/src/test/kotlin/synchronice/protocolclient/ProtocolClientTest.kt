package synchronice.protocolclient

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
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

private fun client(port: Int, queueStore: PendingQueueStore = InMemoryPendingQueueStore()) = ProtocolClient(
    pairing = PairingInfo("synchronice-nao-importa", port, TOKEN),
    resolver = InstanceResolver { "127.0.0.1" },
    knownAddress = InMemoryKnownAddressStore(),
    queueStore = queueStore,
)

private fun tempFile(bytes: ByteArray): File =
    File.createTempFile("synchronice-test", ".bin").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

class ProtocolClientTest {

    @Test
    fun `503 preserva texto e arquivo para retry e 401 preserva pendentes`() {
        val dir = kotlin.io.path.createTempDirectory("service-unavailable").toFile()
        val queue = File(dir, "pending.txt")
        val original = File(dir, "foto.bin").apply { writeText("foto") }
        val port = freePort()
        var status = 503
        val received = mutableListOf<String>()
        val server = fakeServer(port, "/items/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            if (status == 201) received.add(body)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        try {
            val sender = client(port, FilePendingQueueStore(queue))
            sender.sendFile(original)
            sender.sendText("texto")
            original.delete()
            val restored = client(port, FilePendingQueueStore(queue))
            status = 401
            assertEquals(401, assertFailsWith<ProtocolRejectedException> { restored.retryPending() }.code)
            assertTrue(restored.hasPending())
            status = 201
            restored.retryPending()
            assertEquals(listOf("foto", "texto"), received)
            assertFalse(restored.hasPending())
            // Também cobre 503 no envio imediato de texto, sem arquivo à frente.
            status = 503
            restored.sendText("mais tarde")
            assertTrue(restored.hasPending())
            status = 201
            restored.retryPending()
            assertEquals(listOf("foto", "texto", "mais tarde"), received)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `texto recebido pode ser confirmado e recusa de confirmacao sobe`() {
        val port = freePort()
        var confirmed = false
        var reject = false
        val server = fakeServer(port, "/") { exchange ->
            if (exchange.requestURI.path == "/listen") {
                val body = if (confirmed) "" else """{"id":7,"at_ms":100,"kind":"text","text":"oi"}"""
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                assertEquals("/outbox/7/ack", exchange.requestURI.path)
                assertEquals("POST", exchange.requestMethod)
                assertEquals("Bearer $TOKEN", exchange.requestHeaders.getFirst("Authorization"))
                if (!reject) confirmed = true
                exchange.sendResponseHeaders(if (reject) 401 else 204, -1)
                exchange.close()
            }
        }
        try {
            val receiver = client(port)
            val item = receiver.listen().single() as Item.Text
            receiver.acknowledgeText(item)
            assertEquals(emptyList(), receiver.listen())
            reject = true
            assertEquals(401, assertFailsWith<ProtocolRejectedException> { receiver.acknowledgeText(item) }.code)
        } finally { server.stop(0) }
    }

    @Test
    fun `falha ao persistir enqueue nao reaparece nem remove outro Item`() {
        val dir = kotlin.io.path.createTempDirectory("queue-failure").toFile()
        val queue = File(dir, "pending.txt")
        val blockedTemp = File(dir, "pending.txt.tmp").apply { mkdir() }
        val port = freePort()
        val sender = client(port, FilePendingQueueStore(queue))
        try {
            assertFailsWith<IOException> { sender.sendText("nao enfileirado") }
            assertFalse(sender.hasPending())
            assertTrue(blockedTemp.delete())
            sender.sendText("enfileirado")
            val received = mutableListOf<String>()
            val server = fakeServer(port, "/items/text") { exchange ->
                received.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                sender.retryPending()
                assertEquals(listOf("enfileirado"), received)
                assertFalse(client(port, FilePendingQueueStore(queue)).hasPending())
            } finally { server.stop(0) }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `falha ao persistir confirmacao preserva fila e copia do arquivo`() {
        val dir = kotlin.io.path.createTempDirectory("dequeue-failure").toFile()
        val queue = File(dir, "pending.txt")
        val original = File(dir, "original.bin").apply { writeText("bytes preservados") }
        val port = freePort()
        val sender = client(port, FilePendingQueueStore(queue))
        sender.sendFile(original)
        original.delete()
        val blockedTemp = File(dir, "pending.txt.tmp").apply { mkdir() }
        val received = mutableListOf<String>()
        val server = fakeServer(port, "/items/file") { exchange ->
            received.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            assertFailsWith<IOException> { sender.retryPending() }
            assertTrue(sender.hasPending())
            assertTrue(blockedTemp.delete())
            val restored = client(port, FilePendingQueueStore(queue))
            restored.retryPending()
            assertFalse(restored.hasPending())
            assertEquals(listOf("bytes preservados", "bytes preservados"), received)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nome de arquivo UTF8 usa cabecalho ASCII sem perder caracteres`() {
        val dir = kotlin.io.path.createTempDirectory("utf8-name").toFile()
        val file = File(dir, "relatório + 100%.pdf").apply { writeText("pdf") }
        val port = freePort()
        val names = mutableListOf<String?>()
        val server = fakeServer(port, "/items/file") { exchange ->
            names.add(exchange.requestHeaders.getFirst("X-File-Name-Utf8"))
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            client(port).sendFile(file)
            assertEquals(listOf<String?>("relat%C3%B3rio%20%2B%20100%25.pdf"), names)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

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
    fun `consulta presenca de listen usa HEAD autenticado e so confirma 200`() {
        val port = freePort()
        val status = java.util.concurrent.atomic.AtomicInteger(204)
        val requests = mutableListOf<Pair<String, String?>>()
        val server = fakeServer(port, "/listen") { exchange ->
            requests.add(exchange.requestMethod to exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(status.get(), -1)
            exchange.close()
        }
        try {
            val receiver = client(port)
            assertFalse(receiver.isListening())
            status.set(200)
            assertTrue(receiver.isListening())
            assertEquals(
                listOf<Pair<String, String?>>("HEAD" to "Bearer $TOKEN", "HEAD" to "Bearer $TOKEN"),
                requests,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `presenca de listen nao e confirmada em 401 ou falha de rede`() {
        val port = freePort()
        val server = fakeServer(port, "/listen") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        try {
            assertEquals(401, assertFailsWith<ProtocolRejectedException> { client(port).isListening() }.code)
        } finally {
            server.stop(0)
        }
        assertFalse(client(freePort()).isListening())
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
                queueStore = InMemoryPendingQueueStore(),
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

    @Test
    fun `envio de arquivo em stream emite progresso crescente ate 100`() {
        val port = freePort()
        val content = ByteArray(50_000) { (it % 256).toByte() }
        val file = tempFile(content)
        var receivedName: String? = null
        var receivedBytes: ByteArray? = null
        val server = fakeServer(port, "/items/file") { exchange ->
            receivedName = exchange.requestHeaders.getFirst("X-File-Name")
            receivedBytes = exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            val progress = mutableListOf<Int>()

            client(port).sendFile(file) { percent -> progress.add(percent) }

            assertEquals(file.name, receivedName)
            assertEquals(content.toList(), receivedBytes!!.toList())
            assertTrue(progress.isNotEmpty())
            assertEquals(progress.sorted(), progress)
            assertEquals(100, progress.last())
        } finally {
            server.stop(0)
            file.delete()
        }
    }

    @Test
    fun `falha no envio de arquivo deixa o Item Pendente`() {
        val port = freePort()
        val file = tempFile(byteArrayOf(1, 2, 3))
        try {
            val client = client(port)

            client.sendFile(file)

            assertTrue(client.hasPending())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `fila em disco e relida ao recriar o cliente e drenada em ordem`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        val fileToSend = tempFile(byteArrayOf(9, 8, 7))
        try {
            val client1 = client(port, FilePendingQueueStore(queueFile))
            client1.sendText("A")
            client1.sendFile(fileToSend)
            assertTrue(client1.hasPending())

            val requestOrder = mutableListOf<String>()
            val server = fakeServer(port, "/items/text") { exchange ->
                requestOrder.add("text:" + exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            server.createContext("/items/file") { exchange ->
                requestOrder.add("file:" + exchange.requestHeaders.getFirst("X-File-Name"))
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                val client2 = client(port, FilePendingQueueStore(queueFile))

                client2.retryPending()

                assertEquals(listOf("text:A", "file:${fileToSend.name}"), requestOrder)
                assertFalse(client2.hasPending())
            } finally {
                server.stop(0)
            }
        } finally {
            queueFile.delete()
            fileToSend.delete()
        }
    }

    @Test
    fun `arquivo Pendente sobrevive ao original ser apagado, porque a fila guarda uma copia`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        val originalFile = tempFile(byteArrayOf(4, 5, 6))
        val originalName = originalFile.name
        try {
            val client1 = client(port, FilePendingQueueStore(queueFile))
            client1.sendFile(originalFile)
            assertTrue(client1.hasPending())
            assertTrue(originalFile.delete())

            var receivedName: String? = null
            var receivedBytes: ByteArray? = null
            val server = fakeServer(port, "/items/file") { exchange ->
                receivedName = exchange.requestHeaders.getFirst("X-File-Name")
                receivedBytes = exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                val client2 = client(port, FilePendingQueueStore(queueFile))

                client2.retryPending()

                assertEquals(originalName, receivedName)
                assertEquals(listOf<Byte>(4, 5, 6), receivedBytes!!.toList())
                assertFalse(client2.hasPending())
            } finally {
                server.stop(0)
            }
        } finally {
            queueFile.delete()
        }
    }

    @Test
    fun `arquivo Pendente sem payload e descartado e nao trava a entrega do Item seguinte`() {
        val port = freePort()
        val client = client(port)
        val missingFile = tempFile(byteArrayOf(1, 2, 3))

        client.sendFile(missingFile)
        assertTrue(client.hasPending())
        assertTrue(missingFile.delete())
        client.sendText("depois")

        val requests = mutableListOf<String>()
        val server = fakeServer(port, "/items/text") { exchange ->
            requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        try {
            client.retryPending()

            assertEquals(listOf("depois"), requests)
            assertFalse(client.hasPending())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendFile nao lanca quando o arquivo sumiu antes do enfileiramento, e o Item e descartado`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        try {
            val client = client(port, FilePendingQueueStore(queueFile))
            client.sendText("primeiro")
            assertTrue(client.hasPending())

            val vanished = tempFile(byteArrayOf(1, 2, 3))
            assertTrue(vanished.delete())

            client.sendFile(vanished)

            val requests = mutableListOf<String>()
            val server = fakeServer(port, "/items/text") { exchange ->
                requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                client.retryPending()

                assertEquals(listOf("primeiro"), requests)
                assertFalse(client.hasPending())
            } finally {
                server.stop(0)
            }
        } finally {
            queueFile.delete()
        }
    }

    @Test
    fun `falha de IO que nao seja arquivo sumido durante o enfileiramento sobe para quem chamou`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        val payloadDir = File(queueFile.parentFile, "${queueFile.name}.payloads")
        val fileToSend = tempFile(byteArrayOf(1, 2, 3))
        try {
            // Um arquivo comum no lugar da pasta de payloads: o `mkdirs()` do
            // store não consegue criar o diretório, e a cópia falha por causa
            // que não é o arquivo original ter sumido.
            payloadDir.writeText("nao sou um diretorio")
            val client = client(port, FilePendingQueueStore(queueFile))

            assertFailsWith<IOException> { client.sendFile(fileToSend) }
        } finally {
            payloadDir.delete()
            queueFile.delete()
            fileToSend.delete()
        }
    }

    @Test
    fun `copia orfa em payloads e removida ao reconstruir o store`() {
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        val payloadDir = File(queueFile.parentFile, "${queueFile.name}.payloads")
        payloadDir.mkdirs()
        val orphan = File(payloadDir, "orfa-nao-referenciada.bin").apply { writeBytes(byteArrayOf(1)) }
        try {
            FilePendingQueueStore(queueFile)

            assertFalse(orphan.exists())
        } finally {
            queueFile.delete()
            payloadDir.deleteRecursively()
        }
    }

    @Test
    fun `fila em disco escapa e recupera barra, TAB, quebra de linha e retorno de carro`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        val text = "linha\\um\ttab\nquebra\rcarro"
        try {
            val client1 = client(port, FilePendingQueueStore(queueFile))
            client1.sendText(text)
            assertTrue(client1.hasPending())

            val requests = mutableListOf<String>()
            val server = fakeServer(port, "/items/text") { exchange ->
                requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                val client2 = client(port, FilePendingQueueStore(queueFile))

                client2.retryPending()

                assertEquals(listOf(text), requests)
                assertFalse(client2.hasPending())
            } finally {
                server.stop(0)
            }
        } finally {
            queueFile.delete()
        }
    }

    @Test
    fun `fila truncada no meio da ultima linha ainda da um cliente construivel com os Itens integros`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        try {
            val client1 = client(port, FilePendingQueueStore(queueFile))
            client1.sendText("item-um")
            client1.sendText("item-dois")
            assertTrue(client1.hasPending())

            val raw = queueFile.readText()
            queueFile.writeText(raw.substring(0, raw.length - 3))

            val requests = mutableListOf<String>()
            val server = fakeServer(port, "/items/text") { exchange ->
                requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
            try {
                val client2 = client(port, FilePendingQueueStore(queueFile))

                client2.retryPending()

                assertEquals(listOf("item-um"), requests)
                assertFalse(client2.hasPending())
            } finally {
                server.stop(0)
            }
        } finally {
            queueFile.delete()
        }
    }

    @Test
    fun `arquivo de fila corrompido nao impede construir o cliente`() {
        val port = freePort()
        val queueFile = File.createTempFile("synchronice-pendentes", ".txt").apply { deleteOnExit() }
        queueFile.writeText("linha sem formato valido\nT\ttexto valido\n")
        try {
            val client = client(port, FilePendingQueueStore(queueFile))

            assertTrue(client.hasPending())
        } finally {
            queueFile.delete()
        }
    }

    @Test
    fun `baixa Item de arquivo com progresso crescente, bytes identicos, e confirma o recebimento`() {
        val port = freePort()
        val content = ByteArray(50_000) { (it % 256).toByte() }
        val ackedIds = mutableListOf<String?>()
        val server = fakeServer(port, "/outbox/42/file") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/outbox/42/ack") { exchange ->
            ackedIds.add(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val destination = File.createTempFile("synchronice-download", ".bin").apply { deleteOnExit() }
        try {
            val progress = mutableListOf<Int>()

            client(port).downloadFile(Item.FileMeta(42, 100, "foto.png", content.size.toLong()), destination) { percent ->
                progress.add(percent)
            }

            assertEquals(content.toList(), destination.readBytes().toList())
            assertTrue(progress.isNotEmpty())
            assertEquals(progress.sorted(), progress)
            assertEquals(100, progress.last())
            assertEquals(listOf<String?>("Bearer $TOKEN"), ackedIds)
        } finally {
            server.stop(0)
            destination.delete()
        }
    }

    @Test
    fun `anuncio de arquivo vindo do listen e baixado pela mesma costura`() {
        val port = freePort()
        val content = ByteArray(1_000) { (it % 256).toByte() }
        val ackedIds = mutableListOf<String?>()
        val server = fakeServer(port, "/listen") { exchange ->
            val body = "{\"id\":99,\"at_ms\":300,\"kind\":\"file\",\"name\":\"relatorio.pdf\",\"size\":${content.size}}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/outbox/99/file") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/outbox/99/ack") { exchange ->
            ackedIds.add(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val destination = File.createTempFile("synchronice-download", ".bin").apply { deleteOnExit() }
        try {
            val announced = client(port).listen().single() as Item.FileMeta

            client(port).downloadFile(announced, destination)

            assertEquals(content.toList(), destination.readBytes().toList())
            assertEquals(listOf<String?>("Bearer $TOKEN"), ackedIds)
        } finally {
            server.stop(0)
            destination.delete()
        }
    }

    @Test
    fun `ack recusado pelo PC nao confirma o download`() {
        val port = freePort()
        val content = byteArrayOf(1, 2, 3)
        val server = fakeServer(port, "/outbox/42/file") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/outbox/42/ack") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        val destination = File.createTempFile("synchronice-download", ".bin").apply { deleteOnExit() }
        try {
            assertFailsWith<ProtocolRejectedException> {
                client(port).downloadFile(Item.FileMeta(42, 100, "foto.png", content.size.toLong()), destination) {}
            }
        } finally {
            server.stop(0)
            destination.delete()
        }
    }

    // O HttpServer de teste fecha a conexão sem erro nenhum do lado do
    // cliente quando o handler escreve menos bytes do que o Content-Length
    // anunciado — por isso a checagem de tamanho em downloadFile é a única
    // forma confiável de detectar a interrupção.
    private fun downloadInterrompido(configureServer: (HttpServer) -> Unit = {}, assertions: (File) -> Unit) {
        val port = freePort()
        val server = fakeServer(port, "/outbox/42/file") { exchange ->
            val partial = byteArrayOf(1, 2, 3)
            exchange.sendResponseHeaders(200, 10)
            exchange.responseBody.use { it.write(partial) }
        }
        configureServer(server)
        val destination = File.createTempFile("synchronice-download", ".bin").apply { deleteOnExit() }
        try {
            assertFailsWith<IOException> {
                client(port).downloadFile(Item.FileMeta(42, 100, "foto.png", 10), destination) {}
            }

            assertions(destination)
        } finally {
            server.stop(0)
            destination.delete()
        }
    }

    @Test
    fun `download interrompido nao marca o Item como completo e nao envia o ack`() {
        var acked = false

        downloadInterrompido(configureServer = { server ->
            server.createContext("/outbox/42/ack") { exchange ->
                acked = true
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }) {
            assertFalse(acked)
        }
    }

    @Test
    fun `download interrompido nao deixa bytes parciais em destination`() {
        downloadInterrompido { destination ->
            assertEquals(0L, destination.length())
        }
    }

    @Test
    fun `download interrompido apaga o arquivo parcial ponto part`() {
        downloadInterrompido { destination ->
            val partial = File(destination.absoluteFile.parentFile, "${destination.name}.part")

            assertFalse(partial.exists())
        }
    }

    @Test
    fun `404 no download do outbox e id inexistente ou ja confirmado, distinto de recusa`() {
        val port = freePort()
        val server = fakeServer(port, "/outbox/42/file") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val destination = File.createTempFile("synchronice-download", ".bin").apply { deleteOnExit() }
        try {
            assertFailsWith<ItemGoneException> {
                client(port).downloadFile(Item.FileMeta(42, 100, "foto.png", 10), destination) {}
            }
        } finally {
            server.stop(0)
            destination.delete()
        }
    }
}
