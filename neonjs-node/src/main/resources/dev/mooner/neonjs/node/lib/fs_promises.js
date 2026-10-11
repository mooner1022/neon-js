'use strict';
// node:fs/promises without a file system: it loads; each function rejects with what is missing (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

const promises = unavailableModule('fs/promises', {
  promises: ['access', 'appendFile', 'chmod', 'chown', 'copyFile', 'cp', 'glob', 'lchmod', 'lchown', 'link', 'lstat',
    'lutimes', 'mkdir', 'mkdtemp', 'open', 'opendir', 'readdir', 'readFile', 'readlink', 'realpath', 'rename', 'rm',
    'rmdir', 'stat', 'statfs', 'symlink', 'truncate', 'unlink', 'utimes', 'watch', 'writeFile'],
  classes: ['FileHandle'],
});
promises.constants = require('fs').constants;

module.exports = promises;
