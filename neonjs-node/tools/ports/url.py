NOTE = ("IDNA comes from the engine, and the WHATWG URL format (url.format of a URL) is written over the web URL "
        "(Node's is native)")
REPLACEMENTS = [
    ("const { toASCII } = internalBinding('encoding_binding');",
     "// UTS #46 ToASCII from the engine ('' when it fails)\nconst toASCII = (hostname) => binding.toASCII(hostname);"),
    ("const bindingUrl = internalBinding('url');", """// Node's native url.format of a WHATWG URL: its href without what the options leave out
function formatWhatwgURL(href, hash, unicode, search, auth) {
  const url = new URL(href);
  if (!hash) url.hash = '';
  if (!search) url.search = '';
  if (!auth) {
    url.username = '';
    url.password = '';
  }
  let out = url.href;
  const hostname = url.hostname;
  if (unicode && hostname !== '') {
    const decoded = binding.domainToUnicode(hostname);
    if (decoded !== hostname) {
      let start = out.indexOf('//') + 2;
      if (url.username !== '' || url.password !== '') start = out.indexOf('@', start) + 1;
      out = out.slice(0, start) + decoded + out.slice(start + hostname.length);
    }
  }
  return out;
}
const bindingUrl = { format: formatWhatwgURL };"""),
    ("const { getOptionValue } = require('internal/options');",
     "// no command line here: --pending-deprecation is off\nconst getOptionValue = () => false;"),
]
