//! wire_le.rs — little-endian wire-field readers (audit #22 item 6).
//!
//! Every datagram parser must be panic-free on hostile bytes. The old
//! `slice[..n].try_into().unwrap()` pattern was safe only while the
//! surrounding length checks stayed exactly in sync with the slice
//! bounds — one refactor away from a panic surface on the recv path
//! (a panicking `recv_loop` task takes the whole mesh node down with
//! it). These readers fold the slice length, the array conversion and
//! the `from_le_bytes` into ONE fallible step, so an ingress site reads
//! `le_u64(&frame[1..9]).ok_or(RejectReason::Malformed)?` and a future
//! bounds drift degrades into a rejected frame instead of a panic.
//!
//! House-style precedent: `parse_beacon` already used `.try_into().ok()?`
//! inline; this module is that same discipline, factored out for every
//! wire codec (gossip IHAVE/GRAFT, governance frames, mesh header,
//! heartbeat).

/// Reads a little-endian `u16` field; `None` unless the slice is
/// exactly 2 bytes.
#[inline]
pub fn le_u16(b: &[u8]) -> Option<u16> {
    b.try_into().ok().map(u16::from_le_bytes)
}

/// Reads a little-endian `u32` field; `None` unless the slice is
/// exactly 4 bytes.
#[inline]
pub fn le_u32(b: &[u8]) -> Option<u32> {
    b.try_into().ok().map(u32::from_le_bytes)
}

/// Reads a little-endian `u64` field; `None` unless the slice is
/// exactly 8 bytes.
#[inline]
pub fn le_u64(b: &[u8]) -> Option<u64> {
    b.try_into().ok().map(u64::from_le_bytes)
}

/// Reads a little-endian `i64` field; `None` unless the slice is
/// exactly 8 bytes.
#[inline]
pub fn le_i64(b: &[u8]) -> Option<i64> {
    b.try_into().ok().map(i64::from_le_bytes)
}

/// Copies a fixed-width byte field; `None` unless the slice length is
/// exactly `N` (identity fields, session ids, signature spans).
#[inline]
pub fn fixed<const N: usize>(b: &[u8]) -> Option<[u8; N]> {
    b.try_into().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readers_reject_wrong_lengths() {
        let b = [0xA5u8; 8];
        for n in 0..9 {
            assert_eq!(le_u16(&b[..n]).is_some(), n == 2, "le_u16 len {n}");
            assert_eq!(le_u32(&b[..n]).is_some(), n == 4, "le_u32 len {n}");
            assert_eq!(le_u64(&b[..n]).is_some(), n == 8, "le_u64 len {n}");
            assert_eq!(le_i64(&b[..n]).is_some(), n == 8, "le_i64 len {n}");
            assert_eq!(fixed::<8>(&b[..n]).is_some(), n == 8, "fixed<8> len {n}");
        }
    }

    #[test]
    fn readers_decode_little_endian() {
        assert_eq!(le_u16(&[0x34, 0x12]), Some(0x1234));
        assert_eq!(le_u32(&[0x78, 0x56, 0x34, 0x12]), Some(0x1234_5678));
        assert_eq!(
            le_u64(&[0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01]),
            Some(0x0123_4567_89AB_CDEF)
        );
        assert_eq!(le_i64(&[0xFF; 8]), Some(-1));
        assert_eq!(fixed::<3>(&[1u8, 2, 3]), Some([1, 2, 3]));
    }
}
