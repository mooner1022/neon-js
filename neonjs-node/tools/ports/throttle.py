NOTE = "the timers and promise handling come from the engine (binding); the loop's time is the process uptime"
REPLACEMENTS = [
    ("const { TIMEOUT_MAX } = require('internal/timers');", "const TIMEOUT_MAX = 2 ** 31 - 1;"),
    ("const timersBinding = internalBinding('timers');",
     "// libuv's loop time: milliseconds of the process uptime\nconst timersBinding = { getLibuvNow: () => Math.floor(binding.uptime() * 1000) };"),
    ("const { markPromiseAsHandled } = internalBinding('util');", "const { markPromiseAsHandled } = binding.util;"),
]
