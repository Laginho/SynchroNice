package synchronice.protocolclient

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** O PC recusou a requisição (ex.: token errado) — não é indisponibilidade. */
class ProtocolRejectedException(val code: Int) : Exception("PC recusou a requisição: HTTP $code")

/**
 * O Item não está mais na fila do PC: o id nunca existiu, ou o `ack` de uma
 * entrega anterior já o confirmou e o PC reentregou por outro motivo
 * (`server.rs`). Não é recusa — não há nada para baixar.
 */
class ItemGoneException(val id: Long) : Exception("Item $id não está mais na fila do PC")

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
     * estiver alcançável ou responder 503, o texto entra no fim da fila; se o PC recusar a
     * requisição (ex.: token errado), lança [ProtocolRejectedException] em
     * vez de ficar Pendente, porque recusa não é indisponibilidade.
     */
    fun sendText(text: String) {
        send(makePending = { PendingItem.Text(text) }, attempt = { trySendText(text) })
    }

    /**
     * Manda um Item de arquivo ao PC em stream, chamando [onProgress] com o
     * percentual (0 a 100) enviado. Mesma política de fila que [sendText]:
     * drena o Pendente antes, nunca fura, e uma falha no envio (o PC ficar
     * indisponível a qualquer momento da transferência) deixa o arquivo
     * Pendente em vez de perder o Item.
     *
     * Uma exceção, e só ela: se o arquivo original já tiver sumido quando a
     * cópia interna for feita, não há o que guardar e o Item é descartado em
     * silêncio (ver [PendingQueueStore.enqueue]). Qualquer outra falha ao
     * guardar a cópia — disco cheio, permissão negada — sobe para quem
     * chamou, em vez de virar perda silenciosa.
     */
    fun sendFile(file: File, onProgress: (Int) -> Unit = {}) {
        send(
            makePending = { PendingItem.File(file.absolutePath, file.name) },
            attempt = { trySendFile(file, file.name, onProgress) },
        )
    }

    /**
     * Caminho único de envio: drena o Pendente antes, nunca fura a fila; se
     * ainda houver Pendente à frente ou o PC estiver indisponível, o Item
     * novo entra no fim da fila. [makePending] é chamado no máximo uma vez.
     */
    private fun send(makePending: () -> PendingItem, attempt: () -> SendOutcome) {
        retryPending()
        if (pending.isNotEmpty()) {
            enqueue(makePending())
            return
        }
        when (val outcome = attempt()) {
            SendOutcome.Success -> {}
            SendOutcome.Unreachable -> enqueue(makePending())
            is SendOutcome.Rejected -> throw ProtocolRejectedException(outcome.code)
        }
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /**
     * Tenta reenviar os Itens Pendentes, na ordem em que ficaram Pendentes;
     * para no primeiro que ainda falha, para nunca reordenar a fila. Um
     * arquivo Pendente cujo payload sumiu é descartado em vez de travar a
     * fila para sempre.
     */
    fun retryPending() {
        while (true) {
            val next = pending.firstOrNull() ?: return
            if (next is PendingItem.File && !File(next.path).exists()) {
                dequeueFirst()
                continue
            }
            val outcome = when (next) {
                is PendingItem.Text -> trySendText(next.text)
                is PendingItem.File -> trySendFile(File(next.path), next.name) {}
            }
            when (outcome) {
                SendOutcome.Success -> dequeueFirst()
                SendOutcome.Unreachable -> return
                is SendOutcome.Rejected -> throw ProtocolRejectedException(outcome.code)
            }
        }
    }

    private fun enqueue(item: PendingItem) {
        val stored = queueStore.enqueue(item) ?: return
        pending.add(stored)
    }

    private fun dequeueFirst() {
        queueStore.dequeueFirst()
        pending.removeAt(0)
    }

    private fun trySendText(text: String): SendOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("/items/text", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_CREATED -> SendOutcome.Success
                HttpURLConnection.HTTP_UNAVAILABLE -> SendOutcome.Unreachable
                else -> SendOutcome.Rejected(code)
            }
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
            connection.setRequestProperty("X-File-Name-Utf8", URLEncoder.encode(displayName, Charsets.UTF_8).replace("+", "%20"))
            if (displayName.all { it in ' '..'~' }) {
                connection.setRequestProperty("X-File-Name", displayName)
            }
            val total = file.length()
            connection.setFixedLengthStreamingMode(total)
            file.inputStream().use { input ->
                connection.outputStream.use { output -> pump(input, output, total, onProgress) }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_CREATED) {
                onProgress(100)
                SendOutcome.Success
            } else if (code == HttpURLConnection.HTTP_UNAVAILABLE) {
                SendOutcome.Unreachable
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
     * Baixa um Item de arquivo ainda Pendente na fila de saída do PC (`GET
     * /outbox/{id}/file`) para [destination] em stream, chamando [onProgress]
     * com o percentual (0 a 100) recebido. Grava num arquivo temporário ao
     * lado de [destination] e só move para o destino final depois de conferir
     * o tamanho — uma interrupção no meio do download não deixa bytes
     * parciais parecendo um arquivo completo em [destination]. Confirma o
     * recebimento (`POST /outbox/{id}/ack`) só depois disso — só o `ack` tira
     * o Item da fila do PC, então uma interrupção propaga a exceção sem
     * chamar o `ack`, e o PC reentrega no próximo `/listen`. Um download que
     * fecha antes de entregar [Item.FileMeta.size] bytes lança [IOException]
     * em vez de aceitar bytes de menos como completo. Um `404` (`server.rs`:
     * id não existe ou já foi confirmado) lança [ItemGoneException] em vez de
     * [ProtocolRejectedException] — não é recusa, é o Item que o PC já dá por
     * resolvido.
     */
    fun downloadFile(item: Item.FileMeta, destination: File, onProgress: (Int) -> Unit = {}) {
        val connection = open("/outbox/${item.id}/file", "GET")
        val partial = File(destination.absoluteFile.parentFile, "${destination.name}.part")
        try {
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) throw ItemGoneException(item.id)
                if (code != HttpURLConnection.HTTP_OK) throw ProtocolRejectedException(code)
                val received = connection.inputStream.use { input ->
                    partial.outputStream().use { output -> pump(input, output, item.size, onProgress) }
                }
                if (received != item.size) {
                    throw IOException("download incompleto: esperado ${item.size} bytes, recebido $received")
                }
            } finally {
                connection.disconnect()
            }
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            onProgress(100)
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        ack(item.id)
    }

    /** Copia [input] para [output] em blocos de 8 KB, reportando o percentual de [total] a [onProgress]. */
    private fun pump(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit): Long {
        val buffer = ByteArray(8192)
        var transferred = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            transferred += read
            onProgress(if (total > 0) ((transferred * 100) / total).toInt() else 100)
        }
        return transferred
    }

    /** Confirma texto depois de o chamador aceitá-lo na Sessão. Pode repetir após falha de rede. */
    fun acknowledgeText(item: Item.Text) = ack(item.id)

    private fun ack(id: Long) {
        val connection = open("/outbox/$id/ack", "POST")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_NO_CONTENT) throw ProtocolRejectedException(code)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Um ciclo de `/listen`: bloqueia até o PC ter ao menos um Item Pendente,
     * devolve os que vieram, em ordem. Reabrir para continuar escutando é
     * responsabilidade de quem chama.
     */
    fun listen(): List<Item> {
        // ponytail: a 35s idle timeout bounds reconnection after a silent IP change; a network callback could shorten it.
        val connection = open("/listen", "GET").apply { readTimeout = 35_000 }
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

    /** Confirma a presença do cliente pela escuta GET aberta no outro lado. */
    fun isListening(): Boolean {
        val connection = open("/listen", "HEAD").apply { readTimeout = 5_000 }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> true
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw ProtocolRejectedException(status)
                else -> false
            }
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
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
