package fi.fips.node.transport

import android.media.*
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Audio transport — FSK modem for mesh packet exchange over sound.
 * Supports ultrasonic (18-20 kHz) and audible (1-4 kHz) modes.
 * Implements a basic 2-FSK modem with preamble detection.
 */
class AudioTransport(
    override val transportId: Int = 5,
    private var ultrasonic: Boolean = true,
) : FipsTransport {

    companion object {
        private const val TAG = "AudioTransport"
        private const val SAMPLE_RATE = 44100

        // Ultrasonic frequencies
        private const val ULTRA_FREQ_0 = 18000.0
        private const val ULTRA_FREQ_1 = 19000.0

        // Audible frequencies
        private const val AUDIBLE_FREQ_0 = 1200.0
        private const val AUDIBLE_FREQ_1 = 2400.0

        // Modulation parameters
        private const val BAUD_RATE = 300 // symbols per second
        private const val SAMPLES_PER_SYMBOL = SAMPLE_RATE / BAUD_RATE // 147

        // Preamble: alternating 0/1 pattern (8 symbols)
        private val PREAMBLE = byteArrayOf(0, 1, 0, 1, 0, 1, 0, 1)

        // Sync word: unique bit pattern to mark start of data
        private val SYNC_WORD = byteArrayOf(1, 1, 0, 0, 1, 0, 1, 1)

        // Frame format: PREAMBLE + SYNC + LENGTH(2 bytes) + DATA + CRC16(2 bytes)
        private const val MAX_FRAME_SIZE = 256
    }

    override val transportType = TransportType.AUDIO
    override val isAvailable = true
    override var state: TransportState = TransportState.STOPPED
        private set

    private var listener: PacketListener? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false

    private val freq0 get() = if (ultrasonic) ULTRA_FREQ_0 else AUDIBLE_FREQ_0
    private val freq1 get() = if (ultrasonic) ULTRA_FREQ_1 else AUDIBLE_FREQ_1

    fun setUltrasonic(enabled: Boolean) {
        ultrasonic = enabled
    }

    override fun start() {
        if (state == TransportState.RUNNING) return
        state = TransportState.STARTING

        try {
            initAudioTrack()
            initAudioRecord()
            running = true

            receiveThread = Thread({
                demodulateLoop()
            }, "fips-audio-rx").apply {
                isDaemon = true
                start()
            }

            state = TransportState.RUNNING
            Log.i(TAG, "Audio transport started (${if (ultrasonic) "ultrasonic" else "audible"})")
        } catch (e: Exception) {
            state = TransportState.ERROR
            Log.e(TAG, "Failed to start audio transport", e)
        }
    }

    override fun stop() {
        running = false
        receiveThread?.interrupt()
        receiveThread = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        state = TransportState.STOPPED
        Log.i(TAG, "Audio transport stopped")
    }

    override fun send(data: ByteArray, remoteAddr: String) {
        if (data.size > MAX_FRAME_SIZE) {
            Log.w(TAG, "Packet too large for audio: ${data.size}")
            return
        }

        val frame = buildFrame(data)
        val samples = modulate(frame)
        transmit(samples)
    }

    override fun setPacketListener(listener: PacketListener?) {
        this.listener = listener
    }

    // --- Modulation ---

    private fun buildFrame(data: ByteArray): ByteArray {
        val length = data.size
        val crc = crc16(data)

        val bits = mutableListOf<Byte>()

        // Preamble
        bits.addAll(PREAMBLE.toList())
        // Sync word
        bits.addAll(SYNC_WORD.toList())
        // Length (16 bits, big-endian)
        for (i in 15 downTo 0) {
            bits.add(((length shr i) and 1).toByte())
        }
        // Data bits (MSB first)
        for (byte in data) {
            for (i in 7 downTo 0) {
                bits.add(((byte.toInt() shr i) and 1).toByte())
            }
        }
        // CRC16 (16 bits)
        for (i in 15 downTo 0) {
            bits.add(((crc shr i) and 1).toByte())
        }

        return bits.toByteArray()
    }

    private fun modulate(bits: ByteArray): ShortArray {
        val totalSamples = bits.size * SAMPLES_PER_SYMBOL
        val samples = ShortArray(totalSamples)
        var phase = 0.0

        for ((bitIdx, bit) in bits.withIndex()) {
            val freq = if (bit.toInt() == 0) freq0 else freq1
            val phaseIncrement = 2.0 * PI * freq / SAMPLE_RATE

            for (s in 0 until SAMPLES_PER_SYMBOL) {
                val sampleIdx = bitIdx * SAMPLES_PER_SYMBOL + s
                val value = sin(phase) * 0.8
                samples[sampleIdx] = (value * Short.MAX_VALUE).toInt().toShort()
                phase += phaseIncrement
            }
        }

        return samples
    }

    private fun transmit(samples: ShortArray) {
        val track = audioTrack ?: return
        track.play()
        track.write(samples, 0, samples.size)
        track.stop()
    }

    // --- Demodulation ---

    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    @Suppress("MissingPermission")
    private fun initAudioRecord() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
    }

    private fun demodulateLoop() {
        val record = audioRecord ?: return
        record.startRecording()

        val bufferSize = SAMPLES_PER_SYMBOL * 2
        val buffer = ShortArray(bufferSize)
        val symbolBuffer = mutableListOf<Byte>()
        var inFrame = false
        var syncMatchCount = 0

        while (running) {
            val read = record.read(buffer, 0, bufferSize)
            if (read <= 0) continue

            // Process each symbol-length block
            var offset = 0
            while (offset + SAMPLES_PER_SYMBOL <= read) {
                val symbol = demodulateSymbol(buffer, offset)
                offset += SAMPLES_PER_SYMBOL

                if (!inFrame) {
                    // Look for sync word
                    if (symbol == SYNC_WORD[syncMatchCount]) {
                        syncMatchCount++
                        if (syncMatchCount == SYNC_WORD.size) {
                            inFrame = true
                            syncMatchCount = 0
                            symbolBuffer.clear()
                        }
                    } else {
                        syncMatchCount = 0
                    }
                } else {
                    symbolBuffer.add(symbol)

                    // After 16 bits, we have the length
                    if (symbolBuffer.size == 16) {
                        // Continue collecting
                    } else if (symbolBuffer.size >= 16) {
                        val length = bitsToInt(symbolBuffer.subList(0, 16))
                        val totalBits = 16 + length * 8 + 16 // length + data + crc
                        if (symbolBuffer.size >= totalBits) {
                            val frame = extractFrame(symbolBuffer, length)
                            if (frame != null) {
                                listener?.onPacketReceived(frame, transportId, "audio:local")
                            }
                            inFrame = false
                            symbolBuffer.clear()
                        }
                        if (length > MAX_FRAME_SIZE) {
                            inFrame = false
                            symbolBuffer.clear()
                        }
                    }
                }
            }
        }

        record.stop()
    }

    private fun demodulateSymbol(buffer: ShortArray, offset: Int): Byte {
        // Goertzel algorithm for frequency detection
        val power0 = goertzel(buffer, offset, SAMPLES_PER_SYMBOL, freq0)
        val power1 = goertzel(buffer, offset, SAMPLES_PER_SYMBOL, freq1)
        return if (power1 > power0) 1 else 0
    }

    private fun goertzel(samples: ShortArray, offset: Int, length: Int, targetFreq: Double): Double {
        val k = (0.5 + length * targetFreq / SAMPLE_RATE).toInt()
        val w = 2.0 * PI * k / length
        val coeff = 2.0 * cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0

        for (i in 0 until length) {
            s0 = samples[offset + i].toDouble() / Short.MAX_VALUE + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }

        return s0 * s0 + s1 * s1 - coeff * s0 * s1
    }

    private fun bitsToInt(bits: List<Byte>): Int {
        var value = 0
        for (bit in bits) {
            value = (value shl 1) or (bit.toInt() and 1)
        }
        return value
    }

    private fun extractFrame(symbols: List<Byte>, length: Int): ByteArray? {
        val dataBits = symbols.subList(16, 16 + length * 8)
        val crcBits = symbols.subList(16 + length * 8, 16 + length * 8 + 16)

        val data = ByteArray(length)
        for (i in 0 until length) {
            var byte = 0
            for (bit in 0 until 8) {
                byte = (byte shl 1) or (dataBits[i * 8 + bit].toInt() and 1)
            }
            data[i] = byte.toByte()
        }

        val expectedCrc = bitsToInt(crcBits)
        val actualCrc = crc16(data)

        return if (expectedCrc == actualCrc) data else null
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
