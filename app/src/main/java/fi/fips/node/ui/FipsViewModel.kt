package fi.fips.node.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.fips.node.core.FipsCore
import fi.fips.node.transport.TransportManager
import fi.fips.node.transport.TransportState
import fi.fips.node.transport.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PeerInfo(
    val npub: String,
    val nodeAddr: String,
    val transportId: Int,
    val remoteAddr: String,
    val connected: Boolean,
    val lastSeenMs: Long,
    val packetsRx: Long,
    val packetsTx: Long,
    val bytesRx: Long,
    val bytesTx: Long,
)

data class NodeStatus(
    val npub: String = "",
    val nodeAddr: String = "",
    val state: String = "stopped",
    val peerCount: Int = 0,
    val uptimeSecs: Long = 0,
    val totalPacketsRx: Long = 0,
    val totalPacketsTx: Long = 0,
)

data class TransportInfo(
    val id: Int,
    val type: TransportType,
    val displayName: String,
    val available: Boolean,
    val state: TransportState,
)

data class AppSettings(
    val udpPort: Int = 4000,
    val bootstrapPeers: String = "node.fips.atlantislabs.space:4000",
    val audioUltrasonic: Boolean = true,
)

class FipsViewModel(application: Application) : AndroidViewModel(application) {

    val core = FipsCore()
    var transportManager: TransportManager? = null
        private set

    private val prefs: SharedPreferences =
        application.getSharedPreferences("fips_prefs", Application.MODE_PRIVATE)

    private val _status = MutableStateFlow(NodeStatus())
    val status: StateFlow<NodeStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _transports = MutableStateFlow<List<TransportInfo>>(emptyList())
    val transports: StateFlow<List<TransportInfo>> = _transports.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _nsec = MutableStateFlow(prefs.getString("nsec", "") ?: "")
    val nsec: StateFlow<String> = _nsec.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun initNode(nsec: String) {
        val tm = transportManager ?: return
        if (core.init(nsec, tm)) {
            _nsec.value = nsec
            prefs.edit().putString("nsec", nsec).apply()
            _isRunning.value = true
            startPolling()
        }
    }

    fun initTransportManager(tm: TransportManager) {
        transportManager = tm
        updateTransportInfo()
    }

    fun toggleTransport(id: Int) {
        val tm = transportManager ?: return
        val transport = tm.getTransport(id) ?: return
        if (transport.state == TransportState.RUNNING) {
            tm.stopTransport(id)
        } else {
            tm.startTransport(id)
        }
        updateTransportInfo()
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        prefs.edit()
            .putInt("udp_port", newSettings.udpPort)
            .putString("bootstrap_peers", newSettings.bootstrapPeers)
            .putBoolean("audio_ultrasonic", newSettings.audioUltrasonic)
            .apply()
    }

    fun shutdown() {
        _isRunning.value = false
        transportManager?.stopAll()
        core.shutdown()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (_isRunning.value) {
                core.tick()
                updateStatus()
                updatePeers()
                updateTransportInfo()
                delay(1000)
            }
        }
    }

    private fun updateStatus() {
        try {
            val json = JSONObject(core.getStatus())
            _status.value = NodeStatus(
                npub = json.optString("npub", ""),
                nodeAddr = json.optString("node_addr", ""),
                state = json.optString("state", "stopped"),
                peerCount = json.optInt("peer_count", 0),
                uptimeSecs = json.optLong("uptime_secs", 0),
                totalPacketsRx = json.optLong("total_packets_rx", 0),
                totalPacketsTx = json.optLong("total_packets_tx", 0),
            )
        } catch (_: Exception) {}
    }

    private fun updatePeers() {
        try {
            val arr = JSONArray(core.getPeers())
            val list = mutableListOf<PeerInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(PeerInfo(
                    npub = obj.optString("npub", ""),
                    nodeAddr = obj.optString("node_addr", ""),
                    transportId = obj.optInt("transport_id", 0),
                    remoteAddr = obj.optString("remote_addr", ""),
                    connected = obj.optBoolean("connected", false),
                    lastSeenMs = obj.optLong("last_seen_ms", 0),
                    packetsRx = obj.optLong("packets_rx", 0),
                    packetsTx = obj.optLong("packets_tx", 0),
                    bytesRx = obj.optLong("bytes_rx", 0),
                    bytesTx = obj.optLong("bytes_tx", 0),
                ))
            }
            _peers.value = list
        } catch (_: Exception) {}
    }

    private fun updateTransportInfo() {
        val tm = transportManager ?: return
        _transports.value = tm.allTransports.map { t ->
            TransportInfo(
                id = t.transportId,
                type = t.transportType,
                displayName = t.transportType.displayName,
                available = t.isAvailable,
                state = t.state,
            )
        }
    }

    private fun loadSettings(): AppSettings {
        return AppSettings(
            udpPort = prefs.getInt("udp_port", 4000),
            bootstrapPeers = prefs.getString("bootstrap_peers", "node.fips.atlantislabs.space:4000") ?: "",
            audioUltrasonic = prefs.getBoolean("audio_ultrasonic", true),
        )
    }
}
