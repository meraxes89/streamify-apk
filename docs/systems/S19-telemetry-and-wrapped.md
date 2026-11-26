---
id: S19
title: Telemetry & Wrapped
tier: feature
status: active
owner: telemetry
sources: [streamify-modules/supabase/src/commonMain/kotlin/com/streamify/supabase/SupabaseWrappedSync.kt, composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/home/wrapped/]
tests: [composeApp/src/commonTest/kotlin/com/streamify/supabase/DeviceFleetTelemetryTest.kt]
---

# S19 — Telemetry & Wrapped

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Device fleet registration, listening-stat upload and the Wrapped summary UI.

## Does not own

- Playback events (S06), sync (S11), Supabase schema (S22).

## Current truth

- Wrapped cards are Compose code; the upload path lives in the Supabase module.

## Interfaces

- Playback events → local aggregate → Wrapped sync → cards.

## Decisions and specs

- Spec: [10_telemetry_profiler.md](../architecture/engines/10_telemetry_profiler.md)

## Known limits / debt

- Historical audit found a 100-row-per-cycle cap that could collapse repeated plays.

## Refinement backlog

- Verify the aggregation cap against the current SQL before adding new card types.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
