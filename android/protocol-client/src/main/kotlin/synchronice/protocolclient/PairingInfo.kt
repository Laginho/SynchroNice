package synchronice.protocolclient

/** O Par lido do QR do PC (SYN-005): nome de instância, porta e token. */
data class PairingInfo(val instanceName: String, val port: Int, val token: String) {
    /** IPv4 do PC no QR, se veio. Fora do construtor: não entra em equals/hashCode, não é identidade do Par. */
    var ip: String? = null
        internal set
}

/** Lê o JSON do QR do PC; conteúdo malformado ou incompleto não vira Par. */
fun parsePairingQr(json: String): PairingInfo {
    val fields = try {
        parseFlatJsonObject(json)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("QR de pareamento inválido", error)
    }
    val instanceName = fields["instance_name"]
        ?.takeIf { it.matches(Regex("synchronice-[0-9a-fA-F]{8}")) }
        ?: throw IllegalArgumentException("nome de instância inválido")
    val port = fields["port"]?.toIntOrNull()
        ?: throw IllegalArgumentException("porta inválida")
    require(port in 1..65535) { "porta fora da faixa" }
    val token = fields["token"]
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
        ?: throw IllegalArgumentException("token inválido")

    return PairingInfo(instanceName, port, token).also { pairing ->
        // Opcional: só um IPv4 válido vale; qualquer outra coisa é ignorada, nunca rejeita o Par.
        pairing.ip = fields["ip"]?.takeIf { it.matches(IPV4) }
    }
}

private val IPV4 = Regex("""(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""")
