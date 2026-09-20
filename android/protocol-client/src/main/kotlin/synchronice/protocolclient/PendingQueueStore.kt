package synchronice.protocolclient

import java.io.File

/** Onde a fila de Pendentes vive entre chamadas — e, na implementação de disco, entre reinícios do app. */
interface PendingQueueStore {
    fun load(): List<PendingItem>

    fun save(items: List<PendingItem>)
}

class InMemoryPendingQueueStore : PendingQueueStore {
    private var items: List<PendingItem> = emptyList()

    override fun load(): List<PendingItem> = items

    override fun save(items: List<PendingItem>) {
        this.items = items
    }
}

/**
 * Grava a fila em disco, uma linha por Item, para sobreviver ao app ser
 * fechado e reaberto (critério 6). Formato interno, nunca lido por outra
 * parte do protocolo: um caractere de tipo, TAB, campos com `\`, TAB e quebra
 * de linha escapados — para um texto Pendente poder conter qualquer um dos
 * três sem quebrar o parsing por linha.
 */
class FilePendingQueueStore(private val file: File) : PendingQueueStore {
    override fun load(): List<PendingItem> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotEmpty() }
            .map(::decodeLine)
    }

    override fun save(items: List<PendingItem>) {
        file.parentFile?.mkdirs()
        file.writeText(items.joinToString("") { encodeLine(it) + "\n" })
    }

    private fun encodeLine(item: PendingItem): String = when (item) {
        is PendingItem.PendingText -> "T\t${escape(item.text)}"
        is PendingItem.PendingFile -> "F\t${escape(item.path)}\t${escape(item.name)}"
    }

    private fun decodeLine(line: String): PendingItem {
        val fields = line.split("\t").map(::unescape)
        return when (fields[0]) {
            "T" -> PendingItem.PendingText(fields[1])
            "F" -> PendingItem.PendingFile(fields[1], fields[2])
            else -> error("linha desconhecida na fila de Pendentes: $line")
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

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
