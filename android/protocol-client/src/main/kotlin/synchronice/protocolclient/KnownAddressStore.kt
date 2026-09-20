package synchronice.protocolclient

/**
 * Guarda o último IP em que o PC foi encontrado, para usar quando a
 * resolução do nome de instância falhar.
 */
interface KnownAddressStore {
    fun get(): String?
    fun save(ip: String)
}

class InMemoryKnownAddressStore : KnownAddressStore {
    private var ip: String? = null
    override fun get(): String? = ip
    override fun save(ip: String) {
        this.ip = ip
    }
}
