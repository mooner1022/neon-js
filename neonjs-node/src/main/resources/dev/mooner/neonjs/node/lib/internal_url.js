'use strict';
// What the libraries take from Node's lib/internal/url.js (MIT license, see NOTICE); URL itself is the web global.

// a URL as Node tells one (duck-typed, so that URLs of other realms count)
function isURL(self) {
  return Boolean(self?.href && self.protocol && self.auth === undefined && self.path === undefined);
}

// a file: URL of a POSIX path (made absolute against process.cwd()), its special characters escaped
function pathToFileURL(filepath) {
  let resolved = filepath.startsWith('/') ? filepath : `${require('process').cwd().replace(/\/$/, '')}/${filepath}`;
  if (filepath.endsWith('/') && !resolved.endsWith('/')) resolved += '/';
  const escaped = resolved.replace(/%/g, '%25').replace(/\\/g, '%5C').replace(/\n/g, '%0A').replace(/\r/g, '%0D')
    .replace(/\t/g, '%09').replace(/\?/g, '%3F').replace(/#/g, '%23');
  return new URL(`file://${escaped}`);
}

module.exports = {
  isURL,
  pathToFileURL,
  URL: globalThis.URL,
  URLSearchParams: globalThis.URLSearchParams,
};
