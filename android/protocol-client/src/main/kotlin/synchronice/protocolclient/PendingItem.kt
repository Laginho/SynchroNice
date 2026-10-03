package synchronice.protocolclient

import java.util.UUID

/** Um Item ainda não entregue, esperando o PC aparecer (spec, "Pendente"). */
sealed class PendingItem {
    abstract val id: String

    data class Text(val text: String, override val id: String = UUID.randomUUID().toString()) : PendingItem()

    data class File(val path: String, val name: String, override val id: String = UUID.randomUUID().toString()) : PendingItem()

    /** Apagar tudo no celular (SYN-019): ids de protocolo dos Itens apagados, saindo como `POST /items/delete`. */
    data class Delete(
        val doPc: List<Long>,
        val doCelular: List<String>,
        override val id: String = UUID.randomUUID().toString(),
    ) : PendingItem()
}
