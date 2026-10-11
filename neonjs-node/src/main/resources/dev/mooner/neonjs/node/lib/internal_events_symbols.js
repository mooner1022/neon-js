// Ported from Node.js v22.12.0 lib/internal/events/symbols.js (MIT license, see NOTICE).
'use strict';

const {
  Symbol,
} = primordials;

const kFirstEventParam = Symbol('kFirstEventParam');

module.exports = {
  kFirstEventParam,
};
