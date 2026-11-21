// r20 smoke: the additive producer edge installs into the SAME slot the
// frozen consumer edge reads (the r19 bin/cdylib split, from the Rust side).
use streamify_playback::ring_export;

#[test]
fn producer_edge_shares_the_frozen_slot() {
    assert_eq!(unsafe { ring_export::streamify_ring_producer_install(1024, 2, 48_000, 48_000) }, 1);
    assert_eq!(ring_export::streamify_ring_channels(), 2, "frozen consumer edge sees the installed ring");
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 0);
    let pcm = vec![0.25f32; 96 * 2];
    let wrote = unsafe { ring_export::streamify_ring_producer_write(pcm.as_ptr(), pcm.len()) };
    assert_eq!(wrote, 192, "write returns SAMPLES (96 frames x 2ch)");
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 96);
    let mut dst = [0f32; 96 * 2];
    let served = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 96) };
    assert_eq!(served, 96);
    assert!(dst.iter().all(|s| *s == 0.25));
    // Degenerate args degrade to 0, never UB:
    assert_eq!(unsafe { ring_export::streamify_ring_producer_write(std::ptr::null(), 10) }, 0);
    assert_eq!(unsafe { ring_export::streamify_ring_producer_write(pcm.as_ptr(), 0) }, 0);
    // Bad geometry is rejected without disturbing the slot:
    assert_eq!(unsafe { ring_export::streamify_ring_producer_install(0, 2, 48_000, 48_000) }, 0);
    // Clear from the producer side:
    unsafe { ring_export::streamify_ring_producer_clear() };
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 0);
}
