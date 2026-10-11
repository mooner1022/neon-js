'use strict';
// node:tls without sockets (fetch and WebSocket are the network): it loads; connections, servers and contexts say
// what is missing when used (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

module.exports = unavailableModule('tls', {
  functions: ['checkServerIdentity', 'connect', 'convertALPNProtocols', 'createSecureContext', 'createServer',
    'getCACertificates', 'getCertificateCompressionAlgorithms', 'setDefaultCACertificates'],
  classes: ['SecureContext', 'Server', 'TLSSocket'],
  values: {
    CLIENT_RENEG_LIMIT: 3,
    CLIENT_RENEG_WINDOW: 600,
    DEFAULT_CIPHERS: '',
    DEFAULT_ECDH_CURVE: 'auto',
    DEFAULT_MAX_VERSION: 'TLSv1.3',
    DEFAULT_MIN_VERSION: 'TLSv1.2',
    getCiphers: () => [],
    rootCertificates: Object.freeze([]),
  },
});
