NOTE = "call sites and promise handling come from the engine (binding.util); parseEnv is not here yet"
REPLACEMENTS = [
    # `binding` is the libraries' argument here: Node's internalBinding('util') is its util part
    ("const binding = internalBinding('util');", "const utilBinding = binding.util;"),
    ("  return binding.parseEnv(content);", "  return utilBinding.parseEnv(content);"),
    ("    return mapCallSite(binding.getCallSites(frameCount));", "    return mapCallSite(utilBinding.getCallSites(frameCount));"),
    ("  return binding.getCallSites(frameCount);", "  return utilBinding.getCallSites(frameCount);"),
    ("  binding.markPromiseAsHandled(promise);", "  utilBinding.markPromiseAsHandled(promise);"),
]
