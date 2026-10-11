NOTE = "queueMicrotask is the global one"
REPLACEMENTS = [
    ("queueMicrotask ??= require('internal/process/task_queues').queueMicrotask;",
     "queueMicrotask ??= globalThis.queueMicrotask;"),
]
