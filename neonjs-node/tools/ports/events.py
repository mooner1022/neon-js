NOTE = 'process is loaded when first used (node:process needs this module), and EventTarget listeners come from the engine'
REPLACEMENTS = [
    # an EventTarget here has no maximum of its own until one is set: the default
    ("""  } else if (emitterOrTarget?.[kMaxEventTargetListeners]) {
    return emitterOrTarget[kMaxEventTargetListeners];
  }
""", """  } else if (emitterOrTarget?.[kMaxEventTargetListeners]) {
    return emitterOrTarget[kMaxEventTargetListeners];
  } else if (require('internal/event_target').isEventTarget(emitterOrTarget)) {
    return defaultMaxListeners;
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
    const root = emitterOrTarget[kEvents].get(type);
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
]
