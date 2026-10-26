// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::panic::catch_unwind;

/// Encrypts / Decrypts an audio chunk using a position-dependent stream cipher
#[no_mangle]
pub unsafe extern "C" fn crypt_audio_chunk(
    input_ptr: *const u8,
    output_ptr: *mut u8,
    len: usize,
    key_ptr: *const u8,
    key_len: usize,
    offset: u64,
) -> i32 {
    let result = catch_unwind(|| {
        if input_ptr.is_null()
            || output_ptr.is_null()
            || key_ptr.is_null()
            || len == 0
            || key_len == 0
        {
            return -1;
        }

        let input = std::slice::from_raw_parts(input_ptr, len);
        let output = std::slice::from_raw_parts_mut(output_ptr, len);
        let key = std::slice::from_raw_parts(key_ptr, key_len);

        // Fast streaming XOR cipher with position-dependent key rotation
        for i in 0..len {
            let pos = offset + i as u64;
            let key_byte = key[(pos % (key.len() as u64)) as usize];
            let rotation = ((pos >> 8) & 0xFF) as u8;
            output[i] = input[i] ^ key_byte ^ rotation;
        }

        0
    });

    result.unwrap_or(-2)
}
