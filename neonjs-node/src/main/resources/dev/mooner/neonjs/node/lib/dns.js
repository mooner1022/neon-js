'use strict';
// node:dns without name resolution of its own (fetch resolves its own names): it loads; each lookup says what is
// missing through its callback (ERR_UNAVAILABLE).

const { unavailableCallback, unavailableClass } = require('internal/unavailable');

const names = [
  'lookup', 'lookupService', 'resolve', 'resolve4', 'resolve6', 'resolveAny', 'resolveCaa', 'resolveCname',
  'resolveMx', 'resolveNaptr', 'resolveNs', 'resolvePtr', 'resolveSoa', 'resolveSrv', 'resolveTxt', 'reverse',
];
const dns = {};
for (const n of names) dns[n] = unavailableCallback(`dns.${n}`);
Object.assign(dns, {
  Resolver: unavailableClass('dns.Resolver'),
  getDefaultResultOrder: () => 'verbatim',
  getServers: () => [],
  setDefaultResultOrder: () => {},
  setServers: () => {},
  ADDRCONFIG: 1024,
  ALL: 256,
  V4MAPPED: 2048,
});
Object.defineProperty(dns, 'promises', {
  configurable: true,
  enumerable: true,
  get() { return require('dns/promises'); },
});

module.exports = dns;
