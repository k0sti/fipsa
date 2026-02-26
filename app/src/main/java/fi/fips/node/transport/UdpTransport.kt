package fi.fips.node.transport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP transport — DatagramSocket on a configurable port.
 * Full implementation for standard Internet mesh connectivity.
 */
class UdpTransport(
    override val transportId: Int = 1,
    private val listenPort: Int = 4000,
) : FipsTransport {

    companion object {
        private const val TAG = "UdpTransport"
        private const val MAX_PACKET_SIZE = 65535
        private const val SOCKET_TIMEOUT_MS = 100
    }

    override val transportType = TransportType.UDP
    override val isAvailable = true
    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false

    override fun start() {
        if (state == TransportState.RUNNING) return

        state = TransportState.STARTING
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(listenPort))
            sock.soTimeout = SOCKET_TIMEOUT_MS
            sock.receiveBufferSize = 2 * 1024 * 1024
            sock.sendBufferSize = 2 * 1024 * 1024
            socket = sock
            running = true

            receiveThread = Thread({
                receiveLoop()
            }, "fips-udp-rx").apply {
                isDaemon = true
                start()
            }

            state = TransportState.RUNNING
            Log.i(TAG, "UDP transport started on port $listenPort")
        } catch (e: Exception) {
            state = TransportState.ERROR
            Log.e(TAG, "Failed to start UDP transport", e)
        }
    }

    override fun stop() {
        running = false
        receiveThread?.interrupt()
        receiveThread = null
        socket?.close()
        socket = null
        state = TransportState.STOPPED
        Log.i(TAG, "UDP transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        val sock = socket ?: return

        try {
            val parts = remoteAddr.split(":")
            if (parts.size != 2) return
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: return

            val packet = DatagramPacket(data, data.size, InetSocketAddress(host, port))
            sock.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Send failed to $remoteAddr: ${e.message}")
        }
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_PACKET_SIZE)

        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val data = buffer.copyOf(packet.length)
                val addr = "${packet.address.hostAddress}:${packet.port}"

                listener?.onPacketReceived(data, transportId, addr)
            } catch (_: SocketTimeoutException) {
                // Expected, allows checking running flag
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Receive error: ${e.message}")
                }
            }
        }
    }
}
