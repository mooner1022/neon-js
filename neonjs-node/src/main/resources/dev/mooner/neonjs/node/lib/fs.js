'use strict';
// node:fs without a file system: it loads; each function says what is missing (ERR_UNAVAILABLE), through its
// callback when it takes one; exists and existsSync find nothing.

const { unavailableModule, unavailableCallback, unavailableClass, unavailableFunction } = require('internal/unavailable');

const names = [
  'access', 'appendFile', 'chmod', 'chown', 'close', 'copyFile', 'cp', 'fchmod', 'fchown', 'fdatasync', 'fstat',
  'fsync', 'ftruncate', 'futimes', 'glob', 'lchmod', 'lchown', 'link', 'lstat', 'lutimes', 'mkdir', 'mkdtemp',
  'open', 'opendir', 'read', 'readdir', 'readFile', 'readlink', 'readv', 'realpath', 'rename', 'rm', 'rmdir', 'stat',
  'statfs', 'symlink', 'truncate', 'unlink', 'utimes', 'write', 'writeFile', 'writev',
];
const fs = unavailableModule('fs', {
  functions: [...names.map((n) => `${n}Sync`), '_toUnixTimestamp', 'createReadStream', 'createWriteStream',
    'mkdtempDisposableSync', 'openAsBlob', 'openAsBlobSync', 'watch', 'watchFile', 'unwatchFile'],
  classes: ['Dir', 'Dirent', 'Stats', 'ReadStream', 'Utf8Stream', 'WriteStream', 'FileReadStream', 'FileWriteStream'],
});
for (const n of names) fs[n] = unavailableCallback(`fs.${n}`);
fs.realpath.native = unavailableCallback('fs.realpath.native');
fs.realpathSync.native = unavailableFunction('fs.realpathSync.native');

// nothing exists
fs.exists = function exists(path, callback) {
  if (typeof callback === 'function') require('process').nextTick(callback, false);
};
fs.existsSync = function existsSync() {
  return false;
};

fs.constants = Object.freeze({
  UV_FS_SYMLINK_DIR: 1, UV_FS_SYMLINK_JUNCTION: 2, O_RDONLY: 0, O_WRONLY: 1, O_RDWR: 2, UV_DIRENT_UNKNOWN: 0,
  UV_DIRENT_FILE: 1, UV_DIRENT_DIR: 2, UV_DIRENT_LINK: 3, UV_DIRENT_FIFO: 4, UV_DIRENT_SOCKET: 5, UV_DIRENT_CHAR: 6,
  UV_DIRENT_BLOCK: 7, S_IFMT: 61440, S_IFREG: 32768, S_IFDIR: 16384, S_IFCHR: 8192, S_IFBLK: 24576, S_IFIFO: 4096,
  S_IFLNK: 40960, S_IFSOCK: 49152, O_CREAT: 64, O_EXCL: 128, UV_FS_O_FILEMAP: 0, O_NOCTTY: 256, O_TRUNC: 512,
  O_APPEND: 1024, O_DIRECTORY: 65536, O_NOATIME: 262144, O_NOFOLLOW: 131072, O_SYNC: 1052672, O_DSYNC: 4096,
  O_DIRECT: 16384, O_NONBLOCK: 2048, S_IRWXU: 448, S_IRUSR: 256, S_IWUSR: 128, S_IXUSR: 64, S_IRWXG: 56, S_IRGRP: 32,
  S_IWGRP: 16, S_IXGRP: 8, S_IRWXO: 7, S_IROTH: 4, S_IWOTH: 2, S_IXOTH: 1, F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1,
  UV_FS_COPYFILE_EXCL: 1, COPYFILE_EXCL: 1, UV_FS_COPYFILE_FICLONE: 2, COPYFILE_FICLONE: 2,
  UV_FS_COPYFILE_FICLONE_FORCE: 4, COPYFILE_FICLONE_FORCE: 4,
});
Object.defineProperty(fs, 'promises', {
  configurable: true,
  enumerable: true,
  get() { return require('fs/promises'); },
});

module.exports = fs;
