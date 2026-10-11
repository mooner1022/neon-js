NOTE = 'buffer comparison and own keys come from the engine (binding)'
REPLACEMENTS = [
    ("const { compare } = internalBinding('buffer');",
     "const compare = (a, b) => binding.buffer.compare(a, 0, a.length, b, 0, b.length);"),
    ("""const {
  constants: {
    ONLY_ENUMERABLE,
    SKIP_SYMBOLS,
  },
  getOwnNonIndexProperties,
} = internalBinding('util');""", """// the filter bits of Node's getOwnNonIndexProperties
const ONLY_ENUMERABLE = 2;
const SKIP_SYMBOLS = 16;
function getOwnNonIndexProperties(value, filter) {
  const keys = binding.types.ownNonIndexKeys(value, (filter & ONLY_ENUMERABLE) === 0);
  return (filter & SKIP_SYMBOLS) === 0 ? keys : ArrayPrototypeFilter(keys, (k) => typeof k !== 'symbol');
}"""),
]
