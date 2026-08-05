/**
 * Iterative Douglas-Peucker over a slice of the flat x,z point array.
 * Returns kept local indices (0..count-1), endpoints always included, ascending.
 */
export function douglasPeucker(pts: Float32Array, first: number, count: number, epsilon: number): number[] {
  if (count <= 2) return count === 2 ? [0, 1] : [0];
  const keep = new Uint8Array(count);
  keep[0] = 1;
  keep[count - 1] = 1;
  const stack: [number, number][] = [[0, count - 1]];
  const epsSq = epsilon * epsilon;

  while (stack.length) {
    const [a, b] = stack.pop()!;
    if (b - a < 2) continue;
    const ax = pts[(first + a) * 2];
    const az = pts[(first + a) * 2 + 1];
    const bx = pts[(first + b) * 2];
    const bz = pts[(first + b) * 2 + 1];
    const dx = bx - ax;
    const dz = bz - az;
    const lenSq = dx * dx + dz * dz;
    let worst = -1;
    let worstDistSq = epsSq;
    for (let i = a + 1; i < b; i++) {
      const px = pts[(first + i) * 2] - ax;
      const pz = pts[(first + i) * 2 + 1] - az;
      let distSq;
      if (lenSq === 0) {
        distSq = px * px + pz * pz;
      } else {
        const t = Math.min(1, Math.max(0, (px * dx + pz * dz) / lenSq));
        const ex = px - t * dx;
        const ez = pz - t * dz;
        distSq = ex * ex + ez * ez;
      }
      if (distSq > worstDistSq) {
        worstDistSq = distSq;
        worst = i;
      }
    }
    if (worst >= 0) {
      keep[worst] = 1;
      stack.push([a, worst], [worst, b]);
    }
  }

  const result: number[] = [];
  for (let i = 0; i < count; i++) if (keep[i]) result.push(i);
  return result;
}
