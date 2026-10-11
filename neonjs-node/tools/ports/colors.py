NOTE = 'the color depth is the stream\'s own (there is no internal/tty)'
REPLACEMENTS = [
    ("""let internalTTy;
function lazyInternalTTY() {
  internalTTy ??= require('internal/tty');
  return internalTTy;
}
""", ""),
    ("""    if (process.env.FORCE_COLOR !== undefined) {
      return lazyInternalTTY().getColorDepth() > 2;
    }""", """    if (process.env.FORCE_COLOR !== undefined) {
      const force = process.env.FORCE_COLOR;
      return force !== '0' && force !== 'false';
    }"""),
]
