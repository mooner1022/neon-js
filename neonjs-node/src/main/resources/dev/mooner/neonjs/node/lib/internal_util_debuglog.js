'use strict';
// What the libraries take from Node's lib/internal/util/debuglog.js (MIT license, see NOTICE): node:util's debuglog,
// which writes when NODE_DEBUG (NodeOptions.env) names the section.

function debuglog(set, cb) {
  return require('util').debuglog(set, cb);
}

module.exports = {
  debuglog,
};
