# The Streamify Philosophy

## What we are building

Streamify is a **free music streaming platform with every premium feature unlocked by default**. No paywalls, no tiers, no "Pro" upgrade dialog standing between a listener and the art. High-fidelity playback, gapless transitions, real-time DSP loudness normalization, distributed live jam sessions, offline libraries, synced lyrics, cross-device sync — features the industry sells as subscriptions — we treat as the baseline entitlement of every user.

We believe access to music is not a luxury good. The business model of gating quality behind payment is an artifact of distribution economics, not of engineering necessity. Once you can deliver studio-grade audio processing on a $150 phone, charging for it becomes a policy choice. We choose the opposite policy.

## Why it is technically serious

"Free" does not mean "cheap." A platform that gives everyone premium features must be *more* engineered than one that monetizes friction:

- **Latency is a feature.** Ultra-low-latency audio (native C++20 SIMD DSP, sub-millisecond lyric timelines, PTP clock sync) is what makes shared listening feel like the same room. We invest in native engines precisely because free users deserve the same millisecond-level care as paying ones.
- **Distributed collaboration is a feature.** Jam sessions run on a peer-to-peer CRDT mesh with leaderless election and Merkle reconciliation — no server rent, no per-seat billing, no infrastructure cost passed to the listener.
- **Privacy is a feature.** Telemetry exists to fix bugs, not to build profiles. Anon keys stay server-side-scoped; nothing about a listener's taste is sold.
- **Longevity is a feature.** Zero-GC Rust hot paths, RAII-only C++, hand-rolled DI with documented init order — this codebase is built to still play music flawlessly on hardware that doesn't exist yet.

## Our non-negotiables

1. **No feature behind a paywall.** If it ships, everyone gets it.
2. **No ad-funded compromise.** The listener is the product's beneficiary, never its inventory.
3. **Correctness over velocity at the boundaries.** JNI contracts, wire formats, and consensus protocols get differential tests and parity gates. Internal iteration can be fast; interfaces cannot be sloppy.
4. **Documentation is part of the definition of done.** Every folder has a README; every engine has a manual; every PR links to the doc it changes.
5. **Respect the sources.** We stream; we do not scrape-and-sell. Fair-use posture, rate limits honored, upstream ToS risks tracked openly in `docs/`.
6. **Open engineering culture.** Issues, audits, and postmortems are public. Findings from `audit-22` shipped as titled PRs with measurement gates — that is the standard, not the exception.

## Legal note

This project provides software and architecture. It does not grant rights to any copyrighted catalog. Users and downstream distributors are responsible for sourcing content lawfully in their jurisdiction. See [LICENSE](LICENSE) and [SECURITY.md](SECURITY.md).

---

*If you intend to contribute, read this page first. Every technical decision in this repo should trace back to one of the principles above.*
