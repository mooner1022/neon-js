'use strict';
// node:stream/web: the WHATWG streams are not there yet; the module loads, and its classes say so (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

module.exports = unavailableModule('stream/web', {
  classes: [
    'ReadableStream', 'ReadableStreamDefaultReader', 'ReadableStreamBYOBReader', 'ReadableStreamBYOBRequest',
    'ReadableByteStreamController', 'ReadableStreamDefaultController', 'TransformStream',
    'TransformStreamDefaultController', 'WritableStream', 'WritableStreamDefaultWriter',
    'WritableStreamDefaultController', 'ByteLengthQueuingStrategy', 'CountQueuingStrategy', 'TextEncoderStream',
    'TextDecoderStream', 'CompressionStream', 'DecompressionStream',
  ],
});
