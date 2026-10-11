'use strict';
// What the libraries take from Node's lib/internal/buffer.js (MIT license, see NOTICE): FastBuffer, the class of
// node:buffer's Buffers (Buffer's @@species).

const { Buffer } = require('buffer');

module.exports = {
  FastBuffer: Buffer[Symbol.species],
};
