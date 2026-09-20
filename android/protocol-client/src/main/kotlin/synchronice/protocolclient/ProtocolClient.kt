package synchronice.protocolclient

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fala o protocolo HTTP do PC (spec, `server.rs`) pelo lado do celular: envia
 * texto com o token do Par, escuta Itens e resolve o PC na rede. A UI, a
 * câmera de QR e o mDNS de verdade são a casca do app e ficam fora deste
 * módulo (ver "Adiado" em SYN-007).
 */
class ProtocolClient(
    private val pairing: PairingInfo,
    private val resolver: InstanceResolver,
    private val knownAddress: KnownAddressStore,
) {
    private val pending = ConcurrentLinkedQueue<String>()

    /**
     * Manda um Item de texto ao PC. Se o PC não estiver alcançável, o texto
     * fica Pendente e só sai na próxima chamada a [retryPending].
     */
    fun sendText(text: String) {
        if (!trySendText(text)) {
            pending.add(text)
        }
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /**
     * Tenta reenviar os Itens Pendentes, na ordem em que ficaram Pendentes;
     * para no primeiro que ainda falha, para nunca reordenar a fila.
     */
    fun retryPending() {
        while (true) {
            val next = pending.peek() ?: return
            if (!trySendText(next)) return
            pending.poll()
        }
    }

    private fun trySendText(text: String): Boolean {
        return try {
            val connection = open("/items/text", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val created = connection.responseCode == HttpURLConnection.HTTP_CREATED
            connection.disconnect()
            created
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Um ciclo de `/listen`: bloqueia até o PC ter ao menos um Item Pendente,
     * devolve os que vieram, em ordem. Reabrir para continuar escutando é
     * responsabilidade de quem chama.
     */
    fun listen(): List<Item> {
        val connection = open("/listen", "GET")
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        connection.disconnect()
        return body.lineSequence()
            .filter { it.isNotBlank() }
            .map(::parseItem)
            .toList()
    }

    private fun parseItem(line: String): Item {
        val fields = parseFlatJsonObject(line)
        val id = fields.getValue("id").toLong()
        val atMs = fields.getValue("at_ms").toLong()
        return when (val kind = fields.getValue("kind")) {
            "text" -> Item.Text(id, atMs, fields.getValue("text"))
            "file" -> Item.FileMeta(id, atMs, fields.getValue("name"), fields.getValue("size").toLong())
            else -> error("kind desconhecido em /listen: $kind")
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val connection = URI("http://${currentAddress()}:${pairing.port}$path").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        return connection
    }

    /**
     * IP atual do PC: tenta o resolvedor de nome de instância; se falhar,
     * cai para o último IP em que o PC foi encontrado.
     */
    private fun currentAddress(): String {
        val resolved = resolver.resolve(pairing.instanceName)
        if (resolved != null) {
            knownAddress.save(resolved)
            return resolved
        }
        return knownAddress.get()
            ?: throw IOException("PC não encontrado: resolução falhou e não há IP conhecido")
    }
}
