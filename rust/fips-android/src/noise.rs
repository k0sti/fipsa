//! Noise IK handshake and transport encryption.
//!
//! Implements the Noise IK pattern for link-layer encryption using
//! secp256k1 for static keys and ECDH via secp256k1.

use chacha20poly1305::{
    ChaCha20Poly1305, KeyInit, AeadInPlace,
    aead::generic_array::GenericArray,
};
use hkdf::Hkdf;
use sha2::Digest;
use secp256k1::{SecretKey, PublicKey, Secp256k1, rand::rngs::OsRng};

const NOISE_PROTOCOL_NAME: &[u8] = b"Noise_IK_secp256k1_ChaChaPoly_SHA256";
const TAG_LEN: usize = 16;

/// A Noise cipher state for encrypting/decrypting with ChaCha20-Poly1305.
pub struct CipherState {
    key: [u8; 32],
    nonce: u64,
}

impl CipherState {
    pub fn new(key: [u8; 32]) -> Self {
        Self { key, nonce: 0 }
    }

    pub fn encrypt(&mut self, plaintext: &[u8], ad: &[u8]) -> Vec<u8> {
        let cipher = ChaCha20Poly1305::new(GenericArray::from_slice(&self.key));
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[4..12].copy_from_slice(&self.nonce.to_le_bytes());
        self.nonce += 1;

        let mut buffer = plaintext.to_vec();
        let tag = cipher
            .encrypt_in_place_detached(
                GenericArray::from_slice(&nonce_bytes),
                ad,
                &mut buffer,
            )
            .expect("encryption failed");
        buffer.extend_from_slice(tag.as_slice());
        buffer
    }

    pub fn decrypt(&mut self, ciphertext: &[u8], ad: &[u8]) -> Result<Vec<u8>, String> {
        if ciphertext.len() < TAG_LEN {
            return Err("Ciphertext too short".into());
        }

        let cipher = ChaCha20Poly1305::new(GenericArray::from_slice(&self.key));
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[4..12].copy_from_slice(&self.nonce.to_le_bytes());
        self.nonce += 1;

        let (ct, tag) = ciphertext.split_at(ciphertext.len() - TAG_LEN);
        let mut buffer = ct.to_vec();
        cipher
            .decrypt_in_place_detached(
                GenericArray::from_slice(&nonce_bytes),
                ad,
                &mut buffer,
                GenericArray::from_slice(tag),
            )
            .map_err(|_| "Decryption failed".to_string())?;

        Ok(buffer)
    }
}

/// Symmetric state for the Noise handshake.
struct SymmetricState {
    ck: [u8; 32], // chaining key
    h: [u8; 32],  // handshake hash
}

impl SymmetricState {
    fn new() -> Self {
        let mut hasher = sha2::Sha256::new();
        hasher.update(NOISE_PROTOCOL_NAME);
        let h_bytes: [u8; 32] = hasher.finalize().into();
        Self {
            ck: h_bytes,
            h: h_bytes,
        }
    }

    fn mix_hash(&mut self, data: &[u8]) {
        let mut hasher = sha2::Sha256::new();
        hasher.update(&self.h);
        hasher.update(data);
        self.h = hasher.finalize().into();
    }

    fn mix_key(&mut self, input_key_material: &[u8]) {
        let hk = Hkdf::<sha2::Sha256>::new(Some(&self.ck), input_key_material);
        let mut output = [0u8; 64];
        hk.expand(b"", &mut output).expect("HKDF expand failed");
        self.ck.copy_from_slice(&output[..32]);
    }

    fn derive_keys(&self, ikm: &[u8]) -> ([u8; 32], [u8; 32]) {
        let hk = Hkdf::<sha2::Sha256>::new(Some(&self.ck), ikm);
        let mut output = [0u8; 64];
        hk.expand(b"", &mut output).expect("HKDF expand failed");
        let mut k1 = [0u8; 32];
        let mut k2 = [0u8; 32];
        k1.copy_from_slice(&output[..32]);
        k2.copy_from_slice(&output[32..64]);
        (k1, k2)
    }
}

/// A completed Noise session with send/receive cipher states.
pub struct NoiseSession {
    pub send_cipher: CipherState,
    pub recv_cipher: CipherState,
    pub remote_pubkey: PublicKey,
}

impl NoiseSession {
    /// Perform ECDH using secp256k1.
    fn ecdh(secret: &SecretKey, public: &PublicKey) -> [u8; 32] {
        let shared = secp256k1::ecdh::shared_secret_point(public, secret);
        let hash = sha2::Sha256::digest(&shared[..32]);
        hash.into()
    }

    /// Create an initiator handshake message (IK msg1).
    pub fn initiate(
        local_secret: &SecretKey,
        local_public: &PublicKey,
        remote_static: &PublicKey,
    ) -> (Vec<u8>, HandshakeState) {
        let secp = Secp256k1::new();
        let mut state = SymmetricState::new();

        state.mix_hash(&remote_static.serialize());

        let (eph_secret, eph_public) = secp.generate_keypair(&mut OsRng);
        state.mix_hash(&eph_public.serialize());

        let es = Self::ecdh(&eph_secret, remote_static);
        state.mix_key(&es);

        let (ck_new, cipher_key) = state.derive_keys(&es);
        let mut cipher = CipherState::new(cipher_key);
        let encrypted_static = cipher.encrypt(&local_public.serialize(), &state.h);
        state.mix_hash(&encrypted_static);
        state.ck = ck_new;

        let ss = Self::ecdh(local_secret, remote_static);
        state.mix_key(&ss);

        let mut msg = Vec::new();
        msg.push(0x01);
        msg.extend_from_slice(&eph_public.serialize());
        msg.extend_from_slice(&encrypted_static);

        let hs = HandshakeState {
            local_secret: local_secret.clone(),
            local_public: *local_public,
            eph_secret,
            eph_public,
            remote_static: *remote_static,
            symmetric: state,
            is_initiator: true,
        };

        (msg, hs)
    }

    /// Process a received msg1 as responder, returning (msg2, session).
    pub fn respond(
        local_secret: &SecretKey,
        local_public: &PublicKey,
        msg1: &[u8],
    ) -> Result<(Vec<u8>, Self), String> {
        if msg1.len() < 34 {
            return Err("msg1 too short".into());
        }

        let secp = Secp256k1::new();
        let mut state = SymmetricState::new();

        state.mix_hash(&local_public.serialize());

        let re = PublicKey::from_slice(&msg1[1..34])
            .map_err(|_| "Invalid ephemeral key")?;
        state.mix_hash(&re.serialize());

        let es = Self::ecdh(local_secret, &re);
        state.mix_key(&es);

        let (ck_new, cipher_key) = state.derive_keys(&es);
        let mut cipher = CipherState::new(cipher_key);
        let encrypted_static = &msg1[34..];
        let static_bytes = cipher.decrypt(encrypted_static, &state.h)?;
        let remote_static = PublicKey::from_slice(&static_bytes)
            .map_err(|_| "Invalid remote static key")?;
        state.mix_hash(encrypted_static);
        state.ck = ck_new;

        let ss = Self::ecdh(local_secret, &remote_static);
        state.mix_key(&ss);

        let (eph_secret, eph_public) = secp.generate_keypair(&mut OsRng);
        state.mix_hash(&eph_public.serialize());

        let ee = Self::ecdh(&eph_secret, &re);
        let (send_key, recv_key) = state.derive_keys(&ee);

        let mut msg2 = Vec::new();
        msg2.push(0x02);
        msg2.extend_from_slice(&eph_public.serialize());

        let session = Self {
            send_cipher: CipherState::new(send_key),
            recv_cipher: CipherState::new(recv_key),
            remote_pubkey: remote_static,
        };

        Ok((msg2, session))
    }

    /// Encrypt a link-layer message.
    pub fn encrypt_message(&mut self, msg_type: u8, payload: &[u8]) -> Vec<u8> {
        let mut plaintext = vec![msg_type];
        plaintext.extend_from_slice(payload);
        self.send_cipher.encrypt(&plaintext, &[])
    }

    /// Decrypt a link-layer message. Returns (msg_type, payload).
    pub fn decrypt_message(&mut self, ciphertext: &[u8]) -> Result<(u8, Vec<u8>), String> {
        let plaintext = self.recv_cipher.decrypt(ciphertext, &[])?;
        if plaintext.is_empty() {
            return Err("Empty decrypted message".into());
        }
        Ok((plaintext[0], plaintext[1..].to_vec()))
    }
}

/// In-progress handshake state for the initiator to complete with msg2.
pub struct HandshakeState {
    pub local_secret: SecretKey,
    pub local_public: PublicKey,
    pub eph_secret: SecretKey,
    pub eph_public: PublicKey,
    pub remote_static: PublicKey,
    symmetric: SymmetricState,
    pub is_initiator: bool,
}

impl HandshakeState {
    /// Complete the handshake by processing msg2 (initiator side).
    pub fn complete(mut self, msg2: &[u8]) -> Result<NoiseSession, String> {
        if msg2.len() < 34 {
            return Err("msg2 too short".into());
        }

        let re = PublicKey::from_slice(&msg2[1..34])
            .map_err(|_| "Invalid responder ephemeral")?;
        self.symmetric.mix_hash(&re.serialize());

        let ee = NoiseSession::ecdh(&self.eph_secret, &re);
        let (recv_key, send_key) = self.symmetric.derive_keys(&ee);

        Ok(NoiseSession {
            send_cipher: CipherState::new(send_key),
            recv_cipher: CipherState::new(recv_key),
            remote_pubkey: self.remote_static,
        })
    }
}
