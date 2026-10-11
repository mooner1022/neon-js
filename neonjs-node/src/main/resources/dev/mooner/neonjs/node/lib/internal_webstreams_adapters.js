'use strict';
// Node's lib/internal/webstreams/adapters.js converts between Node's streams and the web's: there is no
// ReadableStream here yet, so Readable.fromWeb / toWeb and the like say so (ERR_UNAVAILABLE).

const { unavailableFunction } = require('internal/unavailable');

const names = [
  'newStreamReadableFromReadableStream', 'newReadableStreamFromStreamReadable', 'newStreamWritableFromWritableStream',
  'newWritableStreamFromStreamWritable', 'newStreamDuplexFromReadableWritablePair', 'newReadableWritablePairFromDuplex',
  'newWritableStreamFromStreamBase', 'newReadableStreamFromStreamBase',
];
for (const name of names) module.exports[name] = unavailableFunction(`stream web adapters (${name})`);
