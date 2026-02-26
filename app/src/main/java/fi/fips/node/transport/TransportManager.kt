package fi.fips.node.transport

import android.content.Context
import android.util.Log
import fi.fips.node.core.FipsCore
import fi.fips.node.core.PacketCallback

/**
 * Manages all transport instances, handles lifecycle, and routes
 * packets between transports and the native FipsCore.
 */
class TransportManager(
    private val context: Context,
    private val core: FipsCore,
) : PacketCallback, PacketListener {

    companion object {
        private const val TAG = "TransportManager"
    }

    @PublishedApi
    internal val transports = mutableMapOf<Int, FipsTransport>()

    val allTransports: List<FipsTransport>
        get() = transports.values.toList()

    init {
        // Register all transports
        register(UdpTransport())
        register(BleTransport(context))
        register(WifiDirectTransport(context))
        register(NfcTransport(context))
        register(AudioTransport())
        register(VideoTransport(context))
    }

    private fun register(transport: FipsTransport) {
        transport.setPacketListener(this)
        transports[transport.transportId] = transport
    }

    fun getTransport(id: Int): FipsTransport? = transports[id]

    inline fun <reified T : FipsTransport> getTransportByType(): T? {
        return transports.values.filterIsInstance<T>().firstOrNull()
    }

    /**
     * Start a specific transport.
     */
    fun startTransport(id: Int) {
        transports[id]?.let { transport ->
            if (transport.isAvailable) {
                transport.start()
                Log.i(TAG, "Started transport: ${transport.transportType.displayName}")
            } else {
                Log.w(TAG, "Transport not available: ${transport.transportType.displayName}")
            }
        }
    }

    /**
     * Stop a specific transport.
     */
    fun stopTransport(id: Int) {
        transports[id]?.let { transport ->
            transport.stop()
            Log.i(TAG, "Stopped transport: ${transport.transportType.displayName}")
        }
    }

    /**
     * Start all available transports.
     */
    fun startAll() {
        for (transport in transports.values) {
            if (transport.isAvailable) {
                transport.start()
            }
        }
    }

    /**
     * Stop all transports.
     */
    fun stopAll() {
        for (transport in transports.values) {
            transport.stop()
        }
    }

    // --- PacketCallback (from native -> transport) ---

    override fun onPacket(data: ByteArray, transportId: Int, remoteAddr: String) {
        transports[transportId]?.send(data, remoteAddr)
    }

    // --- PacketListener (from transport -> native) ---

    override fun onPacketReceived(data: ByteArray, transportId: Int, remoteAddr: String) {
        core.injectPacket(data, transportId, remoteAddr)
    }
}
