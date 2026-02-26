//! Protocol message types for the FIPS wire format.
//!
//! Defines handshake, link-layer, and session-layer message types
//! matching the upstream FIPS protocol specification.

/// Handshake message types (pre-encryption).
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum HandshakeType {
    NoiseIKMsg1 = 0x01,
    NoiseIKMsg2 = 0x02,
}

/// Link-layer message types (encrypted with Noise IK session).
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum LinkMessageType {
    SessionDatagram = 0x00,
    TreeAnnounce = 0x10,
    FilterAnnounce = 0x20,
    LookupRequest = 0x30,
    LookupResponse = 0x31,
    Disconnect = 0x50,
    Heartbeat = 0x51,
}

impl LinkMessageType {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0x00 => Some(Self::SessionDatagram),
            0x10 => Some(Self::TreeAnnounce),
            0x20 => Some(Self::FilterAnnounce),
            0x30 => Some(Self::LookupRequest),
            0x31 => Some(Self::LookupResponse),
            0x50 => Some(Self::Disconnect),
            0x51 => Some(Self::Heartbeat),
            _ => None,
        }
    }
}

/// Session-layer message types (end-to-end encrypted with Noise XK).
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum SessionMessageType {
    SessionSetup = 0x00,
    SessionAck = 0x01,
    DataPacket = 0x10,
    SenderReport = 0x11,
    ReceiverReport = 0x12,
    PathMtuNotification = 0x13,
    CoordsRequired = 0x20,
    PathBroken = 0x21,
    MtuExceeded = 0x22,
}

/// Build a heartbeat message payload.
pub fn build_heartbeat() -> Vec<u8> {
    // Heartbeat is just the type byte with an 8-byte epoch
    let mut buf = Vec::with_capacity(9);
    buf.push(LinkMessageType::Heartbeat as u8);
    buf.extend_from_slice(&[0u8; 8]); // timestamp placeholder
    buf
}

/// Build a tree announce payload from coordinate data.
pub fn build_tree_announce(node_addr: &[u8; 16], parent: Option<&[u8; 16]>, seq: u64) -> Vec<u8> {
    let mut buf = Vec::new();
    buf.push(LinkMessageType::TreeAnnounce as u8);
    buf.extend_from_slice(node_addr);
    buf.extend_from_slice(&seq.to_be_bytes());
    if let Some(parent_addr) = parent {
        buf.push(1); // has parent
        buf.extend_from_slice(parent_addr);
    } else {
        buf.push(0); // root
    }
    buf
}

/// Build a bloom filter announce payload.
pub fn build_filter_announce(filter_data: &[u8]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(1 + filter_data.len());
    buf.push(LinkMessageType::FilterAnnounce as u8);
    buf.extend_from_slice(filter_data);
    buf
}
