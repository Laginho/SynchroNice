package synchronice.protocolclient

/**
 * Resolve o nome de instância do Par para o IP atual do PC. A implementação
 * real (mDNS) é a casca do app Android e fica fora do escopo deste módulo
 * (ver "Adiado" em SYN-007) — aqui é só o contrato que [ProtocolClient] usa.
 * `null` quando a resolução falha.
 */
fun interface InstanceResolver {
    fun resolve(instanceName: String): String?
}
