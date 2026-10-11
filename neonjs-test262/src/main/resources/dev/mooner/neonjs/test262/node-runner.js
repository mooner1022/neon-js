'use strict';
// The CommonJS loader of Node's tests on NeonJS: a test and the test/common files it requires run as modules (with
// process and __filename in scope, as in Node); test/common/index.js is NeonJS's own (node-common.js); anything else
// is the realm's require (the node: built-ins). __nodeTestCompile(path) gives a module function, or null when there
// is no such file.
(function () {
  const process = require('process');
  const cache = new Map();

  function normalize(path) {
    const out = [];
    for (const part of path.split('/')) {
      if (part === '' || part === '.') continue;
      if (part === '..') out.pop();
      else out.push(part);
    }
    return out.join('/');
  }

  function resolve(dir, id) {
    let path = normalize(`${dir}/${id}`);
    if (id.endsWith('/') || id === '.' || id === '..' || path === 'test/common') path += '/index.js';
    else if (!path.endsWith('.js') && !path.endsWith('.json')) path += '.js';
    return normalize(path);
  }

  function makeRequire(dir) {
    const req = function require(id) {
      if (typeof id === 'string' && (id.startsWith('./') || id.startsWith('../') || id === '.' || id === '..')) {
        return load(resolve(dir, id));
      }
      return globalThis.require(id);
    };
    req.resolve = (id) => (id.startsWith('.') ? `/${resolve(dir, id)}` : globalThis.require.resolve(id));
    req.cache = {};
    return req;
  }

  function load(path, isMain) {
    const cached = cache.get(path);
    if (cached !== undefined) return cached.exports;
    const fn = __nodeTestCompile(path);
    if (fn === null) {
      const err = new Error(`Cannot find module '/${path}'`);
      err.code = 'MODULE_NOT_FOUND';
      throw err;
    }
    const dir = path.slice(0, path.lastIndexOf('/'));
    const module = { exports: {}, filename: `/${path}`, id: isMain ? '.' : `/${path}`, loaded: false, children: [], paths: [] };
    cache.set(path, module);
    const require = makeRequire(dir);
    if (isMain) require.main = module;
    try {
      fn.call(module.exports, module.exports, require, module, `/${path}`, `/${dir}`, process);
    } catch (err) {
      // what the main module throws goes to 'uncaughtException' listeners, as in Node
      if (!isMain || process.listenerCount('uncaughtException') === 0) throw err;
      process.emit('uncaughtException', err, 'uncaughtException');
    }
    module.loaded = true;
    return module.exports;
  }

  // the end of the process, as Node has it: 'exit' with the exit code, once
  function exit() {
    if (!process._exiting) {
      process._exiting = true;
      process.emit('exit', process.exitCode === undefined ? 0 : process.exitCode);
    }
    return process.exitCode === undefined ? 0 : Number(process.exitCode);
  }

  return { load, exit };
})()
