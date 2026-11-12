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
  build: {
    rollupOptions: {
      input: {
        main: resolve('index.html'),
        canary: resolve('canary.html'),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
