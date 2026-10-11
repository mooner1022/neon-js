'use strict';
// node:https without a network of its own: it loads, and requests and servers say so when used (fetch is the
// network).

const { unavailableClass, unavailableFunction } = require('internal/unavailable');

module.exports = {
  Agent: unavailableClass('https.Agent'),
  Server: unavailableClass('https.Server'),
  createServer: unavailableFunction('https.createServer'),
  get: unavailableFunction('https.get'),
  globalAgent: undefined,
  request: unavailableFunction('https.request'),
};
