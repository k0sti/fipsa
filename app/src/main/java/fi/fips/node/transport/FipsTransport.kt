package fi.fips.node.transport

/**
 * Transport state.
 */
enum class TransportState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Transport type identifier.
 */
enum class TransportType(val displayName: String) {
    UDP("UDP"),
    BLE("Bluetooth LE"),
    WIFI_DIRECT("Wi-Fi Direct"),
    NFC("NFC"),
    AUDIO("Audio"),
    VIDEO("Video/QR")
}

/**
 * Listener for packets received by a transport.
 */
interface PacketListener {
    fun onPacketReceived(data: ByteArray, transportId: Int, remoteAddr: String)
}

/**
 * Common interface for all FIPS transport modules.
 * Each transport handles one communication channel (UDP, BLE, etc.).
 */
interface FipsTransport {
    /** Unique identifier for this transport instance. */
    val transportId: Int

    /** Type of transport. */
    val transportType: TransportType

    /** Whether this transport is available on the current device. */
    val isAvailable: Boolean

    /** Current state of the transport. */
    val state: TransportState

    /** Start the transport. */
    fun start()

    /** Stop the transport. */
    fun stop()

    /** Send a packet to the given remote address. */
    fun send(data: ByteArray, remoteAddr: String)

    /** Set the listener for received packets. */
    fun setPacketListener(listener: PacketListener?)
}
