'use strict';
// node:dns/promises without name resolution of its own: each lookup rejects with what is missing (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

// the error codes of lookups, as node:dns has them (dns.NOTFOUND...)
const dns = require('dns');
const errorCodes = {};
for (const key of Object.keys(dns)) if (typeof dns[key] === 'string') errorCodes[key] = dns[key];

module.exports = unavailableModule('dns/promises', {
  promises: [
  'lookup', 'lookupService', 'resolve', 'resolve4', 'resolve6', 'resolveAny', 'resolveCaa', 'resolveCname',
  'resolveMx', 'resolveNaptr', 'resolveNs', 'resolvePtr', 'resolveSoa', 'resolveSrv', 'resolveTlsa', 'resolveTxt',
  'reverse',
  ],
  classes: ['Resolver'],
  values: {
    getDefaultResultOrder: () => 'verbatim',
    getServers: () => [],
    setDefaultResultOrder: () => {},
    setServers: () => {},
    ...errorCodes,
  },
});
