# Streamify Downstream Extension Modules (`streamify-modules/`)

This directory houses all Streamify-specific extensions and integrations that decorate or override SimpMusic's base Compose Multiplatform behaviors.

## Why this directory exists (The Upstream Immunity Guarantee)
By isolating our code into these sibling modules:
1. Upstream code in `composeApp/`, `core/`, `androidApp/`, and `desktopApp/` remains untouched.
2. Updating SimpMusic is as simple as running:
   ```bash
   git fetch upstream
   git merge upstream/dev
   ```
3. Custom capabilities are dynamically bound at runtime using Koin Inversion of Control (`loadKoinModules`).

## Modules Overview

| Module | Role |
|---|---|
| **`:streamify-core-bridge`** | JNI and UniFFI JNA bindings to the sovereign Rust core (`libstreamify_core_rs`) and C++20 engine (`libstreamify_native_core`). |
| **`:streamify-media-hal`** | Real-time audio processors, lock-free SPSC ringbuffers, hardware HAL adapters (WASAPI/PipeWire/Oboe), and `DolbySpatialManager`. |
| **`:streamify-lyrics-slyr`** | SLYR bytecode binary lyrics compiler with Wiener-Khinchin phase correlation and sub-millisecond syllable synchronization. |
| **`:streamify-supabase`** | Supabase PostgREST client, Realtime channels, auth state machine, Admin Command Center, and continuous cloud backup for Wrapped analytics. |
| **`:streamify-jam`** | Reliable WebSocket (`metroproto`) + Supabase Realtime social listening, PTP clock synchronization, and multi-user Blend generator. |
