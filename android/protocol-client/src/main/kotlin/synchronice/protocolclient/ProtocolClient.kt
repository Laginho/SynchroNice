package synchronice.protocolclient

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** O PC recusou a requisição (ex.: token errado) — não é indisponibilidade. */
class ProtocolRejectedException(val code: Int) : Exception("PC recusou a requisição: HTTP $code")

private sealed class SendOutcome {
    object Success : SendOutcome()
    object Unreachable : SendOutcome()
    data class Rejected(val code: Int) : SendOutcome()
}

/**
 * Fala o protocolo HTTP do PC (spec, `server.rs`) pelo lado do celular: envia
 * texto e arquivo com o token do Par, escuta Itens e resolve o PC na rede. A
 * UI, a câmera de QR, o mDNS de verdade e o serviço em primeiro plano são a
 * casca do app e ficam fora deste módulo (ver "Adiado" em SYN-007).
 */
class ProtocolClient(
    private val pairing: PairingInfo,
    private val resolver: InstanceResolver,
    private val knownAddress: KnownAddressStore,
    private val queueStore: PendingQueueStore,
) {
    private val pending: MutableList<PendingItem> = queueStore.load().toMutableList()

    /**
     * Manda um Item de texto ao PC. Ao detectar o outro lado, drena o
     * Pendente em ordem antes deste texto — nunca fura a fila. Se o PC não
     * estiver alcançável, o texto entra no fim da fila; se o PC recusar a
     * requisição (ex.: token errado), lança [ProtocolRejectedException] em
     * vez de ficar Pendente, porque recusa não é indisponibilidade.
     */
    fun sendText(text: String) {
        retryPending()
        if (pending.isNotEmpty()) {
            enqueue(PendingItem.PendingText(text))
            return
        }
        when (val outcome = trySendText(text)) {
            SendOutcome.Success -> {}
            SendOutcome.Unreachable -> enqueue(PendingItem.PendingText(text))
            is SendOutcome.Rejected -> throw ProtocolRejectedException(outcome.code)
        }
    }

    /**
     * Manda um Item de arquivo ao PC em stream, chamando [onProgress] com o
     * percentual (0 a 100) enviado. Mesma política de fila que [sendText]:
     * drena o Pendente antes, nunca fura, e uma falha no envio (o PC ficar
     * indisponível a qualquer momento da transferência) deixa o arquivo
     * Pendente em vez de perder o Item.
     */
    fun sendFile(file: File, onProgress: (Int) -> Unit = {}) {
        retryPending()
        if (pending.isNotEmpty()) {
            enqueue(PendingItem.PendingFile(file.absolutePath, file.name))
            return
        }
        when (val outcome = trySendFile(file, file.name, onProgress)) {
            SendOutcome.Success -> {}
            SendOutcome.Unreachable -> enqueue(PendingItem.PendingFile(file.absolutePath, file.name))
            is SendOutcome.Rejected -> throw ProtocolRejectedException(outcome.code)
        }
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /**
     * Tenta reenviar os Itens Pendentes, na ordem em que ficaram Pendentes;
     * para no primeiro que ainda falha, para nunca reordenar a fila.
     */
    fun retryPending() {
        while (true) {
            val next = pending.firstOrNull() ?: return
            val outcome = when (next) {
                is PendingItem.PendingText -> trySendText(next.text)
                is PendingItem.PendingFile -> trySendFile(File(next.path), next.name) {}
            }
            when (outcome) {
                SendOutcome.Success -> dequeueFirst()
                SendOutcome.Unreachable -> return
                is SendOutcome.Rejected -> throw ProtocolRejectedException(outcome.code)
            }
        }
    }

    private fun enqueue(item: PendingItem) {
        pending.add(item)
        queueStore.save(pending)
    }

    private fun dequeueFirst() {
        pending.removeAt(0)
        queueStore.save(pending)
    }

    private fun trySendText(text: String): SendOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("/items/text", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_CREATED) SendOutcome.Success else SendOutcome.Rejected(code)
        } catch (_: IOException) {
            SendOutcome.Unreachable
        } finally {
            connection?.disconnect()
        }
    }

    private fun trySendFile(file: File, displayName: String, onProgress: (Int) -> Unit): SendOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("/items/file", "POST")
            connection.doOutput = true
            connection.setRequestProperty("X-File-Name", displayName)
            val total = file.length()
            connection.setFixedLengthStreamingMode(total)
            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress(if (total > 0) ((sent * 100) / total).toInt() else 100)
                    }
                }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_CREATED) {
                onProgress(100)
                SendOutcome.Success
            } else {
                SendOutcome.Rejected(code)
            }
        } catch (_: IOException) {
            SendOutcome.Unreachable
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Um ciclo de `/listen`: bloqueia até o PC ter ao menos um Item Pendente,
     * devolve os que vieram, em ordem. Reabrir para continuar escutando é
     * responsabilidade de quem chama.
     */
    fun listen(): List<Item> {
        val connection = open("/listen", "GET")
        val body = try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw ProtocolRejectedException(code)
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
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
