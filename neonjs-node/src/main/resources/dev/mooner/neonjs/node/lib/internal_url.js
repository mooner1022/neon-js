'use strict';
// What the libraries take from Node's lib/internal/url.js (after Node v26.11.1, MIT license, see NOTICE): URL and
// URLSearchParams are the web globals (shown by util.inspect as Node shows its own), and the domain conversions come
// from the engine's IDNA.

const {
  ArrayPrototypeJoin,
  ArrayPrototypePush,
  ArrayPrototypeReduce,
  ObjectDefineProperty,
  ObjectGetOwnPropertyDescriptor,
  ObjectGetPrototypeOf,
  RegExpPrototypeSymbolReplace,
  SafeSet,
  StringPrototypeCharAt,
  StringPrototypeCodePointAt,
  StringPrototypeIndexOf,
  StringPrototypeSlice,
  SymbolFor,
} = primordials;

const {
  codes: {
    ERR_INVALID_ARG_TYPE,
    ERR_INVALID_FILE_URL_HOST,
    ERR_INVALID_FILE_URL_PATH,
    ERR_INVALID_URL_SCHEME,
    ERR_MISSING_ARGS,
  },
} = require('internal/errors');
const { isWindows, kEmptyObject } = require('internal/util');

const URL = globalThis.URL;
const URLSearchParams = globalThis.URLSearchParams;

const CHAR_LOWERCASE_A = 97;
const CHAR_LOWERCASE_Z = 122;
const CHAR_BACKWARD_SLASH = 92;
const CHAR_FORWARD_SLASH = 47;
const FORWARD_SLASH = /\//g;

// Protocols that can allow "unsafe" and "unwise" chars.
const unsafeProtocol = new SafeSet([
  'javascript',
  'javascript:',
]);
// Protocols that never have a hostname.
const hostlessProtocol = new SafeSet([
  'javascript',
  'javascript:',
]);
// Protocols that always contain a // bit.
const slashedProtocol = new SafeSet([
  'http',
  'http:',
  'https',
  'https:',
  'ftp',
  'ftp:',
  'gopher',
  'gopher:',
  'file',
  'file:',
  'ws',
  'ws:',
  'wss',
  'wss:',
]);

// a URL as Node tells one (duck-typed, so that URLs of other realms count)
function isURL(self) {
  return Boolean(self?.href && self.protocol && self.auth === undefined && self.path === undefined);
}

// Node's binding: the host of "ws://x" set to domain (the URL Standard's host setter), '' when that fails
function domainToASCIIHost(domain) {
  if (domain === '') return '';
  const url = new URL('ws://x');
  url.hostname = domain;
  const host = url.hostname;
  // the setter leaves the host as it was when the domain is no valid host
  if (host === 'x' && domain.toLowerCase() !== 'x') return '';
  return host;
}

function domainToASCII(domain) {
  if (arguments.length < 1) throw new ERR_MISSING_ARGS('domain');
  return domainToASCIIHost(`${domain}`);
}

function domainToUnicode(domain) {
  if (arguments.length < 1) throw new ERR_MISSING_ARGS('domain');
  const host = domainToASCIIHost(`${domain}`);
  return host === '' ? '' : binding.domainToUnicode(host);
}

/**
 * Utility function that converts a URL object into an ordinary options object
 * as expected by the `http.request` and `https.request` APIs.
 * @param {URL} url
 * @returns {Record<string, unknown>}
 */
function urlToHttpOptions(url) {
  const { validateObject, kValidateObjectAllowObjects } = require('internal/validators');
  validateObject(url, 'url', kValidateObjectAllowObjects);
  const { hostname, pathname, port, username, password, search } = url;
  const options = {
    __proto__: null,
    ...url, // In case the url object was extended by the user.
    protocol: url.protocol,
    hostname: hostname && hostname[0] === '[' ?
      StringPrototypeSlice(hostname, 1, -1) :
      hostname,
    hash: url.hash,
    search: search,
    pathname: pathname,
    path: `${pathname || ''}${search || ''}`,
    href: url.href,
  };
  if (port !== '') {
    options.port = Number(port);
  }
  if (username || password) {
    options.auth = `${decodeURIComponent(username)}:${decodeURIComponent(password)}`;
  }
  return options;
}

function getPathFromURLWin32(url) {
  const hostname = url.hostname;
  let pathname = url.pathname;
  for (let n = 0; n < pathname.length; n++) {
    if (pathname[n] === '%') {
      const third = StringPrototypeCodePointAt(pathname, n + 2) | 0x20;
      if ((pathname[n + 1] === '2' && third === 102) || // 2f 2F /
          (pathname[n + 1] === '5' && third === 99)) {  // 5c 5C \
        throw new ERR_INVALID_FILE_URL_PATH('must not include encoded \\ or / characters', url);
      }
    }
  }
  pathname = RegExpPrototypeSymbolReplace(FORWARD_SLASH, pathname, '\\');
  if (pathname.includes('%')) pathname = decodeURIComponent(pathname);
  if (hostname !== '') {
    // If hostname is set, then we have a UNC path
    // Pass the hostname through domainToUnicode just in case
    // it is an IDN using punycode encoding. We do not need to worry
    // about percent encoding because the URL parser will have
    // already taken care of that for us. Note that this only
    // causes IDNs with an appropriate `xn--` prefix to be decoded.
    return `\\\\${domainToUnicode(hostname)}${pathname}`;
  }
  // Otherwise, it's a local path that requires a drive letter
  const letter = StringPrototypeCodePointAt(pathname, 1) | 0x20;
  const sep = StringPrototypeCharAt(pathname, 2);
  if (letter < CHAR_LOWERCASE_A || letter > CHAR_LOWERCASE_Z ||   // a..z A..Z
      (sep !== ':')) {
    throw new ERR_INVALID_FILE_URL_PATH('must be absolute', url);
  }
  return StringPrototypeSlice(pathname, 1);
}

// the bytes of a URL's path: percent-encodings decoded as bytes (an invalid one stays as it is), nothing else checked
function percentDecodeBytes(pathname) {
  const { Buffer } = require('buffer');
  const input = Buffer.from(pathname, 'utf8');
  const out = Buffer.allocUnsafe(input.length);
  let n = 0;
  const hex = (c) => (c >= 0x30 && c <= 0x39 ? c - 0x30 : (c | 0x20) >= 0x61 && (c | 0x20) <= 0x66 ? (c | 0x20) - 0x57 : -1);
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (c === 0x25 && i + 2 < input.length && hex(input[i + 1]) >= 0 && hex(input[i + 2]) >= 0) {
      out[n++] = hex(input[i + 1]) * 16 + hex(input[i + 2]);
      i += 2;
    } else {
      out[n++] = c;
    }
  }
  return out.subarray(0, n);
}

function getPathBufferFromURLWin32(url) {
  const { Buffer } = require('buffer');
  const hostname = url.hostname;
  // the encoded slashes are not looked for: the bytes are taken as they are
  const decoded = percentDecodeBytes(RegExpPrototypeSymbolReplace(FORWARD_SLASH, url.pathname, '\\'));
  if (hostname !== '') {
    return Buffer.concat([Buffer.from('\\\\', 'ascii'), Buffer.from(domainToUnicode(hostname), 'utf8'), decoded]);
  }
  const letter = decoded[1] | 0x20;
  if (letter < CHAR_LOWERCASE_A || letter > CHAR_LOWERCASE_Z || decoded[2] !== 58) {
    throw new ERR_INVALID_FILE_URL_PATH('must be absolute', url);
  }
  return decoded.subarray(1);
}

function getPathBufferFromURLPosix(url) {
  if (url.hostname !== '') {
    throw new ERR_INVALID_FILE_URL_HOST(binding.platform);
  }
  return percentDecodeBytes(url.pathname);
}

function getPathFromURLPosix(url) {
  if (url.hostname !== '') {
    throw new ERR_INVALID_FILE_URL_HOST(binding.platform);
  }
  const pathname = url.pathname;
  for (let n = 0; n < pathname.length; n++) {
    if (pathname[n] === '%') {
      const third = StringPrototypeCodePointAt(pathname, n + 2) | 0x20;
      if (pathname[n + 1] === '2' && third === 102) {
        throw new ERR_INVALID_FILE_URL_PATH('must not include encoded / characters', url);
      }
    }
  }
  return pathname.includes('%') ? decodeURIComponent(pathname) : pathname;
}

function fileURLToPath(path, options = kEmptyObject) {
  const windows = options?.windows;
  if (typeof path === 'string')
    path = new URL(path);
  else if (!isURL(path))
    throw new ERR_INVALID_ARG_TYPE('path', ['string', 'URL'], path);
  if (path.protocol !== 'file:')
    throw new ERR_INVALID_URL_SCHEME('file');
  return (windows ?? isWindows) ? getPathFromURLWin32(path) : getPathFromURLPosix(path);
}

// fileURLToPath giving the path's bytes, for paths that are no UTF-8
function fileURLToPathBuffer(path, options = kEmptyObject) {
  const windows = options?.windows;
  if (typeof path === 'string')
    path = new URL(path);
  else if (!isURL(path))
    throw new ERR_INVALID_ARG_TYPE('path', ['string', 'URL'], path);
  if (path.protocol !== 'file:')
    throw new ERR_INVALID_URL_SCHEME('file');
  return (windows ?? isWindows) ? getPathBufferFromURLWin32(path) : getPathBufferFromURLPosix(path);
}

// A path as a URL path, as Node's native conversion makes it: ASCII letters, digits and !$&'()*+,-./:;=@_ stay;
// every other character is percent-encoded (non-ASCII ones as UTF-8, a lone surrogate as U+FFFD).
const kPathKeep = "!$&'()*+,-./:;=@_";

function encodePathForURL(path) {
  let out = '';
  for (const ch of path) {
    const c = ch.codePointAt(0);
    if (c < 0x80) {
      const keep = (c >= 0x61 && c <= 0x7a) || (c >= 0x41 && c <= 0x5a) || (c >= 0x30 && c <= 0x39) ||
        kPathKeep.includes(ch);
      out += keep ? ch : `%${c.toString(16).toUpperCase().padStart(2, '0')}`;
    } else {
      out += encodeURIComponent(c >= 0xD800 && c <= 0xDFFF ? '\uFFFD' : ch);
    }
  }
  return out;
}

const backslashRegEx = /\\/g;

function pathToFileURL(filepath, options = kEmptyObject) {
  const windows = options?.windows ?? isWindows;
  const path = require('path');
  // a local extended path (\\?\C:\...) is the path after the prefix, as Node's native conversion has it
  if (windows && filepath.startsWith('\\\\?\\') && !filepath.startsWith('\\\\?\\UNC\\')) filepath = filepath.slice(4);
  // a UNC path as given, else resolved (which may make it one)
  const isUNC = windows && filepath.startsWith('\\\\');
  let resolved = isUNC ? filepath : (windows ? path.win32.resolve(filepath) : path.posix.resolve(filepath));
  if (isUNC || (windows && resolved.startsWith('\\\\'))) {
    // UNC path format: \\server\share\resource
    // Handle extended UNC path and standard UNC path
    // "\\?\UNC\" path prefix should be ignored.
    // Ref: https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation
    const isExtendedUNC = resolved.startsWith('\\\\?\\UNC\\');
    const prefixLength = isExtendedUNC ? 8 : 2;
    const hostnameEndIndex = StringPrototypeIndexOf(resolved, '\\', prefixLength);
    const { ERR_INVALID_ARG_VALUE } = require('internal/errors').codes;
    if (hostnameEndIndex === -1) {
      throw new ERR_INVALID_ARG_VALUE('path', resolved, 'Missing UNC resource path');
    }
    if (hostnameEndIndex === 2) {
      throw new ERR_INVALID_ARG_VALUE('path', resolved, 'Empty UNC servername');
    }
    const hostname = StringPrototypeSlice(resolved, prefixLength, hostnameEndIndex);
    const pathname = RegExpPrototypeSymbolReplace(backslashRegEx, StringPrototypeSlice(resolved, hostnameEndIndex), '/');
    // the server name as a URL's host parser takes it (up to /, ? or #, without tabs and newlines): one that is no
    // valid host is an invalid URL, as Node's native parser finds it
    const parsed = new URL('file://');
    parsed.hostname = hostname;
    if (parsed.hostname === '') throw new (require('internal/errors').codes.ERR_INVALID_URL)(`file://${hostname}${pathname}`);
    return new URL(`file://${parsed.host}${encodePathForURL(pathname)}`);
  }
  // path.resolve strips trailing slashes so we must add them back
  const filePathLast = filepath.charCodeAt(filepath.length - 1);
  if ((filePathLast === CHAR_FORWARD_SLASH ||
       (windows && filePathLast === CHAR_BACKWARD_SLASH)) &&
      resolved[resolved.length - 1] !== (windows ? '\\' : '/'))
    resolved += '/';
  if (windows) resolved = `/${RegExpPrototypeSymbolReplace(backslashRegEx, resolved, '/')}`;
  return new URL(`file://${encodePathForURL(resolved)}`);
}

function toPathIfFileURL(fileURLOrPath) {
  if (!isURL(fileURLOrPath))
    return fileURLOrPath;
  return fileURLToPath(fileURLOrPath);
}

// ---------------------------------------------------------------- util.inspect of the web globals, as Node's own

const customInspect = SymbolFor('nodejs.util.inspect.custom');

function getConstructorOf(obj) {
  while (obj) {
    const descriptor = ObjectGetOwnPropertyDescriptor(obj, 'constructor');
    if (descriptor !== undefined && typeof descriptor.value === 'function' && descriptor.value.name !== '') {
      return descriptor.value;
    }
    obj = ObjectGetPrototypeOf(obj);
  }
  return null;
}

function installInspect() {
  if (typeof URL === 'function' && !Object.prototype.hasOwnProperty.call(URL.prototype, customInspect)) {
    ObjectDefineProperty(URL.prototype, customInspect, {
      __proto__: null,
      configurable: true,
      writable: true,
      enumerable: false,
      value: function inspect(depth, opts, inspectFn) {
        if (typeof depth === 'number' && depth < 0) return this;
        const constructor = getConstructorOf(this) || URL;
        const obj = { __proto__: { constructor } };
        obj.href = this.href;
        obj.origin = this.origin;
        obj.protocol = this.protocol;
        obj.username = this.username;
        obj.password = this.password;
        obj.host = this.host;
        obj.hostname = this.hostname;
        obj.port = this.port;
        obj.pathname = this.pathname;
        obj.search = this.search;
        obj.searchParams = this.searchParams;
        obj.hash = this.hash;
        return `${constructor.name} ${inspectFn(obj, opts)}`;
      },
    });
  }
  if (typeof URLSearchParams === 'function' && !Object.prototype.hasOwnProperty.call(URLSearchParams.prototype, customInspect)) {
    const { removeColors } = require('internal/util');
    ObjectDefineProperty(URLSearchParams.prototype, customInspect, {
      __proto__: null,
      configurable: true,
      writable: true,
      enumerable: false,
      value: function inspect(recurseTimes, ctx, inspectFn) {
        if (typeof recurseTimes === 'number' && recurseTimes < 0) return ctx.stylize('[Object]', 'special');
        const separator = ', ';
        const innerOpts = { ...ctx };
        if (recurseTimes !== null) innerOpts.depth = recurseTimes - 1;
        const innerInspect = (v) => inspectFn(v, innerOpts);
        const output = [];
        for (const { 0: key, 1: value } of this) ArrayPrototypePush(output, `${innerInspect(key)} => ${innerInspect(value)}`);
        const length = ArrayPrototypeReduce(output, (prev, cur) => prev + removeColors(cur).length + separator.length, -separator.length);
        if (length > ctx.breakLength) return `${this.constructor.name} {\n  ${ArrayPrototypeJoin(output, ',\n  ')} }`;
        if (output.length) return `${this.constructor.name} { ${ArrayPrototypeJoin(output, separator)} }`;
        return `${this.constructor.name} {}`;
      },
    });
  }
}

module.exports = {
  fileURLToPath,
  fileURLToPathBuffer,
  pathToFileURL,
  toPathIfFileURL,
  URL,
  URLSearchParams,
  domainToASCII,
  domainToUnicode,
  urlToHttpOptions,
  isURL,
  installInspect,
  unsafeProtocol,
  hostlessProtocol,
  slashedProtocol,
};
