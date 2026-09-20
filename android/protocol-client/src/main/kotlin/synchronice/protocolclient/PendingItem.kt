package synchronice.protocolclient

/** Um Item ainda não entregue, esperando o PC aparecer (spec, "Pendente"). */
sealed class PendingItem {
    data class Text(val text: String) : PendingItem()

    data class File(val path: String, val name: String) : PendingItem()
}
