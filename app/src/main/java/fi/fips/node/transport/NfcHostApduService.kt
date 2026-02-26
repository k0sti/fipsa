package fi.fips.node.transport

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * Host Card Emulation service for NFC peer bootstrap.
 * Responds to SELECT APDU with stored peer data (npub + addresses).
 */
class NfcHostApduService : HostApduService() {

    companion object {
        private const val TAG = "NfcHostApdu"
        private val SELECT_AID = NfcTransport.FIPS_AID
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // Shared data to be sent on NFC tap
        @Volatile
        var shareData: ByteArray = ByteArray(0)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.size < 4) return SW_NOT_FOUND

        val ins = commandApdu[1].toInt() and 0xFF

        return when (ins) {
            // SELECT command
            0xA4 -> {
                Log.d(TAG, "SELECT received")
                if (shareData.isNotEmpty()) {
                    shareData + SW_OK
                } else {
                    SW_OK
                }
            }
            // PUT DATA — receiving peer data
            0xDA -> {
                if (commandApdu.size > 5) {
                    val data = commandApdu.copyOfRange(5, commandApdu.size)
                    Log.d(TAG, "Received ${data.size} bytes via HCE")
                    // Data would be forwarded to the transport manager
                }
                SW_OK
            }
            else -> SW_NOT_FOUND
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "NFC deactivated: $reason")
    }
}
