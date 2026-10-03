package synchronice.protocolclient

/** Um Item entregue pelo PC ao celular, na ordem em que chegou (spec, "Item"). */
sealed class Item {
    abstract val id: Long
    abstract val atMs: Long

    data class Text(override val id: Long, override val atMs: Long, val text: String) : Item()

    data class FileMeta(
        override val id: Long,
        override val atMs: Long,
        val name: String,
        val size: Long,
    ) : Item()

    /** Exclusão vinda do PC (`kind:"delete"`): controle, nunca vira bolha. Sai da fila com o ack. */
    data class Delete(
        override val id: Long,
        override val atMs: Long,
        val doPc: List<Long>,
        val doCelular: List<String>,
    ) : Item()
}
