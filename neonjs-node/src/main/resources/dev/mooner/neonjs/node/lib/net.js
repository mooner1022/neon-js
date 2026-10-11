'use strict';
// node:net without sockets (fetch and WebSocket are the network): it loads, and its IP checks are Node's
// (internal/net); connections and servers say what is missing when used (ERR_UNAVAILABLE).

const { unavailableModule } = require('internal/unavailable');
const { isIP, isIPv4, isIPv6 } = require('internal/net');

const net = unavailableModule('net', {
  functions: ['_createServerHandle', '_normalizeArgs', 'connect', 'createConnection', 'createServer'],
  classes: ['BlockList', 'BoundSocket', 'Server', 'Socket', 'SocketAddress', '_TransferredBoundSocket'],
  values: {
    isIP,
    isIPv4,
    isIPv6,
    getDefaultAutoSelectFamily: () => false,
    setDefaultAutoSelectFamily: () => {},
    getDefaultAutoSelectFamilyAttemptTimeout: () => 250,
    setDefaultAutoSelectFamilyAttemptTimeout: () => {},
  },
});
net.Stream = net.Socket;

module.exports = net;
