// Streamify Web — Vite configuration (P0 proof phase).
//
// GitHub Pages serves this site under the repository path /streamify-apk/,
// so `base` must stay '/streamify-apk/': Vite prefixes every emitted asset
// URL (JS, CSS, sourcemaps) with it, and the built index.html would
// otherwise request /assets/... and 404 on Pages.

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  base: "/streamify-apk/",
  server: {
    // Expose the dev server on the LAN so desktop/mobile devices on the
    // same network can load the proof build during dogfooding.
    host: true,
  },
  build: {
    // Vite build defaults are intentional for P0 (ES modules, hashed
    // asset names, sourcemaps off in CI output). Tune here in later weeks.
  },
});
