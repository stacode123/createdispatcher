/** One-shot camera focus requests (notification clicks, badge clicks) consumed by MapCanvas. */
export const mapFocus = $state({ seq: 0, x: 0, z: 0, dim: '' });

export function focusMap(x: number, z: number, dim: string) {
  mapFocus.x = x;
  mapFocus.z = z;
  mapFocus.dim = dim;
  mapFocus.seq++;
}
