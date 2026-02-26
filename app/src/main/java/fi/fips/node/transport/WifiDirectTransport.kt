package fi.fips.node.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Wi-Fi Direct transport — P2P group formation with UDP over the P2P interface.
 * Uses DNS-SD service discovery to find FIPS peers.
 */
class WifiDirectTransport(
    private val context: Context,
    override val transportId: Int = 3,
    private val port: Int = 4001,
) : FipsTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val SERVICE_TYPE = "_fips._udp"
        private const val SERVICE_NAME = "fips-mesh"
        private const val MAX_PACKET_SIZE = 65535
    }

    override val transportType = TransportType.WIFI_DIRECT
    override val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false
    private var broadcastReceiver: BroadcastReceiver? = null

    override fun start() {
        if (state == TransportState.RUNNING) return
        state = TransportState.STARTING

        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

            registerReceiver()
            registerService()
            discoverPeers()
            startUdpSocket()

            state = TransportState.RUNNING
            Log.i(TAG, "Wi-Fi Direct transport started")
        } catch (e: SecurityException) {
            state = TransportState.ERROR
            Log.e(TAG, "Wi-Fi Direct permission denied", e)
        } catch (e: Exception) {
            state = TransportState.ERROR
            Log.e(TAG, "Failed to start Wi-Fi Direct transport", e)
        }
    }

    override fun stop() {
        running = false
        receiveThread?.interrupt()
        receiveThread = null
        socket?.close()
        socket = null

        try {
            wifiP2pManager?.let { manager ->
                channel?.let { ch ->
                    manager.removeLocalService(ch, null, null)
                    manager.clearServiceRequests(ch, null)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission error during cleanup", e)
        }

        broadcastReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        broadcastReceiver = null

        state = TransportState.STOPPED
        Log.i(TAG, "Wi-Fi Direct transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        val sock = socket ?: return
        try {
            val parts = remoteAddr.split(":")
            if (parts.size != 2) return
            val host = parts[0]
            val portNum = parts[1].toIntOrNull() ?: return
            val packet = DatagramPacket(data, data.size, InetSocketAddress(host, portNum))
            sock.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi Direct send failed to $remoteAddr: ${e.message}")
        }
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    @Suppress("MissingPermission")
    private fun registerService() {
        val record = mapOf(
            "port" to port.toString(),
            "protocol" to "fips",
            "version" to "1"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, record)
        wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Local service registered")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to register service: $reason")
            }
        })
    }

    @Suppress("MissingPermission")
    private fun discoverPeers() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.setDnsSdResponseListeners(ch,
            { instanceName, registrationType, device ->
                Log.d(TAG, "Discovered service: $instanceName on ${device.deviceAddress}")
            },
            { fullDomainName, record, device ->
                val peerPort = record["port"]?.toIntOrNull() ?: port
                Log.d(TAG, "Peer TXT: $fullDomainName port=$peerPort device=${device.deviceAddress}")
            }
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(ch, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Service discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Service discovery failed: $reason")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Add service request failed: $reason")
            }
        })
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val wifiState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        Log.d(TAG, "P2P state: $wifiState")
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        if (networkInfo?.isConnected == true) {
                            Log.d(TAG, "P2P connected")
                        }
                    }
                }
            }
        }
        context.registerReceiver(broadcastReceiver, filter)
    }

    private fun startUdpSocket() {
        val sock = DatagramSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(port))
        sock.soTimeout = 100
        socket = sock
        running = true

        receiveThread = Thread({
            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val data = buffer.copyOf(packet.length)
                    val addr = "${packet.address.hostAddress}:${packet.port}"
                    listener?.onPacketReceived(data, transportId, addr)
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Receive error: ${e.message}")
                }
            }
        }, "fips-wifidirect-rx").apply {
            isDaemon = true
            start()
        }
    }
}
