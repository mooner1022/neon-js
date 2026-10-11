NOTE = "there is no file system: makeSyncWrite's writes are unavailable"
REPLACEMENTS = [
    ("const { writeBuffer } = internalBinding('fs');",
     "const writeBuffer = () => {\n"
     "  throw new (require('internal/errors').codes.ERR_UNAVAILABLE)('writing to a file descriptor');\n"
     "};"),
]
