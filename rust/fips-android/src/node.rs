//! Simplified FIPS node for Android.
//!
//! Uses upstream fips crate types for identity, noise handshake, and protocol
//! message types. Manages peer connections and packet routing with external
//! packet injection via JNI callbacks (no built-in transport or TUN).

use std::collections::HashMap;
use std::time::{Instant, Duration};

use jni::JavaVM;
use jni::objects::GlobalRef;
use serde::Serialize;

use fips::Identity;
use fips::NodeAddr;
use fips::PeerIdentity;
use fips::noise::{HandshakeState, NoiseSession};
use fips::protocol::LinkMessageType;

/// Peer state tracked by the node.
struct Peer {
    node_addr: NodeAddr,
    npub: String,
    transport_id: u32,
    remote_addr: String,
    session: Option<NoiseSession>,
    pending_handshake: Option<HandshakeState>,
    last_seen: Instant,
    last_heartbeat_sent: Instant,
    packets_rx: u64,
    packets_tx: u64,
    bytes_rx: u64,
    bytes_tx: u64,
}

/// JSON-serializable peer info.
#[derive(Serialize)]
pub struct PeerInfo {
    pub npub: String,
    pub node_addr: String,
    pub transport_id: u32,
    pub remote_addr: String,
    pub connected: bool,
    pub last_seen_ms: u64,
    pub packets_rx: u64,
    pub packets_tx: u64,
    pub bytes_rx: u64,
    pub bytes_tx: u64,
}

/// JSON-serializable node status.
#[derive(Serialize)]
pub struct NodeStatus {
    pub npub: String,
    pub node_addr: String,
    pub state: String,
    pub peer_count: usize,
    pub uptime_secs: u64,
    pub total_packets_rx: u64,
    pub total_packets_tx: u64,
}

/// The main FIPS node for Android.
pub struct FipsNode {
    identity: Identity,
    peers: HashMap<String, Peer>, // keyed by remote_addr
    jvm: JavaVM,
    callback: GlobalRef,
    started: Instant,
    running: bool,
    total_rx: u64,
    total_tx: u64,
}

impl FipsNode {
    /// Create a new node with the given nsec identity.
    pub fn new(nsec: &str, jvm: JavaVM, callback: GlobalRef) -> Result<Self, String> {
        let identity = Identity::from_secret_str(nsec)
            .map_err(|e| format!("identity error: {}", e))?;
        log::info!("FIPS node initialized: {}", identity.npub());

        Ok(Self {
            identity,
            peers: HashMap::new(),
            jvm,
            callback,
            started: Instant::now(),
            running: true,
            total_rx: 0,
            total_tx: 0,
        })
    }

    /// Inject a packet received from a transport.
    pub fn inject_packet(&mut self, data: &[u8], transport_id: u32, remote_addr: &str) {
        if !self.running || data.is_empty() {
            return;
        }

        self.total_rx += 1;

        let msg_type = data[0];

        match msg_type {
            // Noise IK msg1 — someone initiating handshake with us
            0x01 => {
                self.handle_handshake_msg1(data, transport_id, remote_addr);
            }
            // Noise IK msg2 — response to our handshake
            0x02 => {
                self.handle_handshake_msg2(data, remote_addr);
            }
            // Encrypted link-layer message
            _ => {
                self.handle_encrypted_message(data, remote_addr);
            }
        }
    }

    fn handle_handshake_msg1(&mut self, data: &[u8], transport_id: u32, remote_addr: &str) {
        // Create responder handshake state using upstream noise
        let keypair = self.identity.keypair();
        let mut hs = HandshakeState::new_responder(keypair);

        // Read the Noise IK msg1 (skip the type prefix byte)
        if let Err(e) = hs.read_message_1(&data[1..]) {
            log::warn!("Handshake msg1 failed from {}: {}", remote_addr, e);
            return;
        }

        // Set local epoch (random bytes for restart detection)
        let mut epoch = [0u8; 8];
        epoch.copy_from_slice(&std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
            .to_le_bytes()[..8]);
        hs.set_local_epoch(epoch);

        // Write msg2
        let msg2_noise = match hs.write_message_2() {
            Ok(msg) => msg,
            Err(e) => {
                log::warn!("Failed to write msg2 for {}: {}", remote_addr, e);
                return;
            }
        };

        // Complete the handshake into a session
        let session = match hs.into_session() {
            Ok(s) => s,
            Err(e) => {
                log::warn!("Failed to create session for {}: {}", remote_addr, e);
                return;
            }
        };

        // Identify the remote peer from the session's remote static key
        let remote_pubkey = *session.remote_static();
        let peer_identity = PeerIdentity::from_pubkey_full(remote_pubkey);
        let node_addr = *peer_identity.node_addr();
        let npub = peer_identity.npub();

        let now = Instant::now();
        self.peers.insert(remote_addr.to_string(), Peer {
            node_addr,
            npub,
            transport_id,
            remote_addr: remote_addr.to_string(),
            session: Some(session),
            pending_handshake: None,
            last_seen: now,
            last_heartbeat_sent: now,
            packets_rx: 1,
            packets_tx: 0,
            bytes_rx: data.len() as u64,
            bytes_tx: 0,
        });

        // Send msg2 with type prefix
        let mut wire_msg2 = Vec::with_capacity(1 + msg2_noise.len());
        wire_msg2.push(0x02);
        wire_msg2.extend_from_slice(&msg2_noise);
        self.send_packet(transport_id, remote_addr, &wire_msg2);
        log::info!("Handshake completed (responder) with {}", remote_addr);
    }

    fn handle_handshake_msg2(&mut self, data: &[u8], remote_addr: &str) {
        if let Some(peer) = self.peers.get_mut(remote_addr) {
            if let Some(mut hs) = peer.pending_handshake.take() {
                // Read the Noise IK msg2 (skip the type prefix byte)
                if let Err(e) = hs.read_message_2(&data[1..]) {
                    log::warn!("Handshake msg2 failed from {}: {}", remote_addr, e);
                    return;
                }

                match hs.into_session() {
                    Ok(session) => {
                        peer.session = Some(session);
                        peer.last_seen = Instant::now();
                        log::info!("Handshake completed (initiator) with {}", remote_addr);
                    }
                    Err(e) => {
                        log::warn!("Session creation failed from {}: {}", remote_addr, e);
                    }
                }
            }
        }
    }

    fn handle_encrypted_message(&mut self, data: &[u8], remote_addr: &str) {
        if let Some(peer) = self.peers.get_mut(remote_addr) {
            peer.last_seen = Instant::now();
            peer.packets_rx += 1;
            peer.bytes_rx += data.len() as u64;

            if let Some(session) = &mut peer.session {
                match session.decrypt(data) {
                    Ok(plaintext) => {
                        if plaintext.is_empty() {
                            return;
                        }
                        let msg_type = plaintext[0];
                        if let Some(link_type) = LinkMessageType::from_byte(msg_type) {
                            match link_type {
                                LinkMessageType::Heartbeat => {
                                    // Heartbeat received, already updated last_seen
                                }
                                LinkMessageType::TreeAnnounce => {
                                    log::debug!("Tree announce from {}", remote_addr);
                                }
                                LinkMessageType::FilterAnnounce => {
                                    log::debug!("Filter announce from {}", remote_addr);
                                }
                                _ => {
                                    log::debug!("Link message {:?} from {}", link_type, remote_addr);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        log::warn!("Decrypt failed from {}: {}", remote_addr, e);
                    }
                }
            }
        }
    }

    /// Send a packet out via the JNI callback.
    fn send_packet(&mut self, transport_id: u32, remote_addr: &str, data: &[u8]) {
        self.total_tx += 1;

        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let byte_array = match env.byte_array_from_slice(data) {
                Ok(arr) => arr,
                Err(_) => return,
            };
            let addr_str = match env.new_string(remote_addr) {
                Ok(s) => s,
                Err(_) => return,
            };

            let _ = env.call_method(
                &self.callback,
                "onPacket",
                "([BILjava/lang/String;)V",
                &[
                    (&byte_array).into(),
                    (transport_id as i32).into(),
                    (&addr_str).into(),
                ],
            );
        }
    }

    /// Periodic tick: send heartbeats, expire stale peers.
    pub fn tick(&mut self) {
        if !self.running { return; }

        let now = Instant::now();
        let heartbeat_interval = Duration::from_secs(15);
        let peer_timeout = Duration::from_secs(60);

        // Collect expired peers
        let expired: Vec<String> = self.peers.iter()
            .filter(|(_, p)| now.duration_since(p.last_seen) > peer_timeout)
            .map(|(k, _)| k.clone())
            .collect();

        for addr in expired {
            log::info!("Peer timed out: {}", addr);
            self.peers.remove(&addr);
        }

        // Send heartbeats
        let needs_heartbeat: Vec<(u32, String)> = self.peers.iter()
            .filter(|(_, p)| {
                p.session.is_some() &&
                now.duration_since(p.last_heartbeat_sent) > heartbeat_interval
            })
            .map(|(_, p)| (p.transport_id, p.remote_addr.clone()))
            .collect();

        for (tid, addr) in needs_heartbeat {
            if let Some(peer) = self.peers.get_mut(&addr) {
                if let Some(session) = &mut peer.session {
                    // Build heartbeat: type byte + 8-byte timestamp placeholder
                    let mut plaintext = Vec::with_capacity(9);
                    plaintext.push(LinkMessageType::Heartbeat as u8);
                    plaintext.extend_from_slice(&[0u8; 8]);

                    match session.encrypt(&plaintext) {
                        Ok(encrypted) => {
                            peer.last_heartbeat_sent = now;
                            peer.packets_tx += 1;
                            peer.bytes_tx += encrypted.len() as u64;
                            self.total_tx += 1;

                            // Send via callback
                            if let Ok(mut env) = self.jvm.attach_current_thread() {
                                if let Ok(byte_array) = env.byte_array_from_slice(&encrypted) {
                                    if let Ok(addr_str) = env.new_string(&addr) {
                                        let _ = env.call_method(
                                            &self.callback,
                                            "onPacket",
                                            "([BILjava/lang/String;)V",
                                            &[
                                                (&byte_array).into(),
                                                (tid as i32).into(),
                                                (&addr_str).into(),
                                            ],
                                        );
                                    }
                                }
                            }
                        }
                        Err(e) => {
                            log::warn!("Failed to encrypt heartbeat for {}: {}", addr, e);
                        }
                    }
                }
            }
        }
    }

    /// Get peers as JSON.
    pub fn get_peers_json(&self) -> String {
        let peers: Vec<PeerInfo> = self.peers.values().map(|p| {
            let now = Instant::now();
            PeerInfo {
                npub: p.npub.clone(),
                node_addr: hex::encode(p.node_addr.as_bytes()),
                transport_id: p.transport_id,
                remote_addr: p.remote_addr.clone(),
                connected: p.session.is_some(),
                last_seen_ms: now.duration_since(p.last_seen).as_millis() as u64,
                packets_rx: p.packets_rx,
                packets_tx: p.packets_tx,
                bytes_rx: p.bytes_rx,
                bytes_tx: p.bytes_tx,
            }
        }).collect();

        serde_json::to_string(&peers).unwrap_or_else(|_| "[]".into())
    }

    /// Get node status as JSON.
    pub fn get_status_json(&self) -> String {
        let status = NodeStatus {
            npub: self.identity.npub(),
            node_addr: hex::encode(self.identity.node_addr().as_bytes()),
            state: if self.running { "running" } else { "stopped" }.into(),
            peer_count: self.peers.len(),
            uptime_secs: self.started.elapsed().as_secs(),
            total_packets_rx: self.total_rx,
            total_packets_tx: self.total_tx,
        };

        serde_json::to_string(&status).unwrap_or_else(|_| "{}".into())
    }

    /// Shutdown the node.
    pub fn shutdown(mut self) {
        self.running = false;
        self.peers.clear();
        log::info!("FIPS node shut down");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn test_identity_generate_and_npub() {
        let identity = Identity::generate();
        let npub = identity.npub();
        assert!(npub.starts_with("npub1"));
        // Generate a second — should be different
        let identity2 = Identity::generate();
        assert_ne!(identity.npub(), identity2.npub());
    }

    #[test]
    fn test_identity_from_secret_str() {
        let identity = Identity::generate();
        // Round-trip via hex secret key
        let hex_secret = hex::encode(identity.keypair().secret_key().secret_bytes());
        let restored = Identity::from_secret_str(&hex_secret).unwrap();
        assert_eq!(identity.npub(), restored.npub());
    }

    #[test]
    fn test_node_addr_from_identity() {
        let identity = Identity::generate();
        let addr = identity.node_addr();
        assert_eq!(addr.as_bytes().len(), 16);
        // Node address should be deterministic from pubkey
        let addr2 = NodeAddr::from_pubkey(&identity.pubkey());
        assert_eq!(addr.as_bytes(), addr2.as_bytes());
    }

    #[test]
    fn test_peer_identity_roundtrip() {
        let identity = Identity::generate();
        let peer = PeerIdentity::from_pubkey(identity.pubkey());
        assert_eq!(peer.npub(), identity.npub());
        assert_eq!(peer.node_addr().as_bytes(), identity.node_addr().as_bytes());
    }

    #[test]
    fn test_noise_handshake_ik() {
        // Simulate a full IK handshake between initiator and responder
        let initiator_id = Identity::generate();
        let responder_id = Identity::generate();

        // Initiator creates msg1
        let mut hs_i = HandshakeState::new_initiator(
            initiator_id.keypair(),
            responder_id.pubkey_full(),
        );
        hs_i.set_local_epoch([1, 2, 3, 4, 5, 6, 7, 8]);
        let msg1 = hs_i.write_message_1().unwrap();

        // Responder reads msg1, writes msg2
        let mut hs_r = HandshakeState::new_responder(responder_id.keypair());
        hs_r.read_message_1(&msg1).unwrap();
        hs_r.set_local_epoch([8, 7, 6, 5, 4, 3, 2, 1]);
        let msg2 = hs_r.write_message_2().unwrap();

        // Initiator reads msg2
        hs_i.read_message_2(&msg2).unwrap();

        // Both complete into sessions
        let mut session_i = hs_i.into_session().unwrap();
        let mut session_r = hs_r.into_session().unwrap();

        // Verify they can communicate
        let plaintext = b"hello fips mesh";
        let encrypted = session_i.encrypt(plaintext).unwrap();
        let decrypted = session_r.decrypt(&encrypted).unwrap();
        assert_eq!(decrypted, plaintext);

        // And in reverse
        let encrypted2 = session_r.encrypt(b"hello back").unwrap();
        let decrypted2 = session_i.decrypt(&encrypted2).unwrap();
        assert_eq!(decrypted2, b"hello back");
    }

    #[test]
    fn test_link_message_type_roundtrip() {
        let heartbeat = LinkMessageType::Heartbeat;
        let byte = heartbeat as u8;
        let parsed = LinkMessageType::from_byte(byte).unwrap();
        assert_eq!(parsed, heartbeat);
    }

    #[test]
    fn test_peer_info_serialization() {
        let info = PeerInfo {
            npub: "npub1test".into(),
            node_addr: "deadbeef".into(),
            transport_id: 1,
            remote_addr: "192.168.1.1:4000".into(),
            connected: true,
            last_seen_ms: 100,
            packets_rx: 42,
            packets_tx: 37,
            bytes_rx: 4200,
            bytes_tx: 3700,
        };
        let json = serde_json::to_string(&info).unwrap();
        assert!(json.contains("\"npub\":\"npub1test\""));
        assert!(json.contains("\"connected\":true"));
    }

    #[test]
    fn test_node_status_serialization() {
        let status = NodeStatus {
            npub: "npub1test".into(),
            node_addr: "abcd1234".into(),
            state: "running".into(),
            peer_count: 3,
            uptime_secs: 120,
            total_packets_rx: 100,
            total_packets_tx: 80,
        };
        let json = serde_json::to_string(&status).unwrap();
        assert!(json.contains("\"peer_count\":3"));
        assert!(json.contains("\"state\":\"running\""));
    }
}
