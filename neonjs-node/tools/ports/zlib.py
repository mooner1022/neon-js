NOTE = 'its native handle is internal/zlib (java.util.zip), without Brotli'
REPLACEMENTS = [
    # the handle class, crc32 and constants come from internal/zlib (`binding` is the libraries' argument here)
    ("const binding = internalBinding('zlib');\nconst { crc32: crc32Native } = binding;",
     "const zlibBinding = require('internal/zlib');\nconst { crc32: crc32Native } = zlibBinding;"),
    ("const { owner_symbol } = require('internal/async_hooks').symbols;",
     "const owner_symbol = Symbol('owner_symbol');"),
    ("const constants = internalBinding('constants').zlib;",
     "const constants = zlibBinding.constants;"),
    ("const handle = new binding.Zlib(mode);",
     "const handle = new zlibBinding.Zlib(mode);"),
    ("new binding.BrotliDecoder(mode) : new binding.BrotliEncoder(mode);",
     "new zlibBinding.BrotliDecoder(mode) : new zlibBinding.BrotliEncoder(mode);"),
    ("ObjectDefineProperty(binding.Zlib.prototype, 'jsref', {",
     "ObjectDefineProperty(zlibBinding.Zlib.prototype, 'jsref', {"),
]
