NOTE = 'buffer comparison and own keys come from the engine (binding)'
REPLACEMENTS = [
    ("const { compare } = internalBinding('buffer');",
     """// the bytes of two views (any kind, DataViews too), compared as unsigned
const viewBytes = (v) => new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
const compare = (a, b) => {
  const x = viewBytes(a);
  const y = viewBytes(b);
  return binding.buffer.compare(x, 0, x.length, y, 0, y.length);
};"""),
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
  if ((filter & SKIP_SYMBOLS) === 0) return keys;
  const strings = [];
  for (let i = 0; i < keys.length; i++) if (typeof keys[i] !== 'symbol') strings[strings.length] = keys[i];
  return strings;
}"""),
]
