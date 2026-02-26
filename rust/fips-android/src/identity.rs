//! Identity module — Nostr keypair-based identity using secp256k1.
//!
//! Mirrors the upstream fips identity module: NodeAddr is a 16-byte
//! truncated SHA256 of the public key, with bech32 npub/nsec encoding.

use secp256k1::{Secp256k1, SecretKey, PublicKey, rand::rngs::OsRng};
use sha2::{Sha256, Digest};
use bech32::{Bech32, Hrp};

/// 16-byte node address derived from public key.
pub type NodeAddr = [u8; 16];

/// FIPS identity backed by a secp256k1 keypair.
pub struct Identity {
    secret_key: SecretKey,
    public_key: PublicKey,
    node_addr: NodeAddr,
}

impl Identity {
    /// Create identity from an nsec bech32 string.
    pub fn from_nsec(nsec: &str) -> Result<Self, String> {
        let (hrp, data) = bech32::decode(nsec)
            .map_err(|e| format!("bech32 decode error: {}", e))?;

        if hrp != Hrp::parse("nsec").unwrap() {
            return Err("Not an nsec key".into());
        }

        if data.len() != 32 {
            return Err(format!("Invalid secret key length: {}", data.len()));
        }

        let secp = Secp256k1::new();
        let secret_key = SecretKey::from_slice(&data)
            .map_err(|e| format!("Invalid secret key: {}", e))?;
        let public_key = PublicKey::from_secret_key(&secp, &secret_key);
        let node_addr = Self::compute_node_addr(&public_key);

        Ok(Self { secret_key, public_key, node_addr })
    }

    /// Generate a new random identity.
    pub fn generate() -> Self {
        let secp = Secp256k1::new();
        let (secret_key, public_key) = secp.generate_keypair(&mut OsRng);
        let node_addr = Self::compute_node_addr(&public_key);
        Self { secret_key, public_key, node_addr }
    }

    /// Compute NodeAddr: first 16 bytes of SHA256(compressed_pubkey).
    fn compute_node_addr(pubkey: &PublicKey) -> NodeAddr {
        let hash = Sha256::digest(pubkey.serialize());
        let mut addr = [0u8; 16];
        addr.copy_from_slice(&hash[..16]);
        addr
    }

    pub fn secret_key(&self) -> &SecretKey { &self.secret_key }
    pub fn public_key(&self) -> &PublicKey { &self.public_key }
    pub fn node_addr(&self) -> &NodeAddr { &self.node_addr }

    /// Encode public key as npub bech32 string.
    pub fn npub(&self) -> String {
        // Nostr npub uses the 32-byte x-only pubkey
        let serialized = self.public_key.serialize();
        // Use x-only (skip the 0x02/0x03 prefix byte)
        let x_only = &serialized[1..33];
        let hrp = Hrp::parse("npub").unwrap();
        bech32::encode::<Bech32>(hrp, x_only)
            .unwrap_or_else(|_| "npub_error".into())
    }

    /// Encode secret key as nsec bech32 string.
    pub fn nsec(&self) -> String {
        let hrp = Hrp::parse("nsec").unwrap();
        bech32::encode::<Bech32>(hrp, &self.secret_key.secret_bytes())
            .unwrap_or_else(|_| "nsec_error".into())
    }
}
