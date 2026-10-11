'use strict';
// What the libraries take from Node's lib/internal/event_target.js (MIT license, see NOTICE): EventTarget itself is
// the web global, whose listeners the engine keeps (binding.eventListeners).

const kEvents = Symbol('kEvents');
// the web EventTarget's own option for listeners called even after stopImmediatePropagation
const kResistStopPropagation = Symbol.for('nodejs.event_target.resistStopPropagation');
// listener options of Node's own EventTarget, which the web global ignores
const kWeakHandler = Symbol('kWeak');

function isEventTarget(obj) {
  return binding.isEventTarget(obj);
}

module.exports = {
  isEventTarget,
  kEvents,
  kResistStopPropagation,
  kWeakHandler,
};
