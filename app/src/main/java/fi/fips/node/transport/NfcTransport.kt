package fi.fips.node.transport

import android.app.Activity
import android.content.Context
import android.nfc.*
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log

/**
 * NFC transport — NDEF beam + HCE for peer bootstrap.
 * Exchanges npub + transport addresses for initial peer discovery.
 */
class NfcTransport(
    private val context: Context,
    override val transportId: Int = 4,
) : FipsTransport {

    companion object {
        private const val TAG = "NfcTransport"
        const val FIPS_AID = "F046495053000000" // "FIPS" in hex + padding
        private const val SELECT_APDU_HEADER = "00A40400"
    }

    override val transportType = TransportType.NFC
    override val isAvailable: Boolean
        get() {
            val nfcManager = context.getSystemService(Context.NFC_SERVICE) as? NfcManager
            return nfcManager?.defaultAdapter?.isEnabled == true
        }

    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var nfcAdapter: NfcAdapter? = null

    // Data to share via NFC (e.g., npub + addresses)
    private var shareData: ByteArray = ByteArray(0)

    override fun start() {
        if (state == TransportState.RUNNING) return
        state = TransportState.STARTING

        val nfcManager = context.getSystemService(Context.NFC_SERVICE) as? NfcManager
        nfcAdapter = nfcManager?.defaultAdapter

        if (nfcAdapter == null || nfcAdapter?.isEnabled != true) {
            state = TransportState.ERROR
            Log.w(TAG, "NFC not available or disabled")
            return
        }

        state = TransportState.RUNNING
        Log.i(TAG, "NFC transport started")
    }

    override fun stop() {
        nfcAdapter = null
        state = TransportState.STOPPED
        Log.i(TAG, "NFC transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        // NFC send is via beam/HCE — store data to be sent on next tap
        shareData = data
        Log.d(TAG, "NFC data queued for next tap: ${data.size} bytes")
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    /**
     * Set data to be shared via NFC on next tap (npub + addresses).
     */
    fun setShareData(data: ByteArray) {
        shareData = data
    }

    /**
     * Enable reader mode for an activity to receive peer data.
     */
    fun enableReaderMode(activity: Activity) {
        nfcAdapter?.enableReaderMode(
            activity,
            { tag ->
                handleTag(tag)
            },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
        )
    }

    /**
     * Disable reader mode.
     */
    fun disableReaderMode(activity: Activity) {
        nfcAdapter?.disableReaderMode(activity)
    }

    private fun handleTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 5000

            // Select FIPS AID
            val selectApdu = hexToBytes(SELECT_APDU_HEADER) +
                byteArrayOf((FIPS_AID.length / 2).toByte()) +
                hexToBytes(FIPS_AID)

            val response = isoDep.transceive(selectApdu)
            if (response.size >= 2) {
                val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                        (response[response.size - 1].toInt() and 0xFF)
                if (sw == 0x9000 && response.size > 2) {
                    val data = response.copyOf(response.size - 2)
                    listener?.onPacketReceived(data, transportId, "nfc:tap")
                    Log.d(TAG, "Received ${data.size} bytes via NFC")
                }
            }

            // Send our data if we have any
            if (shareData.isNotEmpty()) {
                val sendCommand = byteArrayOf(0x00, 0xDA.toByte(), 0x00, 0x00, shareData.size.toByte()) + shareData
                isoDep.transceive(sendCommand)
            }

            isoDep.close()
        } catch (e: Exception) {
            Log.w(TAG, "NFC communication error: ${e.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
