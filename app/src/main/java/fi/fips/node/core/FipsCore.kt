package fi.fips.node.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI bridge to the native Rust FIPS node.
 * Manages the native node lifecycle and provides packet injection.
 */
class FipsCore {
    companion object {
        init {
            System.loadLibrary("fips_android")
        }
    }

    private var handle: Long = 0L
    private var callback: PacketCallback? = null

    /**
     * Initialize the native FIPS node with the given nsec identity.
     *
     * @param nsec Nostr secret key in bech32 nsec format
     * @param callback Callback for outbound packets
     * @return true if initialization succeeded
     */
    fun init(nsec: String, callback: PacketCallback): Boolean {
        this.callback = callback
        handle = nativeInit(nsec, callback)
        return handle != 0L
    }

    /**
     * Inject a received packet into the node for processing.
     */
    fun injectPacket(data: ByteArray, transportId: Int, remoteAddr: String) {
        if (handle != 0L) {
            nativeInjectPacket(handle, data, transportId, remoteAddr)
        }
    }

    /**
     * Periodic tick — processes timers, heartbeats, expiry.
     */
    fun tick() {
        if (handle != 0L) {
            nativeTick(handle)
        }
    }

    /**
     * Get current peers as a JSON array string.
     */
    fun getPeers(): String {
        if (handle == 0L) return "[]"
        return nativeGetPeers(handle)
    }

    /**
     * Get node status as a JSON object string.
     */
    fun getStatus(): String {
        if (handle == 0L) return "{}"
        return nativeGetStatus(handle)
    }

    /**
     * Shutdown the native node and free resources.
     */
    fun shutdown() {
        if (handle != 0L) {
            nativeShutdown(handle)
            handle = 0L
            callback = null
        }
    }

    val isInitialized: Boolean get() = handle != 0L

    // Native JNI methods
    private external fun nativeInit(nsec: String, callback: PacketCallback): Long
    private external fun nativeInjectPacket(handle: Long, data: ByteArray, transportId: Int, remoteAddr: String)
    private external fun nativeTick(handle: Long)
    private external fun nativeGetPeers(handle: Long): String
    private external fun nativeGetStatus(handle: Long): String
    private external fun nativeShutdown(handle: Long)
}
