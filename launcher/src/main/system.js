// System probes the launcher uses for sane UI defaults.
//
// Today: RAM ceiling. Lets the Settings RAM sliders cap to what the host
// machine actually has, so a user on a 16 GB box can't accidentally allocate
// 32 GB and get a confusing JVM "could not reserve heap" error at launch.

const os = require('os');

/**
 * @returns {{ totalMb: number, freeMb: number, recommendedMaxMb: number }}
 *
 * `recommendedMaxMb` reserves ~25 % of total RAM for the OS and other apps,
 * with a floor of 2 GB and a hard cap matching settings.BOUNDS.maxRam.
 */
function ramInfo() {
  const totalBytes = os.totalmem();
  const freeBytes  = os.freemem();
  const totalMb = Math.round(totalBytes / (1024 * 1024));
  const freeMb  = Math.round(freeBytes  / (1024 * 1024));

  // Reserve 25% for OS/other apps; floor 2 GB, ceiling 64 GB (matches BOUNDS).
  let recommendedMaxMb = Math.floor(totalMb * 0.75);
  recommendedMaxMb = Math.max(2048, Math.min(64 * 1024, recommendedMaxMb));

  return { totalMb, freeMb, recommendedMaxMb };
}

module.exports = { ramInfo };
