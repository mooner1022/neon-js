'use strict';
// node:child_process: a sandbox starts no processes. It loads; each function says so (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

module.exports = unavailableModule('child_process', {
  functions: ['exec', 'execFile', 'execFileSync', 'execSync', 'fork', 'spawn', 'spawnSync'],
  classes: ['ChildProcess'],
});
