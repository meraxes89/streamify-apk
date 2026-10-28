//! Android LITTLE-core affinity helper (Phase 2).
//!
//! NOTE (Phase 4b CI follow-up, resolved in Phase 5): this module reads
//! sysfs (`/sys/devices/system/cpu/...`) and calls the Linux kernel
//! `sched_setaffinity` / `setpriority` syscalls — APIs that exist on
//! Linux AND Android (bionic) but NOT on Windows/macOS/iOS hosts. It
//! used to compile unconditionally, which blocked every Windows engine
//! build (the desktop-ci.yml probe-gate lane). The durable fix (per the
//! Phase 4b PR description): the real implementation is cfg-gated to
//! `linux` + `android` — its ONLY caller is the Android-gated
//! `jni_bridge.rs` — and every other target compiles the inert stub
//! below, which keeps the module's public API linkable from any host
//! lane (`cargo check` / unit-test binaries) without the Linux-only
//! libc imports.

#[cfg(any(target_os = "linux", target_os = "android"))]
mod imp {
    use libc::{cpu_set_t, sched_setaffinity, CPU_SET, CPU_ZERO};
    use std::fs;

    /// Reads `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
    /// to identify the LITTLE (Efficiency) cluster dynamically across
    /// Qualcomm, MediaTek, Samsung Exynos, and Google Tensor chipsets.
    pub fn get_little_core_ids() -> Vec<usize> {
        let mut core_freqs = Vec::new();

        if let Ok(entries) = fs::read_dir("/sys/devices/system/cpu/") {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with("cpu") && name[3..].parse::<usize>().is_ok() {
                        let cpu_id = name[3..].parse::<usize>().unwrap();
                        let freq_path = path.join("cpufreq/cpuinfo_max_freq");
                        if let Ok(freq_str) = fs::read_to_string(freq_path) {
                            let max_freq = freq_str.trim().parse::<u32>().unwrap_or(0);
                            core_freqs.push((cpu_id, max_freq));
                        }
                    }
                }
            }
        }

        if core_freqs.is_empty() {
            // Fallback for devices without sysfs access: assume lower 4 cores on an 8-core SoC
            return vec![0, 1, 2, 3];
        }

        // Sort by frequency ascending (slowest cores = LITTLE efficiency cluster)
        core_freqs.sort_by_key(|&(_, freq)| freq);

        // Efficiency cluster is typically the lower 50% (or min 2-4 cores)
        let little_count = (core_freqs.len() / 2).max(2);
        core_freqs
            .iter()
            .take(little_count)
            .map(|&(id, _)| id)
            .collect()
    }

    /// Binds the current calling thread strictly to the LITTLE (Efficiency) CPU cores
    /// using the Linux kernel `sched_setaffinity` syscall and lowers thread priority.
    pub fn pin_current_thread_to_little_cores() -> bool {
        let little_cores = get_little_core_ids();
        if little_cores.is_empty() {
            return false;
        }

        unsafe {
            let mut cpuset: cpu_set_t = std::mem::zeroed();
            CPU_ZERO(&mut cpuset);

            for &cpu_id in &little_cores {
                CPU_SET(cpu_id, &mut cpuset);
            }

            // 0 = current thread
            let result = sched_setaffinity(0, std::mem::size_of::<cpu_set_t>(), &cpuset);

            // Lower nice priority for background worker
            libc::setpriority(libc::PRIO_PROCESS, 0, 10);

            result == 0
        }
    }
}

/// Inert stub for every non-Linux/Android target (Windows, macOS, iOS and
/// the host test lanes): the concept of an Android LITTLE cluster does not
/// exist there, so discovery returns no cores and pinning reports failure.
/// Callers already treat both as "best-effort, skip if unavailable".
#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod imp {
    /// No LITTLE/EFFICIENCY cluster exists on this target.
    pub fn get_little_core_ids() -> Vec<usize> {
        Vec::new()
    }

    /// Affinity pinning is unavailable on this target; always a no-op
    /// reporting failure so callers take their fallback path.
    pub fn pin_current_thread_to_little_cores() -> bool {
        false
    }
}

pub use imp::{get_little_core_ids, pin_current_thread_to_little_cores};
