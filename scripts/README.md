# `scripts/` — Verification & Probe Scripts

Developer-side harnesses (not shipped; not part of CI except where
`.github/workflows/` calls them out explicitly).

| File | What it is |
|---|---|
| `gauntlet_v2.py` | Full resolver gauntlet (search → resolve → stream). |
| `extreme_extract_test.py` | NewPipe-Extractor extraction checks. |
| `stage1_extract.py` | Stage-1 extraction smoke. |
| `gvs_binding_test.py` | GVS binding checks. |
| `curl_probe.sh` | Quick CDN probes. |
