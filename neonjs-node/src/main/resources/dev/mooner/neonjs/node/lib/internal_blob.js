'use strict';
// What the libraries take from Node's lib/internal/blob.js (MIT license, see NOTICE): Blob is the web global.

const { Blob } = globalThis;

function isBlob(object) {
  return object instanceof Blob;
}

module.exports = {
  Blob,
  isBlob,
};
