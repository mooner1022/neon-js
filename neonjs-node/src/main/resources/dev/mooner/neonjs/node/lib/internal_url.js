'use strict';
// What the libraries take from Node's lib/internal/url.js (MIT license, see NOTICE); URL itself is the web global.

// a URL as Node tells one (duck-typed, so that URLs of other realms count)
function isURL(self) {
  return Boolean(self?.href && self.protocol && self.auth === undefined && self.path === undefined);
}

module.exports = {
  isURL,
  URL: globalThis.URL,
  URLSearchParams: globalThis.URLSearchParams,
};
