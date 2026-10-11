NOTE = "the timers and promise handling come from the engine (binding)"
REPLACEMENTS = [
    ("const { TIMEOUT_MAX } = require('internal/timers');", "const TIMEOUT_MAX = 2 ** 31 - 1;"),
    ("const { markPromiseAsHandled } = internalBinding('util');", "const { markPromiseAsHandled } = binding.util;"),
]
