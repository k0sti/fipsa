# FIPS Android (fipsa)

Android application that runs a [FIPS](https://github.com/jmcorgan/fips) mesh node with multiple transport channels. FIPS (Free Internetworking Peering System) is a distributed, decentralized network routing protocol using Nostr identities for peer authentication and Noise protocol for encrypted communication.

## Architecture

```
┌──────────────────────────────────────────────┐
│              Jetpack Compose UI              │
│  Dashboard │ Peers │ Transports │ Identity   │
├──────────────────────────────────────────────┤
│              FipsViewModel                   │
├──────────────────────────────────────────────┤
│            TransportManager                  │
│  ┌─────┐ ┌─────┐ ┌──────┐ ┌─────┐          │
│  │ UDP │ │ BLE │ │Wi-Fi │ │ NFC │          │
│  │     │ │     │ │Direct│ │     │          │
│  └──┬──┘ └──┬──┘ └──┬───┘ └──┬──┘          │
│  ┌──┴──┐ ┌──┴──┐                            │
│  │Audio│ │Video│  ← FSK modem / QR codes    │
│  └──┬──┘ └──┬──┘                            │
├─────┴───────┴────────────────────────────────┤
│             FipsCore (JNI)                   │
├──────────────────────────────────────────────┤
│        libfips_android.so (Rust)             │
│  Identity │ Noise IK │ Protocol │ Routing   │
│  secp256k1  ChaCha20    Wire fmt   Tree/Bloom│
└──────────────────────────────────────────────┘
```

## Transports

| Transport | Channel | Range | Throughput | Use Case |
|-----------|---------|-------|------------|----------|
| **UDP** | Internet/LAN | Global | High | Primary mesh transport |
| **BLE** | Bluetooth LE | ~100m | Low (~1 KB/s) | Nearby peer mesh |
| **Wi-Fi Direct** | P2P Wi-Fi | ~200m | High | Local group mesh |
| **NFC** | Near Field | ~10cm | Very Low | Peer bootstrap/key exchange |
| **Audio** | Sound (FSK) | ~10m | Very Low (~37 B/s) | Airgapped environments |
| **Video** | QR codes | Visual | Low | Offline data transfer |

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Rust toolchain with Android targets
- Android NDK 26+
- cargo-ndk

### Setup Rust

```bash
# Install Rust Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Install cargo-ndk
cargo install cargo-ndk
```

### Build Native Libraries

```bash
cd rust/fips-android

# Build for ARM64
cargo ndk -t aarch64-linux-android -o ../../app/src/main/jniLibs build --release

# Build for ARMv7
cargo ndk -t armv7-linux-androideabi -o ../../app/src/main/jniLibs build --release
```

### Build APK

```bash
# From project root
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

### Build Everything

```bash
# Build Rust + Android in one go
./gradlew buildRustLibs assembleDebug
```

## Project Structure

```
fipsa/
├── app/src/main/
│   ├── java/fi/fips/node/
│   │   ├── core/              # JNI bridge (FipsCore, PacketCallback)
│   │   ├── transport/         # Transport implementations
│   │   │   ├── FipsTransport.kt      # Common interface
│   │   │   ├── UdpTransport.kt       # UDP/Internet
│   │   │   ├── BleTransport.kt       # Bluetooth LE
│   │   │   ├── WifiDirectTransport.kt # Wi-Fi P2P
│   │   │   ├── NfcTransport.kt       # NFC beam + HCE
│   │   │   ├── AudioTransport.kt     # FSK audio modem
│   │   │   ├── VideoTransport.kt     # QR code encode/decode
│   │   │   └── TransportManager.kt   # Lifecycle + routing
│   │   ├── ui/                # Jetpack Compose UI
│   │   │   ├── screens/       # Dashboard, Peers, Transports, Identity, Settings
│   │   │   ├── theme/         # Material 3 theme
│   │   │   └── navigation/    # Bottom nav routing
│   │   ├── service/           # Foreground service
│   │   └── MainActivity.kt
│   ├── res/                   # Android resources
│   └── AndroidManifest.xml
├── rust/fips-android/         # Rust JNI bridge
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs             # JNI entry points
│       ├── identity.rs        # Nostr keypair identity
│       ├── noise.rs           # Noise IK handshake + encryption
│       ├── protocol.rs        # Wire format messages
│       └── node.rs            # Simplified mesh node
└── .github/workflows/         # CI/CD
```

## Connecting to the Network

Public bootstrap node:

```
npub: npub1e2z5mnxk237an7uwca4sxn6mv4a9v66djw9xwsxykf9f42k5ckvqn8z8jz
addr: node.fips.atlantislabs.space:4000
```

Add this in Settings > Bootstrap Peers to connect to the FIPS mesh.

## How It Works

1. **Identity**: Each node has a Nostr keypair (secp256k1). The `npub` is the public identity, `nsec` is the secret key.

2. **Link Encryption**: Peers authenticate using Noise IK handshakes, establishing encrypted links with ChaCha20-Poly1305.

3. **Routing**: A spanning tree protocol assigns coordinates to each node. Bloom filters track reachability. Packets are routed greedily by tree distance.

4. **Multi-Transport**: Packets flow through whichever transport reaches the destination. The TransportManager routes outbound packets from the native node to the correct transport channel.

## Upstream

- [FIPS Protocol](https://github.com/jmcorgan/fips) — The Rust reference implementation
- FIPS uses Nostr identities (NIP-01) for cryptographic peer identity

## License

MIT
