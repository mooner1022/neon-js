NOTE = "path.matchesGlob needs Node's minimatch, which is not here"
REPLACEMENTS = [
    ("const lazyMatchGlobPattern = getLazy(() => require('internal/fs/glob').matchGlobPattern);",
     "const lazyMatchGlobPattern = getLazy(() => {\n"
     "  throw new (require('internal/errors').codes.ERR_UNAVAILABLE)('path.matchesGlob');\n"
     "});"),
]
