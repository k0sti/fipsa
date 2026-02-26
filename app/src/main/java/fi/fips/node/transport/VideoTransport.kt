package fi.fips.node.transport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

/**
 * Video transport — QR code encode/decode via CameraX + ML Kit.
 * Supports animated QR with fountain codes for large payloads.
 */
class VideoTransport(
    private val context: Context,
    override val transportId: Int = 6,
) : FipsTransport {

    companion object {
        private const val TAG = "VideoTransport"
        private const val QR_MAX_BYTES = 2953 // Max QR code binary capacity (version 40, L)
        private const val CHUNK_SIZE = 200 // Bytes per QR frame for animated mode
        private const val MAGIC_HEADER = "FIPS:" // Prefix for FIPS QR data
    }

    override val transportType = TransportType.VIDEO
    override val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.camera.any")

    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val receivedChunks = mutableMapOf<Int, ByteArray>()
    private var expectedChunks = 0

    override fun start() {
        if (state == TransportState.RUNNING) return
        state = TransportState.STARTING

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        state = TransportState.RUNNING
        Log.i(TAG, "Video transport started")
    }

    override fun stop() {
        barcodeScanner?.close()
        barcodeScanner = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        receivedChunks.clear()
        state = TransportState.STOPPED
        Log.i(TAG, "Video transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        // Video transport "send" generates QR codes for display.
        // The actual display is handled by the UI layer.
        Log.d(TAG, "QR data prepared: ${data.size} bytes")
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    /**
     * Generate a single QR code bitmap from data.
     */
    fun generateQrCode(data: ByteArray, size: Int = 512): Bitmap? {
        return try {
            val content = MAGIC_HEADER + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            if (content.length > QR_MAX_BYTES) {
                Log.w(TAG, "Data too large for single QR: ${content.length}")
                return null
            }

            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L
            )

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed", e)
            null
        }
    }

    /**
     * Generate animated QR frames for large data using fountain coding.
     * Returns a list of QR bitmaps to cycle through.
     */
    fun generateAnimatedQr(data: ByteArray, size: Int = 512): List<Bitmap> {
        val totalChunks = ceil(data.size.toDouble() / CHUNK_SIZE).toInt()
        val frames = mutableListOf<Bitmap>()

        for (i in 0 until totalChunks) {
            val start = i * CHUNK_SIZE
            val end = min(start + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(start, end)

            // Frame header: chunk_index(2) + total_chunks(2) + data
            val frameData = ByteArray(4 + chunk.size)
            frameData[0] = (i shr 8).toByte()
            frameData[1] = (i and 0xFF).toByte()
            frameData[2] = (totalChunks shr 8).toByte()
            frameData[3] = (totalChunks and 0xFF).toByte()
            chunk.copyInto(frameData, 4)

            val bitmap = generateQrCode(frameData, size)
            if (bitmap != null) {
                frames.add(bitmap)
            }
        }

        // Add redundant fountain-coded frames (XOR of pairs)
        for (i in 0 until totalChunks / 2) {
            val idx1 = i * 2
            val idx2 = min(idx1 + 1, totalChunks - 1)

            val chunk1Start = idx1 * CHUNK_SIZE
            val chunk1End = min(chunk1Start + CHUNK_SIZE, data.size)
            val chunk1 = data.copyOfRange(chunk1Start, chunk1End)

            val chunk2Start = idx2 * CHUNK_SIZE
            val chunk2End = min(chunk2Start + CHUNK_SIZE, data.size)
            val chunk2 = data.copyOfRange(chunk2Start, chunk2End)

            val maxLen = maxOf(chunk1.size, chunk2.size)
            val xorChunk = ByteArray(maxLen)
            for (j in 0 until maxLen) {
                val b1 = if (j < chunk1.size) chunk1[j] else 0
                val b2 = if (j < chunk2.size) chunk2[j] else 0
                xorChunk[j] = (b1.toInt() xor b2.toInt()).toByte()
            }

            // Redundant frame with special index
            val frameData = ByteArray(4 + xorChunk.size)
            frameData[0] = ((totalChunks + i) shr 8).toByte()
            frameData[1] = ((totalChunks + i) and 0xFF).toByte()
            frameData[2] = (totalChunks shr 8).toByte()
            frameData[3] = (totalChunks and 0xFF).toByte()
            xorChunk.copyInto(frameData, 4)

            val bitmap = generateQrCode(frameData, size)
            if (bitmap != null) {
                frames.add(bitmap)
            }
        }

        return frames
    }

    /**
     * Bind camera analysis for QR code scanning.
     */
    fun bindCameraAnalysis(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                processImage(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner?.process(image)
            ?.addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue
                    if (rawValue.startsWith(MAGIC_HEADER)) {
                        val encoded = rawValue.removePrefix(MAGIC_HEADER)
                        try {
                            val data = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                            handleReceivedQrData(data)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to decode QR data: ${e.message}")
                        }
                    }
                }
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleReceivedQrData(data: ByteArray) {
        if (data.size < 4) return

        val chunkIndex = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val totalChunks = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val payload = data.copyOfRange(4, data.size)

        if (totalChunks == 1) {
            // Single frame — deliver directly
            listener?.onPacketReceived(payload, transportId, "video:qr")
            return
        }

        // Multi-frame reassembly
        expectedChunks = totalChunks
        if (chunkIndex < totalChunks) {
            receivedChunks[chunkIndex] = payload
        }

        // Check if all chunks received
        if (receivedChunks.size >= totalChunks) {
            val assembled = ByteArray(receivedChunks.values.sumOf { it.size })
            var offset = 0
            for (i in 0 until totalChunks) {
                val chunk = receivedChunks[i] ?: continue
                chunk.copyInto(assembled, offset)
                offset += chunk.size
            }
            receivedChunks.clear()
            listener?.onPacketReceived(assembled, transportId, "video:qr")
        }
    }
}
