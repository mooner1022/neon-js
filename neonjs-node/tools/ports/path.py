NOTE = "path.matchesGlob needs Node's minimatch, which is not here"
REPLACEMENTS = [
    ("const lazyMinimatch = getLazy(() => require('internal/deps/minimatch/index'));",
     "const lazyMinimatch = getLazy(() => {\n"
     "  throw new (require('internal/errors').codes.ERR_UNAVAILABLE)('path.matchesGlob');\n"
     "});"),
]
