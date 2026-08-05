import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Point the dev proxy at any running server: DISPATCHER_PROXY=http://host:port npm run dev
const proxyTarget = process.env.DISPATCHER_PROXY ?? 'http://localhost:8455';

/**
 * The sources the committed dist is built from. Gradle's `verifyWebDist` recomputes exactly
 * this list and this digest without Node, so the two must stay in step — see
 * common/build.gradle.
 */
const SOURCE_DIR = 'src';
const SOURCE_FILES = ['index.html', 'package.json', 'tsconfig.json', 'vite.config.ts'];

function walk(dir: string, out: string[]): string[] {
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else out.push(path);
  }
  return out;
}

/** SHA-256 over "<relative path>\n<sha256 of contents>\n" for every source, path-sorted. */
function sourceDigest(root: string): string {
  const files = [...walk(join(root, SOURCE_DIR), []), ...SOURCE_FILES.map((n) => join(root, n))]
    .map((path) => relative(root, path).split('\\').join('/'))
    .sort();
  const digest = createHash('sha256');
  for (const path of files) {
    digest.update(path);
    digest.update('\n');
    digest.update(createHash('sha256').update(readFileSync(join(root, path))).digest('hex'));
    digest.update('\n');
  }
  return digest.digest('hex');
}

/**
 * Writes dist/.buildinfo.json after a build. Its only job is honesty: the dist in git is a
 * build artifact Gradle cannot regenerate, so this records which sources produced it and
 * lets `./gradlew check` say so when they have moved on since.
 */
function buildInfo(): Plugin {
  return {
    name: 'dispatcher-buildinfo',
    apply: 'build',
    closeBundle() {
      const root = process.cwd();
      const info = { sourceDigest: sourceDigest(root), builtAt: new Date().toISOString() };
      writeFileSync(join(root, 'dist', '.buildinfo.json'), JSON.stringify(info, null, 2) + '\n');
    },
  };
}

export default defineConfig({
  plugins: [svelte(), buildInfo()],
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
