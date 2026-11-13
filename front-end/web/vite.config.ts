/// <reference types="node" />
import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// wasm facade output (wasm-pack `pkg`) is copied into src/wasm/ by build tooling;
// src/lib/engine.ts imports it dynamically, with the committed stub in
// src/wasm/streamify_wasm.js as a fallback so the web build stays green until
// the real facade lands (W0.4).
export default defineConfig({
  base: '/streamify-apk/',
  plugins: [react()],
  // AudioWorklet modules must be ES modules (audioWorklet.addModule);
  // the sink worklet is imported via `?worker&url` (transport.ts).
  worker: {
    format: 'es',
  },
  build: {
    rollupOptions: {
      input: {
        main: resolve('index.html'),
        canary: resolve('canary.html'),
        // P3b: the BotGuard mint harness — a SEPARATE ES-module entry the
        // hidden srcdoc iframe loads via <script type="module" src=…>.
        // It must be a real rollup INPUT (bundled + TS-stripped); a `?url`
        // import emits the RAW .ts source, which the browser cannot run.
        // Fixed output name (no hash): the main bundle references it by
        // BASE_URL at runtime (see audio/botguard/mintIframe.ts).
        'mint-harness': resolve('src/audio/botguard/mintHarness.ts'),
      },
      output: {
        entryFileNames: (chunk) =>
          chunk.name === 'mint-harness'
            ? 'assets/mint-harness.js'
            : 'assets/[name]-[hash].js',
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
