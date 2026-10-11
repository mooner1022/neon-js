'use strict';
// node:process. What it tells of the host comes from NodeOptions only (env is empty unless given); exit() ends the
// script's evaluation, never the host; 'uncaughtException' and 'unhandledRejection' listeners see what the script
// left uncaught (the host's uncaught error handler gets what they do not handle).

const EventEmitter = require('events');
const info = binding.processInfo();
const console = globalThis.console;

function Process() {
  EventEmitter.call(this);
}
Object.setPrototypeOf(Process.prototype, EventEmitter.prototype);
Object.setPrototypeOf(Process, EventEmitter);
Object.defineProperty(Process.prototype, Symbol.toStringTag, { value: 'process', configurable: true });

const process = new Process();
let cwd = info.cwd;

function invalidArg(name, expected, value) {
  return binding.error('TypeError', `The "${name}" argument must be ${expected}. Received ${typeof value}`, 'ERR_INVALID_ARG_TYPE');
}

process.title = 'neonjs';
process.version = info.version;
process.versions = { node: info.version.slice(1), neonjs: info.neonjsVersion };
process.release = { name: 'node' };
process.arch = info.arch;
process.platform = info.platform;
process.argv = info.argv;
process.argv0 = info.argv.length > 0 ? info.argv[0] : 'neonjs';
process.execArgv = [];
process.execPath = '';
process.env = info.env;
process.pid = 0;
process.ppid = 0;
process.exitCode = undefined;
process.config = { target_defaults: {}, variables: {} };
process.features = { inspector: false, ipv6: true, tls: false, typescript: false };
process.allowedNodeEnvironmentFlags = new Set();
process.noDeprecation = false;
process.throwDeprecation = false;
process.traceDeprecation = false;

process.cwd = function cwd_() {
  return cwd;
};

process.chdir = function chdir(directory) {
  if (typeof directory !== 'string') throw invalidArg('directory', 'of type string', directory);
  // the working directory of the script only: relative paths resolve against it
  // TODO: normalize with path.resolve once node:path is there ('..' is kept as is for now)
  cwd = directory.startsWith('/') ? directory : (cwd.endsWith('/') ? cwd : cwd + '/') + directory;
};

process.umask = function umask() {
  return 0o22;
};

process.exit = function exit(code) {
  if (code !== undefined) process.exitCode = code;
  const status = process.exitCode === undefined ? 0 : process.exitCode;
  // an 'exit' listener calling exit again exits at once, as in Node
  if (!process._exiting) {
    process._exiting = true;
    process.emit('exit', status);
  }
  binding.exit(status);
};

process.abort = function abort() {
  binding.exit(134);
};

process.kill = function kill() {
  throw binding.error('Error', 'process.kill is not available here', 'ERR_ACCESS_DENIED');
};

process.nextTick = function nextTick(callback, ...args) {
  if (typeof callback !== 'function') throw invalidArg('callback', 'of type function', callback);
  binding.nextTick(callback, args);
};

process.hrtime = function hrtime(time) {
  const [s, ns] = binding.hrtime();
  if (time === undefined) return [s, ns];
  if (!Array.isArray(time) || time.length !== 2) throw invalidArg('time', 'an instance of Array', time);
  let sec = s - time[0];
  let nsec = ns - time[1];
  if (nsec < 0) {
    sec -= 1;
    nsec += 1e9;
  }
  return [sec, nsec];
};
process.hrtime.bigint = function bigint() {
  return binding.hrtimeBigint();
};

process.uptime = function uptime() {
  return binding.uptime();
};

process.memoryUsage = function memoryUsage() {
  return binding.memoryUsage();
};
process.memoryUsage.rss = function rss() {
  return binding.memoryUsage().rss;
};

process.cpuUsage = function cpuUsage() {
  return { user: 0, system: 0 };
};

function createWarning(message, type, code, ctor, detail) {
  const w = new Error(message);
  w.name = String(type || 'Warning');
  if (code !== undefined) w.code = code;
  if (detail !== undefined) w.detail = detail;
  Error.captureStackTrace(w, ctor || process.emitWarning);
  return w;
}

process.emitWarning = function emitWarning(warning, type, code, ctor) {
  let detail;
  if (type !== null && typeof type === 'object' && !Array.isArray(type)) {
    ctor = type.ctor;
    code = type.code;
    if (typeof type.detail === 'string') detail = type.detail;
    type = type.type || 'Warning';
  } else if (typeof type === 'function') {
    ctor = type;
    code = undefined;
    type = 'Warning';
  }
  if (type !== undefined && typeof type !== 'string') throw invalidArg('type', 'of type string', type);
  if (typeof code === 'function') {
    ctor = code;
    code = undefined;
  } else if (code !== undefined && typeof code !== 'string') {
    throw invalidArg('code', 'of type string', code);
  }
  if (typeof warning === 'string') {
    warning = createWarning(warning, type, code, ctor, detail);
  } else if (!(warning instanceof Error)) {
    throw invalidArg('warning', 'of type string or an instance of Error', warning);
  }
  if (warning.name === 'DeprecationWarning') {
    if (process.noDeprecation) return;
    if (process.throwDeprecation) throw warning;
  }
  binding.nextTick(() => process.emit('warning', warning), []);
};

// Node prints warnings by a listener of its own: so does this, to the console sink
process.on('warning', function onWarning(warning) {
  if (!console) return;
  const code = warning.code ? `[${warning.code}] ` : '';
  let text = `(neonjs) ${code}${warning.name}: ${warning.message}`;
  if (warning.detail) text += `\n${warning.detail}`;
  console.error(text);
});

function stream(fd) {
  const s = new EventEmitter();
  s.fd = fd;
  s.isTTY = false;
  s.columns = 80;
  s.writable = true;
  s.write = function write(chunk, encoding, callback) {
    if (typeof encoding === 'function') callback = encoding;
    binding.write(fd, typeof chunk === 'string' ? chunk : String(chunk));
    if (typeof callback === 'function') binding.nextTick(callback, []);
    return true;
  };
  s.end = function end(chunk, encoding, callback) {
    if (chunk !== undefined && typeof chunk !== 'function') s.write(chunk, encoding);
    const cb = typeof chunk === 'function' ? chunk : typeof encoding === 'function' ? encoding : callback;
    if (typeof cb === 'function') binding.nextTick(cb, []);
    return s;
  };
  s.cork = s.uncork = function () {};
  s.getColorDepth = function () { return 1; };
  s.hasColors = function () { return false; };
  return s;
}
process.stdout = stream(1);
process.stderr = stream(2);
process.stdin = Object.assign(new EventEmitter(), {
  fd: 0,
  isTTY: false,
  readable: false,
  setEncoding() { return this; },
  resume() { return this; },
  pause() { return this; },
  setRawMode() { return this; },
});

binding.setProcess(process);
module.exports = process;
