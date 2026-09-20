package synchronice.protocolclient

/** O Par lido do QR do PC (SYN-005): nome de instância, porta e token. */
data class PairingInfo(val instanceName: String, val port: Int, val token: String)
