NOTE = "NODE_DEBUG is NodeOptions.env's, read when the module loads; there are no trace events here"
REPLACEMENTS = [
    ("""const {
  categoryEnabledChecker,
  trace,
  nodeTraceEventCategory,
  kAsyncBegin,
  kAsyncEnd,
  kTraceInstant,
} = require('internal/trace_events');""", """// no trace events here: no category is enabled
const categoryEnabledChecker = () => () => false;
const trace = () => {};
const nodeTraceEventCategory = (category) => category;
const kAsyncBegin = 'b';
const kAsyncEnd = 'e';
const kTraceInstant = 'n';"""),
]
# Node initializes it before user code from process.env.NODE_DEBUG
APPEND = "\ninitializeDebugEnv(process.env.NODE_DEBUG);\n"
