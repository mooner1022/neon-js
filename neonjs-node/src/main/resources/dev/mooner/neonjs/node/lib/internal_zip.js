'use strict';
// node:zlib's ZIP archives (Node's lib/internal/zip, MIT license, see NOTICE) are not here: the classes and functions
// load and say ERR_UNAVAILABLE when used. The in-memory size ceiling is kept as Node keeps it.

const { unavailableClass, unavailableFunction } = require('internal/unavailable');
const { validateInteger } = require('internal/validators');

function zipClass(name, factories) {
  const C = unavailableClass(`zlib.${name}`);
  for (const f of factories) {
    Object.defineProperty(C, f, {
      configurable: true, writable: true, enumerable: false, value: unavailableFunction(`zlib.${name}.${f}`),
    });
  }
  return C;
}

let maxZipContentSize = 256 * 1024 * 1024;

module.exports = {
  ZipEntry: zipClass('ZipEntry', ['read', 'create', 'createSync', 'createStream', 'createSymlink']),
  ZipFile: zipClass('ZipFile', ['open', 'openSync']),
  ZipBuffer: zipClass('ZipBuffer', []),
  createZipArchive: unavailableFunction('zlib.createZipArchive'),
  createZipArchiveSync: unavailableFunction('zlib.createZipArchiveSync'),
  zipFiles: unavailableFunction('zlib.zipFiles'),
  getMaxZipContentSize() {
    return maxZipContentSize;
  },
  setMaxZipContentSize(size) {
    validateInteger(size, 'size', 0);
    maxZipContentSize = size;
  },
};
