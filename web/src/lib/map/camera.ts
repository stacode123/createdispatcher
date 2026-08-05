/** Pan/zoom state in world blocks; scale = pixels per block. Zoom math mirrors GraphMapScreen. */
export class Camera {
  x = 0;
  z = 0;
  scale = 1;

  toScreenX(worldX: number, width: number): number {
    return (worldX - this.x) * this.scale + width / 2;
  }

  toScreenZ(worldZ: number, height: number): number {
    return (worldZ - this.z) * this.scale + height / 2;
  }

  toWorldX(screenX: number, width: number): number {
    return (screenX - width / 2) / this.scale + this.x;
  }

  toWorldZ(screenY: number, height: number): number {
    return (screenY - height / 2) / this.scale + this.z;
  }

  /** Cursor-anchored zoom: the world point under (mx, my) stays fixed. */
  zoomAt(mx: number, my: number, factor: number, width: number, height: number) {
    const worldX = this.toWorldX(mx, width);
    const worldZ = this.toWorldZ(my, height);
    this.scale = Math.min(12, Math.max(0.02, this.scale * factor));
    this.x = worldX - (mx - width / 2) / this.scale;
    this.z = worldZ - (my - height / 2) / this.scale;
  }

  panBy(dxPx: number, dyPx: number) {
    this.x -= dxPx / this.scale;
    this.z -= dyPx / this.scale;
  }

  fit(bbox: { minX: number; minZ: number; maxX: number; maxZ: number }, width: number, height: number) {
    const spanX = Math.max(1, bbox.maxX - bbox.minX);
    const spanZ = Math.max(1, bbox.maxZ - bbox.minZ);
    this.x = (bbox.minX + bbox.maxX) / 2;
    this.z = (bbox.minZ + bbox.maxZ) / 2;
    this.scale = Math.min(3, Math.min((width - 40) / spanX, (height - 60) / spanZ));
    this.scale = Math.min(12, Math.max(0.02, this.scale));
  }
}

/**
 * ONE camera for the whole app — tab switches must never reset where the user is looking.
 * The fitted/lastFitDim flags live beside it so the initial (and per-dimension) auto-fit
 * happens exactly once, not per MapCanvas mount.
 */
export const mapCamera = new Camera();
export const mapView = { fitted: false, lastFitDim: '' };
