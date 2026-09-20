package synchronice.protocolclient

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Onde a fila de Pendentes vive entre chamadas — e, na implementação de disco, entre reinícios do app. */
interface PendingQueueStore {
    fun load(): List<PendingItem>

    /**
     * Põe o Item no fim da fila; devolve o Item como ele deve ser mantido em
     * memória dali em diante (a implementação de disco troca o caminho de um
     * arquivo pelo da cópia interna, para sobreviver ao original sumir), ou
     * `null` se o Item não pôde ser preservado (ex.: o arquivo original já
     * sumiu quando a cópia foi tentada) — quem chama não o mantém em memória.
     */
    fun enqueue(item: PendingItem): PendingItem?

    /** Tira da fila o Item que estava na cabeça e libera o que a implementação tiver reservado para ele. */
    fun dequeueFirst()
}

class InMemoryPendingQueueStore : PendingQueueStore {
    override fun load(): List<PendingItem> = emptyList()

    override fun enqueue(item: PendingItem): PendingItem = item

    override fun dequeueFirst() {}
}

/**
 * Grava a fila em disco, uma linha por Item, para sobreviver ao app ser
 * fechado e reaberto (critério 6). Formato interno, nunca lido por outra
 * parte do protocolo: um caractere de tipo, TAB, campos com `\`, TAB, `\r` e
 * quebra de linha escapados — para um texto Pendente poder conter qualquer um
 * deles sem quebrar o parsing por linha. Um arquivo Pendente é copiado para
 * uma pasta interna ao entrar na fila (a spec proíbe guardar só o caminho: um
 * arquivo temporário, movido ou de uma URI de Compartilhar não sobrevive até
 * a entrega) e a cópia é apagada quando o Item sai da fila. Uma linha que não
 * decodifica é ignorada, para uma fila corrompida não impedir o cliente de
 * construir.
 */
class FilePendingQueueStore(private val file: File) : PendingQueueStore {
    private val payloadDir = File(file.parentFile ?: File("."), "${file.name}.payloads")
    private val items: MutableList<PendingItem> =
        if (file.exists()) decodeLines(file.readText()).toMutableList()
        else mutableListOf()

    init {
        sweepOrphanPayloads()
    }

    override fun load(): List<PendingItem> = items.toList()

    override fun enqueue(item: PendingItem): PendingItem? {
        val stored = if (item is PendingItem.File) copyIntoPayloadDir(item) ?: return null else item
        items.add(stored)
        persist()
        return stored
    }

    override fun dequeueFirst() {
        if (items.isEmpty()) return
        val removed = items.removeAt(0)
        if (removed is PendingItem.File) File(removed.path).delete()
        persist()
    }

    /** `null` se o arquivo original já tiver sumido — o Item é descartado em vez de estourar para quem chamou. */
    private fun copyIntoPayloadDir(item: PendingItem.File): PendingItem.File? {
        payloadDir.mkdirs()
        val copy = File(payloadDir, "${UUID.randomUUID()}-${item.name}")
        return try {
            File(item.path).copyTo(copy, overwrite = true)
            item.copy(path = copy.absolutePath)
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Uma cópia em [payloadDir] sem Item na fila que a referencie é órfã —
     * sobrou de um crash entre `copyIntoPayloadDir` e `persist()`. Varrida ao
     * construir o store, para não acumular para sempre.
     */
    private fun sweepOrphanPayloads() {
        val referenced = items.filterIsInstance<PendingItem.File>().mapTo(HashSet()) { File(it.path).absolutePath }
        payloadDir.listFiles()?.forEach { candidate ->
            if (candidate.absolutePath !in referenced) candidate.delete()
        }
    }

    /**
     * Escreve num arquivo temporário e troca pelo arquivo final com um
     * rename atômico, para um crash no meio da escrita nunca deixar a fila
     * (que existe justamente para sobreviver a isso) truncada.
     */
    private fun persist() {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile ?: File("."), "${file.name}.tmp")
        tempFile.writeText(items.joinToString("") { encodeLine(it) + "\n" })
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun encodeLine(item: PendingItem): String = when (item) {
        is PendingItem.Text -> "T\t${escape(item.text)}"
        is PendingItem.File -> "F\t${escape(item.path)}\t${escape(item.name)}"
    }

    /**
     * A última linha só é confiável se o arquivo termina em quebra de linha
     * — do contrário ela é a ponta de uma escrita interrompida (o `persist`
     * antigo, sem arquivo temporário, podia deixar exatamente isso) e um
     * fragmento truncado pode decodificar como um Item válido só que errado.
     * Descartá-la mantém "Itens íntegros" em vez de aceitar dado corrompido.
     */
    private fun decodeLines(raw: String): List<PendingItem> {
        val complete = if (raw.endsWith("\n")) raw else raw.substringBeforeLast("\n", "")
        return complete.split("\n").filter { it.isNotEmpty() }.mapNotNull(::decodeLineOrNull)
    }

    private fun decodeLineOrNull(line: String): PendingItem? {
        val fields = line.split("\t").map(::unescape)
        return when {
            fields.size >= 2 && fields[0] == "T" -> PendingItem.Text(fields[1])
            fields.size >= 3 && fields[0] == "F" -> PendingItem.File(fields[1], fields[2])
            else -> null
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(c)
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}
