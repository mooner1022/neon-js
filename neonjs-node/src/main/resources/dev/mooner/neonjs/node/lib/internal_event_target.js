'use strict';
// What the libraries take from Node's lib/internal/event_target.js (MIT license, see NOTICE): EventTarget itself is
// the web global, whose listeners the engine keeps (binding.eventListeners).

const kEvents = Symbol('kEvents');
const kResistStopPropagation = Symbol('kResistStopPropagation');

function isEventTarget(obj) {
  return binding.isEventTarget(obj);
}

module.exports = {
  isEventTarget,
  kEvents,
  kResistStopPropagation,
};
