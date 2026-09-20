package synchronice.protocolclient

/** Um Item ainda não entregue, esperando o PC aparecer (spec, "Pendente"). */
sealed class PendingItem {
    data class PendingText(val text: String) : PendingItem()

    data class PendingFile(val path: String, val name: String) : PendingItem()
}
