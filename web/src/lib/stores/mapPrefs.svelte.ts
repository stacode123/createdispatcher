/** Map view preferences shared by the header and the canvas. */
export const mapPrefs = $state({
  /** Selected dimension name; '' = first available. */
  dim: '',
  showLabels: true,
  showTrains: true,
  showStations: true,
  showSignals: true,
  /** Diamond markers: live notifications, and sim conflicts during playback. */
  showConflicts: true,
  legendOpen: false,
});
