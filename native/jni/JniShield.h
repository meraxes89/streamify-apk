#pragma once
// ============================================================================
//  JniShield.h — the C++ mirror of the Rust JNI panic-shield house model
//  (C6 / AHEAD WS-C; the C++ twin of `rust/crates/streamify-ffi/src/
//  jni_bridge.rs`'s catch_unwind discipline)
//  (native/jni/JniShield.h)
// ============================================================================
//
//  HOUSE RULE (jni_bridge.rs header, verbatim): "Every entry point is
//  wrapped in `catch_unwind` — a panic must never unwind across the FFI
//  boundary into ART." The same law holds for C++ exceptions crossing a
//  JNI `extern "C"` edge: a C++ exception escaping a JNI function is
//  `std::terminate` at the ABI boundary (extern "C" is implicitly
//  noexcept-unwinding-hostile on every Android ABI we ship) — the whole
//  process dies, uncatchable from Kotlin.
//
//  THE SHIELD. `JNI_SHIELD(sentinel)` opens a `try {` block scoped to the
//  rest of the function body; `JNI_SHIELD_END(sentinel)` closes it with a
//  `catch (...)` that maps ANY escaping C++ exception onto the function's
//  own documented error convention — the exact per-type table the Rust
//  bridges use:
//
//      jint       -> -10   (JNI error sentinel, jni_bridge.rs house value)
//      jlong      -> 0     (null opaque handle)
//      jboolean   -> false
//      jfloat/jdouble -> 0
//      jobject-family -> nullptr
//      void       -> swallowed (empty sentinel: `return ;`)
//
//  Usage (value-returning export — symbol spelled "Java_" + mangled FQN +
//  method name; the placeholder below deliberately avoids a literal
//  Java_* token so scripts/jni_parity_check.py's source scanner does not
//  mistake the doc example for a shipped export):
//
//      extern "C" JNIEXPORT jint JNICALL
//      <mangled export>(JNIEnv* env, jobject, jint x) {
//          JNI_SHIELD(-10);
//          ...body with ordinary `return` statements...
//          JNI_SHIELD_END(-10);
//      }
//
//  Usage (void export — the empty sentinel spelling `return ;` is valid
//  C++ in a void function):
//
//      extern "C" JNIEXPORT void JNICALL
//      <mangled export>(JNIEnv* env, jobject) {
//          JNI_SHIELD();
//          ...body...
//          JNI_SHIELD_END();
//      }
//
//  WHY MACRO PAIRS AND NOT A LAMBDA WRAPPER: JNI bodies are full of
//  `return expr;` statements that must keep returning from the EXPORT
//  itself; an immediately-invoked lambda would force rewriting every
//  return into a result variable across 87+ legacy exports. A try/catch
//  scoped by two macros keeps the retrofit mechanical and reviewable,
//  and `return` inside the try block returns from the enclosing export
//  exactly as before. The unbalanced-brace spelling is the same
//  technique as SCOPE_EXIT-style guards; the enforcement is mechanical:
//  `scripts/jni_panic_boundary_check.py` requires the `JNI_SHIELD` token
//  inside every `Java_*` export body in the C++ tree, exactly as it
//  requires `catch_unwind` in every Rust export body.
//
//  WHAT THE SHIELD DOES NOT DO: it does not touch the JNIEnv pending-
//  exception state (a pending JAVA exception is not a C++ exception; it
//  propagates to the JVM normally when the export returns), and it does
//  not log — matching the Rust house model's `.unwrap_or(sentinel)`
//  exactly (AHEAD §8: shipped artifacts are panic=abort; the shield is
//  the specified contract for every unwind-capable configuration, and
//  silent sentinel mapping is the documented behavior).
// ============================================================================

// Opens the shielded region. SENTINEL is repeated on the matching
// JNI_SHIELD_END so each macro line reads standalone in review.
#define JNI_SHIELD(SENTINEL) \
    try {

// Closes the shielded region started by JNI_SHIELD, mapping any escaping
// C++ exception onto SENTINEL. For void exports call with an empty
// argument: JNI_SHIELD_END() — `return ;` is valid in a void function.
#define JNI_SHIELD_END(SENTINEL) \
    } catch (...) { return SENTINEL; }
