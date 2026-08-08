import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Point the dev proxy at any running server: DISPATCHER_PROXY=http://host:port npm run dev
const proxyTarget = process.env.DISPATCHER_PROXY ?? 'http://localhost:8455';

export default defineConfig({
  plugins: [svelte()],
  base: './',
  build: {
    outDir: 'dist',
    target: 'es2022',
    sourcemap: false,
    rollupOptions: { output: { manualChunks: undefined } },
  },
  server: {
    proxy: {
      // SSE streams through http-proxy need idle timeouts off or the stream dies.
      '/api': { target: proxyTarget, changeOrigin: true, timeout: 0, proxyTimeout: 0 },
      '/auth': { target: proxyTarget, changeOrigin: true },
    },
  },
});
