package fi.fips.node.transport

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport — scanner + GATT server for mesh packet exchange.
 * Uses a custom FIPS service UUID with packet fragmentation for small MTU.
 */
class BleTransport(
    private val context: Context,
    override val transportId: Int = 2,
) : FipsTransport {

    companion object {
        private const val TAG = "BleTransport"
        val FIPS_SERVICE_UUID: UUID = UUID.fromString("f1950000-fips-4000-8000-00805f9b34fb")
        val FIPS_PACKET_CHAR_UUID: UUID = UUID.fromString("f1950001-fips-4000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 20
        private const val FRAGMENT_HEADER_SIZE = 3 // sequence(1) + total(1) + index(1)
    }

    override val transportType = TransportType.BLE
    override val isAvailable: Boolean
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.isEnabled == true &&
                   context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")
        }

    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    // Track connected peers by device address
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    // Fragment reassembly buffers
    private val reassemblyBuffers = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()

    private var sequenceNumber: Byte = 0

    override fun start() {
        if (state == TransportState.RUNNING) return
        state = TransportState.STARTING

        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager?.adapter
                ?: throw IllegalStateException("No Bluetooth adapter")

            startGattServer()
            startAdvertising(adapter)
            startScanning(adapter)

            state = TransportState.RUNNING
            Log.i(TAG, "BLE transport started")
        } catch (e: SecurityException) {
            state = TransportState.ERROR
            Log.e(TAG, "BLE permission denied", e)
        } catch (e: Exception) {
            state = TransportState.ERROR
            Log.e(TAG, "Failed to start BLE transport", e)
        }
    }

    override fun stop() {
        try {
            stopScanning()
            stopAdvertising()
            gattServer?.close()
            gattServer = null
            connectedDevices.clear()
            reassemblyBuffers.clear()
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException during stop", e)
        }
        state = TransportState.STOPPED
        Log.i(TAG, "BLE transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        val device = connectedDevices[remoteAddr] ?: return
        val mtu = deviceMtu[remoteAddr] ?: DEFAULT_MTU
        val maxPayload = mtu - FRAGMENT_HEADER_SIZE

        try {
            val characteristic = gattServer?.getService(FIPS_SERVICE_UUID)
                ?.getCharacteristic(FIPS_PACKET_CHAR_UUID) ?: return

            val fragments = fragment(data, maxPayload)
            val seq = sequenceNumber++

            for ((index, fragment) in fragments.withIndex()) {
                val header = byteArrayOf(seq, fragments.size.toByte(), index.toByte())
                characteristic.value = header + fragment
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Send failed (permission): ${e.message}")
        }
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    private fun startGattServer() {
        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices[device.address] = device
                    Log.d(TAG, "Device connected: ${device.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device.address)
                    deviceMtu.remove(device.address)
                    reassemblyBuffers.remove(device.address)
                    Log.d(TAG, "Device disconnected: ${device.address}")
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                deviceMtu[device.address] = mtu - 3 // ATT overhead
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == FIPS_PACKET_CHAR_UUID && value.size > FRAGMENT_HEADER_SIZE) {
                    handleFragment(device.address, value)
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (_: SecurityException) {}
                }
            }
        }

        gattServer = bluetoothManager?.openGattServer(context, gattCallback)

        val service = BluetoothGattService(FIPS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            FIPS_PACKET_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    @Suppress("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(FIPS_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Advertising started")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising failed: $errorCode")
            }
        })
    }

    @Suppress("MissingPermission")
    private fun startScanning(adapter: BluetoothAdapter) {
        scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FIPS_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    @Suppress("MissingPermission")
    private fun stopScanning() {
        if (scanning) {
            scanner?.stopScan(scanCallback)
            scanning = false
        }
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!connectedDevices.containsKey(device.address)) {
                Log.d(TAG, "Discovered FIPS peer: ${device.address}")
                // Connection would be initiated here in a full implementation
            }
        }
    }

    private fun handleFragment(deviceAddr: String, value: ByteArray) {
        val seq = value[0].toInt() and 0xFF
        val total = value[1].toInt() and 0xFF
        val index = value[2].toInt() and 0xFF
        val payload = value.copyOfRange(FRAGMENT_HEADER_SIZE, value.size)

        val key = "$deviceAddr:$seq"
        val fragments = reassemblyBuffers.getOrPut(key) { mutableMapOf() }
        fragments[index] = payload

        if (fragments.size == total) {
            reassemblyBuffers.remove(key)
            val assembled = ByteArray(fragments.values.sumOf { it.size })
            var offset = 0
            for (i in 0 until total) {
                val frag = fragments[i] ?: continue
                frag.copyInto(assembled, offset)
                offset += frag.size
            }
            listener?.onPacketReceived(assembled, transportId, deviceAddr)
        }
    }

    private fun fragment(data: ByteArray, maxPayload: Int): List<ByteArray> {
        if (maxPayload <= 0) return listOf(data)
        val fragments = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxPayload, data.size)
            fragments.add(data.copyOfRange(offset, end))
            offset = end
        }
        return fragments
    }
}
