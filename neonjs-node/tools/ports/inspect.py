NOTE = ("what V8 tells Node natively comes from the engine (binding.types), a host (Java) object is one opaque line, "
        "string widths are the non-ICU ones, and process is loaded when first used")
# URL and URLSearchParams (the web globals) are shown as Node shows its own
APPEND = """
require('internal/url').installInspect();
"""
REPLACEMENTS = [
    ("""const {
  constants: {
    ALL_PROPERTIES,
    ONLY_ENUMERABLE,
    kPending,
    kRejected,
  },
  getOwnNonIndexProperties,
  getPromiseDetails,
  getProxyDetails,
  previewEntries,
  getConstructorName: internalGetConstructorName,
  getExternalValue,
} = internalBinding('util');""", """// Node's internalBinding('util'), from the engine: none of these runs script code
const ALL_PROPERTIES = 0;
const ONLY_ENUMERABLE = 2;
const kPending = 0;
const kRejected = 2;
const engineTypes = binding.types;
function getOwnNonIndexProperties(value, filter) {
  return engineTypes.ownNonIndexKeys(value, filter === ALL_PROPERTIES);
}
function getPromiseDetails(promise) {
  return engineTypes.promiseDetails(promise);
}
function getProxyDetails(value, showProxy = true) {
  const details = engineTypes.proxyDetails(value);
  if (details === undefined) return undefined;
  return showProxy ? details : details[0];
}
function previewEntries(value, isIterator = false) {
  const entries = engineTypes.previewEntries(value);
  // a Map or Set iterator gives [entries, isKeyValue]; asked without isIterator, the entries
  return isIterator || !engineTypes.isMapIterator(value) && !engineTypes.isSetIterator(value) ? entries : entries[0];
}
const internalGetConstructorName = (value) => engineTypes.className(value);
const getExternalValue = () => 0;"""),
    ("const { BuiltinModule } = require('internal/bootstrap/realm');",
     "// the node: libraries, whose frames are dimmed in color\nconst BuiltinModule = { exists: (id) => binding.builtinExists(id) };"),
    ("    workingDirectory = process.cwd();", "    workingDirectory = require('process').cwd();"),
    ("if (internalBinding('config').hasIntl) {", "// (no ICU here: the widths below)\nif (false) {"),
    ("  const icu = internalBinding('icu');", "  const icu = undefined;"),
    # a host (Java) object: its class name only, never walked
    ("""    value = proxy;
  }

  // Provide a hook for user-specified inspect functions.""", """    value = proxy;
  }

  // a host (Java) object is one opaque line: its members are not walked
  const hostTag = engineTypes.hostTag(value);
  if (hostTag !== undefined) return ctx.stylize(hostTag, 'special');

  // Provide a hook for user-specified inspect functions."""),
]
