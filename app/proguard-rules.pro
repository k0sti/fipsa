# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep FipsCore callback interface
-keep class fi.fips.node.core.PacketCallback { *; }
-keep class fi.fips.node.core.FipsCore { *; }
