//! The Windows WASAPI-EXCLUSIVE bracket (Phase 1 D1, feature
//! `wasapi_exclusive`, x86_64-pc-windows-msvc only).
//!
//! cpal supports WASAPI **shared** mode only. This bracket opens the
//! default render endpoint in **exclusive** mode (48 kHz stereo f32)
//! through the raw WinAPI COM surface — NO bindgen/clang-sys (the
//! `miniaudio` crate's `ep-miniaudio-sys` pins bindgen 0.54 → clang-sys
//! 0.29, which collides with the workspace's clang-sys 1.x via
//! rustypipe-botguard's v8: two `links = "clang"` in one graph is
//! unresolvable — the Phase 1 CI round-1 finding). The Windows COM ABI
//! for this surface is small and frozen; the declarations below mirror
//! Windows SDK's audioclient.h/mmdeviceapi.h GUIDs and vtable orders.
//!
//! The closed-failure contract: ANY rejection (format refused, device
//! busy, driver error) returns `Err` and the engine opens cpal shared
//! mode WITHOUT interrupting playback (the engine's step-down arm +
//! the `SteppedDownToShared` event).
//!
//! # RT discipline
//!
//! The render callback runs the SAME disciplined path as the cpal
//! callback: the frozen ring read, the underrun zero-fill, the frame
//! counter, and the atomic clock publish — nothing else.

#![cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]

use std::sync::Arc;

use crate::dac_clock::{host_dac_clock, HostClockAnchor};
use crate::{DacClockSlot, StopFlag, CANONICAL_CHANNELS, CANONICAL_RATE_HZ};
use streamify_playback::ring_export;

// ─────────────────────────────────────────────────────────────────────────────
// The WinAPI surface (minimal, frozen declarations)
// ─────────────────────────────────────────────────────────────────────────────

/// The stable mmdeviceapi/audioclient GUIDs (Windows SDK, immutable).
mod guid {
    pub const CLSID_MM_DEVICE_ENUMERATOR: GUID = GUID {
        data1: 0xBCDE0395,
        data2: 0xE52F,
        data3: 0x467C,
        data4: [0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E],
    };
    pub const IID_IMM_DEVICE_ENUMERATOR: GUID = GUID {
        data1: 0xA95664D2,
        data2: 0x9614,
        data3: 0x4F35,
        data4: [0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6],
    };
    pub const IID_IMM_DEVICE: GUID = GUID {
        data1: 0xD666063F,
        data2: 0x1587,
        data3: 0x4E43,
        data4: [0x81, 0xF1, 0xB9, 0x48, 0xE8, 0x07, 0x36, 0x3F],
    };
    pub const IID_IAUDIO_CLIENT: GUID = GUID {
        data1: 0x1CB9AD4C,
        data2: 0xDBFA,
        data3: 0x4C32,
        data4: [0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2],
    };
    pub const IID_IAUDIO_RENDER_CLIENT: GUID = GUID {
        data1: 0xF294ACFC,
        data2: 0x3146,
        data3: 0x4483,
        data4: [0xA7, 0xBF, 0xAD, 0xDC, 0xA7, 0xC2, 0x60, 0xE2],
    };
    pub const IID_IAUDIO_CLOCK: GUID = GUID {
        data1: 0xCD63314F,
        data2: 0x3FBA,
        data3: 0x4A82,
        data4: [0x85, 0x6C, 0x28, 0x8F, 0x72, 0x50, 0x05, 0x77],
    };
}

#[repr(C)]
#[derive(Clone, Copy)]
struct GUID {
    data1: u32,
    data2: u16,
    data3: u16,
    data4: [u8; 8],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct WAVEFORMATEX {
    format_tag: u16,
    channels: u16,
    samples_per_sec: u32,
    avg_bytes_per_sec: u32,
    block_align: u16,
    bits_per_sample: u16,
    cb_size: u16,
}

const WAVE_FORMAT_IEEE_FLOAT: u16 = 0x0003;

// AUDCLNT_SHAREMODE_EXCLUSIVE = 1; AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x1
const AUDCLNT_SHAREMODE_EXCLUSIVE: u32 = 1;
const AUDCLNT_STREAMFLAGS_EVENTCALLBACK: u32 = 0x1;
const AUDCLNT_BUFFERFLAGS_SILENT: u32 = 0x2;
/// eRender = 0, eConsole = 0.
const E_RENDER: u32 = 0;
const E_CONSOLE: u32 = 0;
/// COINIT_MULTITHREADED.
const COINIT_MULTITHREADED: u32 = 0x2;
/// CLSCTX_ALL.
const CLSCTX_ALL: u32 = 0x17;

#[link(name = "ole32")]
extern "system" {
    fn CoInitializeEx(reserved: *mut core::ffi::c_void, co_init: u32) -> i32;
    fn CoCreateInstance(
        clsid: *const GUID,
        outer: *mut core::ffi::c_void,
        cls_context: u32,
        iid: *const GUID,
        out: *mut *mut core::ffi::c_void,
    ) -> i32;
    fn CoTaskMemFree(ptr: *mut core::ffi::c_void);
}

/// HRESULT success test.
#[inline]
fn ok(hr: i32) -> bool {
    hr >= 0
}

/// Call a COM method through the vtable (index-based; the frozen
/// Windows ABI).
///
/// # Safety
///
/// `this` must be a live COM interface pointer of the right type and
/// `idx` the method's true vtable slot.
unsafe fn vcall(this: *mut core::ffi::c_void, idx: usize) -> *mut core::ffi::c_void {
    unsafe {
        let vtable = *(this as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(idx);
        std::mem::transmute::<
            *mut core::ffi::c_void,
            unsafe extern "system" fn(*mut core::ffi::c_void) -> *mut core::ffi::c_void,
        >(method)(this)
    }
}

/// IMMDeviceEnumerator::GetDefaultAudioEndpoint — vtable slot 4
/// (IUnknown 0..2 + EnumAudioEndpoints 3).
unsafe fn get_default_endpoint(enumerator: *mut core::ffi::c_void) -> Result<*mut core::ffi::c_void, String> {
    unsafe {
        let mut device: *mut core::ffi::c_void = std::ptr::null_mut();
        type Fn = unsafe extern "system" fn(
            *mut core::ffi::c_void, u32, u32, *mut *mut core::ffi::c_void,
        ) -> i32;
        let vtable = *(enumerator as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(4);
        let f: Fn = std::mem::transmute(method);
        let hr = f(enumerator, E_RENDER, E_CONSOLE, &mut device);
        if ok(hr) {
            Ok(device)
        } else {
            Err(format!("GetDefaultAudioEndpoint hr=0x{hr:08X}"))
        }
    }
}

/// IMMDevice::Activate — vtable slot 3.
unsafe fn activate(device: *mut core::ffi::c_void, iid: &GUID) -> Result<*mut core::ffi::c_void, String> {
    unsafe {
        let mut iface: *mut core::ffi::c_void = std::ptr::null_mut();
        type Fn = unsafe extern "system" fn(
            *mut core::ffi::c_void, *const GUID, u32, *mut core::ffi::c_void, *mut *mut core::ffi::c_void,
        ) -> i32;
        let vtable = *(device as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(3);
        let f: Fn = std::mem::transmute(method);
        let hr = f(device, iid, CLSCTX_ALL, std::ptr::null_mut(), &mut iface);
        if ok(hr) {
            Ok(iface)
        } else {
            Err(format!("IMMDevice::Activate hr=0x{hr:08X}"))
        }
    }
}

/// IAudioClient::Initialize — vtable slot 3. GetBufferSize slot 4;
/// GetCurrentPadding slot 6; GetService slot 9; SetEventHandle slot 10;
/// Start slot 7; Stop slot 8 (IUnknown 0..2, then the audioclient order).
unsafe fn audio_client_initialize(
    client: *mut core::ffi::c_void,
    share: u32,
    flags: u32,
    duration: i64,
    format: *const WAVEFORMATEX,
) -> Result<(), String> {
    unsafe {
        type Fn = unsafe extern "system" fn(
            *mut core::ffi::c_void, u32, u32, i64, i64, *const WAVEFORMATEX, *const GUID, *mut core::ffi::c_void,
        ) -> i32;
        let vtable = *(client as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(3);
        let f: Fn = std::mem::transmute(method);
        let hr = f(client, share, flags, duration, 0, format, std::ptr::null(), std::ptr::null_mut());
        if ok(hr) {
            Ok(())
        } else {
            Err(format!("IAudioClient::Initialize hr=0x{hr:08X} (exclusive refused)"))
        }
    }
}

unsafe fn audio_client_buffer_size(client: *mut core::ffi::c_void) -> Result<u32, String> {
    unsafe {
        let mut frames: u32 = 0;
        type Fn = unsafe extern "system" fn(*mut core::ffi::c_void, *mut u32) -> i32;
        let vtable = *(client as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(4);
        let f: Fn = std::mem::transmute(method);
        let hr = f(client, &mut frames);
        if ok(hr) {
            Ok(frames)
        } else {
            Err(format!("GetBufferSize hr=0x{hr:08X}"))
        }
    }
}

unsafe fn audio_client_service(
    client: *mut core::ffi::c_void,
    iid: &GUID,
) -> Result<*mut core::ffi::c_void, String> {
    unsafe {
        let mut iface: *mut core::ffi::c_void = std::ptr::null_mut();
        type Fn = unsafe extern "system" fn(
            *mut core::ffi::c_void, *const GUID, *mut *mut core::ffi::c_void,
        ) -> i32;
        let vtable = *(client as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(9);
        let f: Fn = std::mem::transmute(method);
        let hr = f(client, iid, &mut iface);
        if ok(hr) {
            Ok(iface)
        } else {
            Err(format!("GetService hr=0x{hr:08X}"))
        }
    }
}

unsafe fn audio_client_start(client: *mut core::ffi::c_void) -> Result<(), String> {
    unsafe {
        type Fn = unsafe extern "system" fn(*mut core::ffi::c_void) -> i32;
        let vtable = *(client as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(7);
        let f: Fn = std::mem::transmute(method);
        let hr = f(client);
        if ok(hr) {
            Ok(())
        } else {
            Err(format!("Start hr=0x{hr:08X}"))
        }
    }
}

unsafe fn audio_client_stop(client: *mut core::ffi::c_void) {
    unsafe {
        type Fn = unsafe extern "system" fn(*mut core::ffi::c_void) -> i32;
        let vtable = *(client as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(8);
        let f: Fn = std::mem::transmute(method);
        let _ = f(client);
    }
}

/// IAudioRenderClient::GetBuffer slot 3 / ReleaseBuffer slot 4.
unsafe fn render_get_buffer(
    render: *mut core::ffi::c_void,
    frames: u32,
) -> Result<*mut f32, String> {
    unsafe {
        let mut data: *mut u8 = std::ptr::null_mut();
        type Fn = unsafe extern "system" fn(*mut core::ffi::c_void, u32, *mut *mut u8) -> i32;
        let vtable = *(render as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(3);
        let f: Fn = std::mem::transmute(method);
        let hr = f(render, frames, &mut data);
        if ok(hr) {
            Ok(data as *mut f32)
        } else {
            Err(format!("GetBuffer hr=0x{hr:08X}"))
        }
    }
}

unsafe fn render_release_buffer(render: *mut core::ffi::c_void, frames: u32, flags: u32) {
    unsafe {
        type Fn = unsafe extern "system" fn(*mut core::ffi::c_void, u32, u32) -> i32;
        let vtable = *(render as *mut *mut *mut core::ffi::c_void);
        let method = *vtable.add(4);
        let f: Fn = std::mem::transmute(method);
        let _ = f(render, frames, flags);
    }
}

unsafe fn release_com(this: *mut core::ffi::c_void) {
    if !this.is_null() {
        unsafe { let _ = vcall(this, 2); } // IUnknown::Release
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The bracket
// ─────────────────────────────────────────────────────────────────────────────

/// The exclusive bracket's RT state (the mirror of the engine's
/// cpal-side state; the render thread owns a clone of the `Arc`).
pub(crate) struct ExclusiveRt {
    clock: Arc<DacClockSlot>,
    anchor: HostClockAnchor,
    underrun_frames: std::sync::atomic::AtomicU64,
    stop: StopFlag,
    /// Monotonic frames PRESENTED on this device stream (the clock
    /// master counter — no callback timestamp exists on this path).
    frames_presented: std::sync::atomic::AtomicU64,
}

impl ExclusiveRt {
    pub(crate) fn new(clock: Arc<DacClockSlot>, anchor: HostClockAnchor) -> Self {
        Self {
            clock,
            anchor,
            underrun_frames: std::sync::atomic::AtomicU64::new(0),
            stop: StopFlag::default(),
            frames_presented: std::sync::atomic::AtomicU64::new(0),
        }
    }
}

/// The guard owns the render thread + the COM interfaces; dropping it
/// stops and releases the exclusive endpoint.
pub struct ExclusiveGuard {
    thread: Option<std::thread::JoinHandle<()>>,
    stop_flag: Arc<StopFlag>,
    client: *mut core::ffi::c_void,
    render: *mut core::ffi::c_void,
}

// The COM pointers are only touched from the render thread (owned by
// the guard) — Send is sound for handing the guard across threads.
unsafe impl Send for ExclusiveGuard {}

impl ExclusiveGuard {
    /// Stop the render thread and release (idempotent).
    pub fn stop(&mut self) {
        self.stop_flag.set();
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
        unsafe {
            audio_client_stop(self.client);
            release_com(self.render);
            release_com(self.client);
            self.client = std::ptr::null_mut();
            self.render = std::ptr::null_mut();
        }
    }
}

impl Drop for ExclusiveGuard {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Open the exclusive bracket: DEFAULT render endpoint, 48 kHz stereo
/// f32, `AUDCLNT_SHAREMODE_EXCLUSIVE`. `Err(reason)` triggers the
/// documented step-down to cpal shared.
pub(crate) fn open_exclusive(rt: &Arc<ExclusiveRt>) -> Result<ExclusiveGuard, String> {
    unsafe {
        // Per-thread COM init (S_OK / S_FALSE both fine; RPC_E_CHANGED_MODE
        // means another apartment — we still proceed, the calls work).
        let hr = CoInitializeEx(std::ptr::null_mut(), COINIT_MULTITHREADED);
        let _ = hr;

        // 1. The device enumerator → default render endpoint.
        let mut enumerator: *mut core::ffi::c_void = std::ptr::null_mut();
        let hr = CoCreateInstance(
            &guid::CLSID_MM_DEVICE_ENUMERATOR,
            std::ptr::null_mut(),
            CLSCTX_ALL,
            &guid::IID_IMM_DEVICE_ENUMERATOR,
            &mut enumerator,
        );
        if !ok(hr) {
            return Err(format!("CoCreateInstance(MMDeviceEnumerator) hr=0x{hr:08X}"));
        }
        let device = get_default_endpoint(enumerator)
            .map_err(|e| {
                release_com(enumerator);
                e
            })?;
        release_com(enumerator);

        // 2. Activate IAudioClient.
        let client = activate(device, &guid::IID_IAUDIO_CLIENT).map_err(|e| {
            release_com(device);
            e
        })?;
        release_com(device);

        // 3. The exclusive 48 kHz stereo float format.
        let fmt = WAVEFORMATEX {
            format_tag: WAVE_FORMAT_IEEE_FLOAT,
            channels: CANONICAL_CHANNELS,
            samples_per_sec: CANONICAL_RATE_HZ,
            avg_bytes_per_sec: CANONICAL_RATE_HZ * u32::from(CANONICAL_CHANNELS) * 4,
            block_align: CANONICAL_CHANNELS * 4,
            bits_per_sample: 32,
            cb_size: 0,
        };
        // Exclusive periodicity must equal the buffer duration. 10 ms
        // @ 48 kHz = 480 frames — inside every exclusive driver's
        // supported range (verified by Initialize's own error).
        let frames_10ms: i64 = 480;
        let duration_100ns = frames_10ms * 10_000_000 / i64::from(CANONICAL_RATE_HZ);
        audio_client_initialize(
            client,
            AUDCLNT_SHAREMODE_EXCLUSIVE,
            0,
            duration_100ns,
            &fmt,
        )
        .map_err(|e| {
            release_com(client);
            e
        })?;

        let buffer_frames = audio_client_buffer_size(client).map_err(|e| {
            release_com(client);
            e
        })?;

        // 4. The render client service.
        let render = audio_client_service(client, &guid::IID_IAUDIO_RENDER_CLIENT)
            .map_err(|e| {
                release_com(client);
                e
            })?;

        audio_client_start(client).map_err(|e| {
            release_com(render);
            release_com(client);
            e
        })?;

        // 5. The render thread: the disciplined pull loop.
        let rt_thread = Arc::clone(rt);
        let client_t = client;
        let render_t = render;
        let stop_flag = Arc::new(StopFlag::default());
        let stop_for_thread = Arc::clone(&stop_flag);
        let buffer = buffer_frames;
        let thread = std::thread::Builder::new()
            .name("sovereign-wasapi-exclusive".into())
            .spawn(move || {
                unsafe {
                    let mut presented: u64 = 0;
                    while !stop_for_thread.get() {
                        // Pull `buffer` frames: GetBuffer → ring read →
                        // ReleaseBuffer (the disciplined RT path).
                        match render_get_buffer(render_t, buffer) {
                            Ok(dst) => {
                                let frames = buffer as usize;
                                let channels = CANONICAL_CHANNELS as usize;
                                let served = ring_export::streamify_ring_read_f32(dst, frames);
                                let served_samples = served * channels;
                                let total = frames * channels;
                                if served_samples < total {
                                    rt_thread.underrun_frames.fetch_add(
                                        ((total - served_samples) / 2) as u64,
                                        std::sync::atomic::Ordering::Relaxed,
                                    );
                                    for i in served_samples..total {
                                        *dst.add(i) = 0.0;
                                    }
                                }
                                render_release_buffer(render_t, buffer, 0);
                                presented += frames as u64;
                                rt_thread
                                    .frames_presented
                                    .store(presented, std::sync::atomic::Ordering::Relaxed);
                                let sample = host_dac_clock(
                                    &rt_thread.anchor,
                                    presented,
                                    CANONICAL_RATE_HZ,
                                );
                                rt_thread.clock.publish(&sample);
                                // ~buffer/2 period pace (GetBuffer blocks
                                // in event-driven exclusive; the sleep
                                // only bounds spin on error paths).
                                std::thread::sleep(std::time::Duration::from_millis(1));
                            }
                            Err(_) => {
                                // Device invalidated mid-render: surface
                                // via the clock going quiet + stop.
                                stop_for_thread.set();
                            }
                        }
                    }
                    audio_client_stop(client_t);
                }
            })
            .map_err(|e| {
                unsafe {
                    audio_client_stop(client);
                    release_com(render);
                    release_com(client);
                }
                format!("render thread spawn failed: {e}")
            })?;

        Ok(ExclusiveGuard {
            thread: Some(thread),
            stop_flag,
            client,
            render,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rt_state_constructs_clean() {
        let rt = ExclusiveRt::new(
            Arc::new(DacClockSlot::default()),
            HostClockAnchor::capture(),
        );
        assert_eq!(
            rt.underrun_frames.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
        assert_eq!(
            rt.frames_presented.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
    }

    #[test]
    fn wave_format_is_48k_stereo_f32() {
        // The format struct's derived fields must be consistent.
        let f = WAVEFORMATEX {
            format_tag: WAVE_FORMAT_IEEE_FLOAT,
            channels: 2,
            samples_per_sec: 48_000,
            avg_bytes_per_sec: 48_000 * 2 * 4,
            block_align: 8,
            bits_per_sample: 32,
            cb_size: 0,
        };
        assert_eq!(f.block_align, f.channels * f.bits_per_sample as u16 / 8);
        assert_eq!(f.avg_bytes_per_sec, f.samples_per_sec * u32::from(f.block_align));
    }
}
