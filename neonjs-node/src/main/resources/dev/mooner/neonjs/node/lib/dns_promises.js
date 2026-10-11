'use strict';
// node:dns/promises without name resolution of its own: each lookup rejects with what is missing (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');

module.exports = unavailableModule('dns/promises', {
  promises: [
  'lookup', 'lookupService', 'resolve', 'resolve4', 'resolve6', 'resolveAny', 'resolveCaa', 'resolveCname',
  'resolveMx', 'resolveNaptr', 'resolveNs', 'resolvePtr', 'resolveSoa', 'resolveSrv', 'resolveTxt', 'reverse',
  ],
  classes: ['Resolver'],
  values: {
    getDefaultResultOrder: () => 'verbatim',
    getServers: () => [],
    setDefaultResultOrder: () => {},
    setServers: () => {},
  },
});
