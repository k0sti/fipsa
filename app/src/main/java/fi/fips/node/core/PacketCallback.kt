package fi.fips.node.core

/**
 * Callback interface invoked by the native Rust code to send outbound packets.
 * Implementations route packets to the appropriate transport.
 */
interface PacketCallback {
    /**
     * Called when the native node wants to send a packet.
     *
     * @param data Raw packet bytes
     * @param transportId Transport identifier to send on
     * @param remoteAddr Remote address string (e.g., "1.2.3.4:4000")
     */
    fun onPacket(data: ByteArray, transportId: Int, remoteAddr: String)
}
