//! FIPS Android JNI Bridge
//!
//! Uses the upstream fips crate (core feature) for identity, noise, and
//! protocol types. The JNI bridge handles Android-specific concerns:
//! external packet injection via JNI callbacks and simplified node state.

mod node;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JByteArray};
use jni::sys::{jint, jlong, jstring};
use std::panic;

use crate::node::FipsNode;

/// Initialize logging for Android
fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("fips-native"),
    );
}

/// Create a new FIPS node instance.
/// Returns a handle (pointer) to be passed to subsequent calls.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    nsec: JString,
    callback: JObject,
) -> jlong {
    init_logging();

    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let nsec_str: String = env.get_string(&nsec)
            .expect("Failed to get nsec string")
            .into();

        let callback_global = env.new_global_ref(callback)
            .expect("Failed to create global ref for callback");

        let jvm = env.get_java_vm()
            .expect("Failed to get JavaVM");

        match FipsNode::new(&nsec_str, jvm, callback_global) {
            Ok(node) => {
                let boxed = Box::new(node);
                Box::into_raw(boxed) as jlong
            }
            Err(e) => {
                log::error!("Failed to create FipsNode: {}", e);
                0
            }
        }
    }));

    match result {
        Ok(handle) => handle,
        Err(_) => {
            log::error!("Panic in nativeInit");
            0
        }
    }
}

/// Inject a received packet into the node for processing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeInjectPacket(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    transport_id: jint,
    remote_addr: JString,
) {
    if handle == 0 { return; }

    let node = unsafe { &mut *(handle as *mut FipsNode) };

    let data = match env.convert_byte_array(&data) {
        Ok(d) => d,
        Err(_) => return,
    };

    let addr: String = match env.get_string(&remote_addr) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    node.inject_packet(&data, transport_id as u32, &addr);
}

/// Periodic tick — process timers, send keepalives, etc.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeTick(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 { return; }
    let node = unsafe { &mut *(handle as *mut FipsNode) };
    node.tick();
}

/// Get list of peers as JSON string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeGetPeers(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("[]").unwrap().into_raw();
    }

    let node = unsafe { &*(handle as *mut FipsNode) };
    let json = node.get_peers_json();

    env.new_string(&json)
        .unwrap_or_else(|_| env.new_string("[]").unwrap())
        .into_raw()
}

/// Get node status as JSON string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeGetStatus(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("{}").unwrap().into_raw();
    }

    let node = unsafe { &*(handle as *mut FipsNode) };
    let json = node.get_status_json();

    env.new_string(&json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap())
        .into_raw()
}

/// Shutdown and free the node.
#[unsafe(no_mangle)]
pub extern "system" fn Java_fi_fips_node_core_FipsCore_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 { return; }
    let node = unsafe { Box::from_raw(handle as *mut FipsNode) };
    node.shutdown();
}
