'use strict';
// node:http without a network of its own: Node's constants and header checks (after Node v26.11.1 lib/_http_server.js,
// lib/_http_common.js and lib/_http_outgoing.js, MIT license, see NOTICE). Requests and servers are not here (fetch
// is the network); they load, and say so when used.

const {
  codes: { ERR_HTTP_INVALID_HEADER_VALUE, ERR_INVALID_CHAR, ERR_INVALID_HTTP_TOKEN },
  hideStackFrames,
} = require('internal/errors');
const { validateObject, validateOneOf } = require('internal/validators');
const { assignFunctionName } = require('internal/util');
const { unavailableClass, unavailableFunction } = require('internal/unavailable');

const STATUS_CODES = {
  100: 'Continue',                   // RFC 7231 6.2.1
  101: 'Switching Protocols',        // RFC 7231 6.2.2
  102: 'Processing',                 // RFC 2518 10.1 (obsoleted by RFC 4918)
  103: 'Early Hints',                // RFC 8297 2
  200: 'OK',                         // RFC 7231 6.3.1
  201: 'Created',                    // RFC 7231 6.3.2
  202: 'Accepted',                   // RFC 7231 6.3.3
  203: 'Non-Authoritative Information', // RFC 7231 6.3.4
  204: 'No Content',                 // RFC 7231 6.3.5
  205: 'Reset Content',              // RFC 7231 6.3.6
  206: 'Partial Content',            // RFC 7233 4.1
  207: 'Multi-Status',               // RFC 4918 11.1
  208: 'Already Reported',           // RFC 5842 7.1
  226: 'IM Used',                    // RFC 3229 10.4.1
  300: 'Multiple Choices',           // RFC 7231 6.4.1
  301: 'Moved Permanently',          // RFC 7231 6.4.2
  302: 'Found',                      // RFC 7231 6.4.3
  303: 'See Other',                  // RFC 7231 6.4.4
  304: 'Not Modified',               // RFC 7232 4.1
  305: 'Use Proxy',                  // RFC 7231 6.4.5
  307: 'Temporary Redirect',         // RFC 7231 6.4.7
  308: 'Permanent Redirect',         // RFC 7238 3
  400: 'Bad Request',                // RFC 7231 6.5.1
  401: 'Unauthorized',               // RFC 7235 3.1
  402: 'Payment Required',           // RFC 7231 6.5.2
  403: 'Forbidden',                  // RFC 7231 6.5.3
  404: 'Not Found',                  // RFC 7231 6.5.4
  405: 'Method Not Allowed',         // RFC 7231 6.5.5
  406: 'Not Acceptable',             // RFC 7231 6.5.6
  407: 'Proxy Authentication Required', // RFC 7235 3.2
  408: 'Request Timeout',            // RFC 7231 6.5.7
  409: 'Conflict',                   // RFC 7231 6.5.8
  410: 'Gone',                       // RFC 7231 6.5.9
  411: 'Length Required',            // RFC 7231 6.5.10
  412: 'Precondition Failed',        // RFC 7232 4.2
  413: 'Payload Too Large',          // RFC 7231 6.5.11
  414: 'URI Too Long',               // RFC 7231 6.5.12
  415: 'Unsupported Media Type',     // RFC 7231 6.5.13
  416: 'Range Not Satisfiable',      // RFC 7233 4.4
  417: 'Expectation Failed',         // RFC 7231 6.5.14
  418: 'I\'m a Teapot',              // RFC 7168 2.3.3
  421: 'Misdirected Request',        // RFC 7540 9.1.2
  422: 'Unprocessable Entity',       // RFC 4918 11.2
  423: 'Locked',                     // RFC 4918 11.3
  424: 'Failed Dependency',          // RFC 4918 11.4
  425: 'Too Early',                  // RFC 8470 5.2
  426: 'Upgrade Required',           // RFC 2817 and RFC 7231 6.5.15
  428: 'Precondition Required',      // RFC 6585 3
  429: 'Too Many Requests',          // RFC 6585 4
  431: 'Request Header Fields Too Large', // RFC 6585 5
  451: 'Unavailable For Legal Reasons', // RFC 7725 3
  500: 'Internal Server Error',      // RFC 7231 6.6.1
  501: 'Not Implemented',            // RFC 7231 6.6.2
  502: 'Bad Gateway',                // RFC 7231 6.6.3
  503: 'Service Unavailable',        // RFC 7231 6.6.4
  504: 'Gateway Timeout',            // RFC 7231 6.6.5
  505: 'HTTP Version Not Supported', // RFC 7231 6.6.6
  506: 'Variant Also Negotiates',    // RFC 2295 8.1
  507: 'Insufficient Storage',       // RFC 4918 11.5
  508: 'Loop Detected',              // RFC 5842 7.2
  509: 'Bandwidth Limit Exceeded',
  510: 'Not Extended',               // RFC 2774 7
  511: 'Network Authentication Required', // RFC 6585 6
};

// llhttp's methods, as Node lists them
const METHODS = [
  'ACL', 'BIND', 'CHECKOUT', 'CONNECT', 'COPY', 'DELETE', 'GET', 'HEAD', 'LINK', 'LOCK', 'M-SEARCH', 'MERGE',
  'MKACTIVITY', 'MKCALENDAR', 'MKCOL', 'MOVE', 'NOTIFY', 'OPTIONS', 'PATCH', 'POST', 'PROPFIND', 'PROPPATCH',
  'PURGE', 'PUT', 'QUERY', 'REBIND', 'REPORT', 'SEARCH', 'SOURCE', 'SUBSCRIBE', 'TRACE', 'UNBIND', 'UNLINK', 'UNLOCK',
  'UNSUBSCRIBE',
];

const tokenRegExp = /^[\^_`a-zA-Z\-0-9!#$%&'*+.|~]+$/;
/**
 * Verifies that the given val is a valid HTTP token
 * per the rules defined in RFC 7230
 * See https://tools.ietf.org/html/rfc7230#section-3.2.6
 */
function checkIsHttpToken(val) {
  return tokenRegExp.test(val);
}

// RFC 7230's field-value (the default), and the Fetch Standard's laxer header value (no NUL, CR or LF; bytes only)
const strictHeaderCharRegex = /[^\t\x20-\x7e\x80-\xff]/;
// eslint-disable-next-line no-control-regex
const lenientHeaderCharRegex = /[\x00\x0a\x0d]|[^\x00-\xff]/;

/** True if val contains an invalid header value character. */
function checkInvalidHeaderChar(val, lenient = false) {
  const regex = lenient ? lenientHeaderCharRegex : strictHeaderCharRegex;
  return regex.test(val);
}

/** Non-throwing counterpart of validateHeaderName(). */
function isValidHeaderName(name) {
  return typeof name === 'string' && checkIsHttpToken(name);
}

const validateHeaderName = assignFunctionName('validateHeaderName', hideStackFrames((name, label) => {
  if (!isValidHeaderName(name)) {
    throw new ERR_INVALID_HTTP_TOKEN.HideStackFramesError(label || 'Header name', name);
  }
}));

const kHttpValidationValues = ['strict', 'relaxed'];

/** Non-throwing counterpart of validateHeaderValue(). */
function isValidHeaderValue(value, options) {
  let lenient = false;
  if (options !== undefined) {
    validateObject(options, 'options');
    const { httpValidation } = options;
    if (httpValidation === 'relaxed') {
      lenient = true;
    } else if (httpValidation !== undefined && httpValidation !== 'strict') {
      validateOneOf(httpValidation, 'options.httpValidation', kHttpValidationValues);
    }
  }
  if (value === undefined || typeof value === 'symbol') {
    return false;
  }
  return !checkInvalidHeaderChar(value, lenient);
}

const validateHeaderValue = assignFunctionName('validateHeaderValue', hideStackFrames((name, value, lenient) => {
  if (value === undefined) {
    throw new ERR_HTTP_INVALID_HEADER_VALUE.HideStackFramesError(value, name);
  }
  if (checkInvalidHeaderChar(value, lenient)) {
    throw new ERR_INVALID_CHAR.HideStackFramesError('header content', name);
  }
}));

const maxHeaderSize = 16 * 1024;

module.exports = {
  _connectionListener: unavailableFunction('http._connectionListener'),
  METHODS: METHODS.toSorted(),
  STATUS_CODES,
  Agent: unavailableClass('http.Agent'),
  ClientRequest: unavailableClass('http.ClientRequest'),
  IncomingMessage: unavailableClass('http.IncomingMessage'),
  OutgoingMessage: unavailableClass('http.OutgoingMessage'),
  Server: unavailableClass('http.Server'),
  ServerResponse: unavailableClass('http.ServerResponse'),
  createServer: unavailableFunction('http.createServer'),
  get: unavailableFunction('http.get'),
  request: unavailableFunction('http.request'),
  setGlobalProxyFromEnv: unavailableFunction('http.setGlobalProxyFromEnv'),
  setMaxIdleHTTPParsers: () => {},
  isValidHeaderName,
  isValidHeaderValue,
  validateHeaderName,
  validateHeaderValue,
};
// the web's, as in Node (with neonjs-net; undefined without it)
for (const name of ['WebSocket', 'CloseEvent', 'MessageEvent']) {
  Object.defineProperty(module.exports, name, {
    configurable: true,
    enumerable: true,
    get() { return globalThis[name]; },
  });
}
Object.defineProperty(module.exports, 'maxHeaderSize', {
  configurable: true,
  enumerable: true,
  get() { return maxHeaderSize; },
});
module.exports.globalAgent = undefined;
