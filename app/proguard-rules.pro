# Preserve Data Models and Value Classes
-keep class com.streamify.app.data.models.** { *; }
-keep class com.streamify.app.ui.models.** { *; }

# Preserve Native JNI Bridge methods
-keepclassmembers class com.streamify.app.data.NativeBridge {
    public static <methods>;
    private static native <methods>;
    private native <methods>;
    public <methods>;
}

# Phase 4a / MP-HAL05: frozen audio-sink JNI symbol contract
# (com.streamify.core.native_.NativeAudioSink — the class/package names are
# ABI, pinned by native/include/streamify_audio_sinks.h). The generic
# native-members rule below covers it; this explicit pin keeps the frozen
# edge immune to future edits of that generic block.
-keep class com.streamify.core.native_.NativeAudioSink {
    public static native <methods>;
}

# Prevent stripping C++/Rust JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# ExoPlayer / Media3 rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil rules
-keep class coil.** { *; }
-dontwarn coil.**

# ── UniFFI generated bindings & JNA runtime (SEC-06 / GOV-05) ────────────
# The Kotlin UniFFI proxies (uniffi.**, generated from rust/src/streamify.udl
# by generateUniffiKotlinBindings) reach the Rust cdylib exclusively through
# JNA reflection and upcall structures — R8 cannot see those accesses
# statically and WILL strip or rename them into runtime
# NoSuchMethodError/IllegalArgumentException failures in minified release
# builds. Keep the whole generated surface plus the JNA runtime intact.

# Preserve UniFFI generated bindings and JNA runtime
-keep class uniffi.** { *; }
-keep class com.streamify.app.data.uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
}
-dontwarn com.sun.jna.**
