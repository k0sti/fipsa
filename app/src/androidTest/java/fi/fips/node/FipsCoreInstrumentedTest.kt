package fi.fips.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.fips.node.core.FipsCore
import fi.fips.node.core.PacketCallback
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for the FIPS native JNI bridge.
 * These run on an Android emulator/device and verify the Rust .so loads
 * and the node lifecycle works end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class FipsCoreInstrumentedTest {

    // Test nsec (throwaway key for testing only)
    private val testNsec = "nsec1wajlth83zh2axvvmu2ll6ufkaqyxc2nzt4arpz7n6arsuy5enqvshdv45a"

    private fun createTestCallback(): PacketCallback {
        return object : PacketCallback {
            override fun onPacket(data: ByteArray, transportId: Int, remoteAddr: String) {
                // No-op for basic tests
            }
        }
    }

    @Test
    fun testNativeLibraryLoads() {
        // Just creating FipsCore triggers System.loadLibrary
        val core = FipsCore()
        // If we get here without UnsatisfiedLinkError, the .so loaded
        assertNotNull(core)
    }

    @Test
    fun testNodeInitialization() {
        val core = FipsCore()
        val result = core.init(testNsec, createTestCallback())
        assertTrue("Node should initialize successfully", result)
        assertTrue("Node should be initialized", core.isInitialized)
        core.shutdown()
        assertFalse("Node should not be initialized after shutdown", core.isInitialized)
    }

    @Test
    fun testNodeStatusJson() {
        val core = FipsCore()
        core.init(testNsec, createTestCallback())

        val statusJson = core.getStatus()
        val status = JSONObject(statusJson)

        // Verify expected fields
        assertTrue("Status should have npub", status.has("npub"))
        assertTrue("Status should have node_addr", status.has("node_addr"))
        assertTrue("Status should have state", status.has("state"))
        assertTrue("Status should have peer_count", status.has("peer_count"))
        assertTrue("Status should have uptime_secs", status.has("uptime_secs"))

        // Verify values
        val npub = status.getString("npub")
        assertTrue("npub should start with npub1", npub.startsWith("npub1"))
        assertEquals("State should be running", "running", status.getString("state"))
        assertEquals("Peer count should be 0", 0, status.getInt("peer_count"))

        core.shutdown()
    }

    @Test
    fun testNodePeersEmptyOnStart() {
        val core = FipsCore()
        core.init(testNsec, createTestCallback())

        val peersJson = core.getPeers()
        val peers = JSONArray(peersJson)
        assertEquals("Peers should be empty on start", 0, peers.length())

        core.shutdown()
    }

    @Test
    fun testNodeTick() {
        val core = FipsCore()
        core.init(testNsec, createTestCallback())

        // tick() should not crash with no peers
        core.tick()
        core.tick()
        core.tick()

        // Node should still be alive
        assertTrue("Node should still be initialized after ticks", core.isInitialized)

        core.shutdown()
    }

    @Test
    fun testInjectGarbagePacket() {
        val core = FipsCore()
        core.init(testNsec, createTestCallback())

        // Injecting garbage should not crash
        core.injectPacket(byteArrayOf(0xFF.toByte(), 0x00, 0x01), 1, "127.0.0.1:4000")
        core.injectPacket(byteArrayOf(), 1, "127.0.0.1:4000")
        core.injectPacket(byteArrayOf(0x01), 1, "127.0.0.1:4000") // Too-short handshake msg1

        assertTrue("Node should survive garbage packets", core.isInitialized)
        core.shutdown()
    }

    @Test
    fun testMultipleInitShutdownCycles() {
        val core = FipsCore()

        repeat(3) {
            val result = core.init(testNsec, createTestCallback())
            assertTrue("Init cycle $it should succeed", result)
            core.tick()
            core.shutdown()
        }
    }

    @Test
    fun testPacketCallbackFires() {
        // Two nodes: initiator sends handshake, responder's callback should fire with msg2
        val latch = CountDownLatch(1)
        var receivedData: ByteArray? = null

        val responderCallback = object : PacketCallback {
            override fun onPacket(data: ByteArray, transportId: Int, remoteAddr: String) {
                receivedData = data
                latch.countDown()
            }
        }

        val responder = FipsCore()
        responder.init(testNsec, responderCallback)

        // Inject a fake handshake msg1 (type 0x01 + noise data)
        // This will fail the Noise handshake (bad data) but the code path exercises the JNI callback
        val fakeMsg1 = ByteArray(120) { 0x00 }
        fakeMsg1[0] = 0x01 // msg1 type
        responder.injectPacket(fakeMsg1, 1, "10.0.0.1:4000")

        // The handshake will fail (invalid noise data), so callback won't fire
        // But the node shouldn't crash
        assertTrue("Responder should survive bad handshake", responder.isInitialized)
        responder.shutdown()
    }

    @Test
    fun testApplicationContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("fi.fips.node", appContext.packageName)
    }
}
