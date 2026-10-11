'use strict';
// What the libraries take from Node's lib/internal/encoding.js (MIT license, see NOTICE): the web globals' codecs.

module.exports = {
  TextDecoder: globalThis.TextDecoder,
  TextEncoder: globalThis.TextEncoder,
};
