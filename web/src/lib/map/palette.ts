/** Stable per-train hue by id hash, from the current theme's 8-color train palette. */
import { paint } from '../stores/theme.svelte';

export function colorFor(id: string): string {
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) | 0;
  return paint.trains[Math.abs(hash) % paint.trains.length];
}
