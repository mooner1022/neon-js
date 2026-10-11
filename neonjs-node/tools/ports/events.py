NOTE = 'process is loaded when first used (node:process needs this module), and EventTarget listeners come from the engine'
REPLACEMENTS = [
    # an EventTarget here has no maximum of its own until one is set: Node's initial one
    ("""  } else if (typeof emitterOrTarget?.[kMaxEventTargetListeners] === 'number') {
    return emitterOrTarget[kMaxEventTargetListeners];
  }
""", """  } else if (typeof emitterOrTarget?.[kMaxEventTargetListeners] === 'number') {
    return emitterOrTarget[kMaxEventTargetListeners];
  } else if (require('internal/event_target').isEventTarget(emitterOrTarget)) {
    // as Node's EventTarget starts: the default, and none for an AbortSignal
    return emitterOrTarget instanceof globalThis.AbortSignal ? 0 : defaultMaxListeners;
  }
"""),
    ("""const kRejection = SymbolFor('nodejs.rejection');
""", """const kRejection = SymbolFor('nodejs.rejection');

// node:process, which needs this module to load
let processModule;
function lazyProcess() {
  processModule ??= require('process');
  return processModule;
}
"""),
    ("process.nextTick(emitUnhandledRejectionOrErr, that, err, type, args);",
     "lazyProcess().nextTick(emitUnhandledRejectionOrErr, that, err, type, args);"),
    ("process.emitWarning(w);", "lazyProcess().emitWarning(w);"),
    ("""  const { isEventTarget, kEvents } = require('internal/event_target');
  if (isEventTarget(emitterOrTarget)) {
    const root = emitterOrTarget[kEvents]?.get(type);
    const listeners = [];
    let handler = root?.next;
    while (handler?.listener !== undefined) {
      const listener = handler.listener?.deref ?
        handler.listener.deref() : handler.listener;
      listeners.push(listener);
      handler = handler.next;
    }
    return listeners;
  }""", """  const { isEventTarget } = require('internal/event_target');
  if (isEventTarget(emitterOrTarget)) {
    return binding.eventListeners(emitterOrTarget, type);
  }"""),
    ("""  const { isEventTarget, kEvents } = require('internal/event_target');
  if (isEventTarget(emitterOrTarget)) {
    return emitterOrTarget[kEvents]?.get(type)?.size ?? 0;
  }""", """  const { isEventTarget } = require('internal/event_target');
  if (isEventTarget(emitterOrTarget)) {
    return binding.eventListeners(emitterOrTarget, type).length;
  }"""),
]
