NOTE = 'the native decoder is the engine binding'
REPLACEMENTS = [
    ("} = internalBinding('string_decoder');", "} = binding.stringDecoder;"),
]
