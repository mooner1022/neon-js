'use strict';
// node:os as a sandbox shows it: the platform and architecture NodeOptions and the engine give, the environment
// variables of process.env (HOME, TMPDIR... which the host gives or not), the engine's own memory, and for the rest
// (processors, network interfaces, the user, the host's name and kernel) neutral values, never the host's.

const info = binding.processInfo();
const { validateInt32 } = require('internal/validators');
const { Buffer } = require('buffer');

const platform = info.platform;
const isWindows = platform === 'win32';

const signals = {
  __proto__: null,
  SIGHUP: 1, SIGINT: 2, SIGQUIT: 3, SIGILL: 4, SIGTRAP: 5, SIGABRT: 6, SIGIOT: 6, SIGBUS: 7, SIGFPE: 8, SIGKILL: 9,
  SIGUSR1: 10, SIGSEGV: 11, SIGUSR2: 12, SIGPIPE: 13, SIGALRM: 14, SIGTERM: 15, SIGSTKFLT: 16, SIGCHLD: 17,
  SIGCONT: 18, SIGSTOP: 19, SIGTSTP: 20, SIGTTIN: 21, SIGTTOU: 22, SIGURG: 23, SIGXCPU: 24, SIGXFSZ: 25,
  SIGVTALRM: 26, SIGPROF: 27, SIGWINCH: 28, SIGIO: 29, SIGPOLL: 29, SIGPWR: 30, SIGSYS: 31,
};

const errno = {
  __proto__: null,
  E2BIG: 7, EACCES: 13, EADDRINUSE: 98, EADDRNOTAVAIL: 99, EAFNOSUPPORT: 97, EAGAIN: 11, EALREADY: 114, EBADF: 9,
  EBUSY: 16, ECANCELED: 125, ECHILD: 10, ECONNABORTED: 103, ECONNREFUSED: 111, ECONNRESET: 104, EDEADLK: 35,
  EDESTADDRREQ: 89, EDOM: 33, EEXIST: 17, EFAULT: 14, EFBIG: 27, EHOSTUNREACH: 113, EINTR: 4, EINVAL: 22, EIO: 5,
  EISCONN: 106, EISDIR: 21, ELOOP: 40, EMFILE: 24, EMLINK: 31, EMSGSIZE: 90, ENAMETOOLONG: 36, ENETDOWN: 100,
  ENETRESET: 102, ENETUNREACH: 101, ENFILE: 23, ENOBUFS: 105, ENODEV: 19, ENOENT: 2, ENOEXEC: 8, ENOMEM: 12,
  ENOSPC: 28, ENOSYS: 38, ENOTCONN: 107, ENOTDIR: 20, ENOTEMPTY: 39, ENOTSOCK: 88, ENOTSUP: 95, ENOTTY: 25, ENXIO: 6,
  EOPNOTSUPP: 95, EOVERFLOW: 75, EPERM: 1, EPIPE: 32, EPROTO: 71, ERANGE: 34, EROFS: 30, ESPIPE: 29, ESRCH: 3,
  ETIMEDOUT: 110, ETXTBSY: 26, EWOULDBLOCK: 11, EXDEV: 18,
};

const priority = {
  __proto__: null,
  PRIORITY_LOW: 19, PRIORITY_BELOW_NORMAL: 10, PRIORITY_NORMAL: 0, PRIORITY_ABOVE_NORMAL: -7, PRIORITY_HIGH: -14,
  PRIORITY_HIGHEST: -20,
};

const constants = Object.freeze({
  __proto__: null,
  UV_UDP_REUSEADDR: 4,
  dlopen: Object.freeze({ __proto__: null, RTLD_LAZY: 1, RTLD_NOW: 2, RTLD_GLOBAL: 256, RTLD_LOCAL: 0 }),
  errno: Object.freeze(errno),
  signals: Object.freeze(signals),
  priority: Object.freeze(priority),
});

const MACHINES = { __proto__: null, x64: 'x86_64', arm64: 'aarch64', ia32: 'i686', arm: 'armv7l' };
const TYPES = { __proto__: null, win32: 'Windows_NT', darwin: 'Darwin', aix: 'AIX', freebsd: 'FreeBSD', openbsd: 'OpenBSD', sunos: 'SunOS' };

let env;
function getenv(name) {
  env ??= require('process').env;
  const value = env[name];
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function arch() {
  return info.arch;
}

function platformFn() {
  return platform;
}

function type() {
  return TYPES[platform] ?? 'Linux';
}

function release() {
  return info.neonjsVersion;
}

function version() {
  return `NeonJS ${info.neonjsVersion}`;
}

function machine() {
  return MACHINES[info.arch] ?? info.arch;
}

function hostname() {
  return 'localhost';
}

function endianness() {
  return 'LE';
}

function homedir() {
  return getenv(isWindows ? 'USERPROFILE' : 'HOME') ?? (isWindows ? 'C:\\' : '/');
}

function tmpdir() {
  if (isWindows) {
    const path = getenv('TEMP') ?? getenv('TMP') ?? `${getenv('SystemRoot') ?? getenv('windir') ?? 'C:\\Windows'}\\temp`;
    if (path.length > 1 && path.endsWith('\\') && !path.endsWith(':\\')) return path.slice(0, -1);
    return path;
  }
  const path = getenv('TMPDIR') ?? getenv('TMP') ?? getenv('TEMP') ?? getenv('TEMPDIR') ?? '/tmp';
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path;
}

// the engine's memory, not the host's
function totalmem() {
  return binding.memoryUsage().heapTotal;
}

function freemem() {
  const m = binding.memoryUsage();
  return Math.max(m.heapTotal - m.heapUsed, 1);
}

function uptime() {
  return Math.max(binding.uptime(), Number.MIN_VALUE);
}

function availableParallelism() {
  return 1;
}

// one processor, the engine's
function cpus() {
  return [{ model: 'NeonJS', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
}

function loadavg() {
  return [0, 0, 0];
}

// the loopback interface only
function networkInterfaces() {
  const lo = {
    address: '127.0.0.1',
    netmask: '255.0.0.0',
    family: 'IPv4',
    mac: '00:00:00:00:00:00',
    internal: true,
    cidr: '127.0.0.1/8',
  };
  return { [isWindows ? 'Loopback Pseudo-Interface 1' : 'lo']: [lo] };
}

function userInfo(options) {
  const asBuffer = options?.encoding === 'buffer';
  const username = getenv(isWindows ? 'USERNAME' : 'USER') ?? 'neonjs';
  const home = homedir();
  const shell = isWindows ? null : '';
  return {
    uid: -1,
    gid: -1,
    username: asBuffer ? Buffer.from(username) : username,
    homedir: asBuffer ? Buffer.from(home) : home,
    shell: asBuffer && shell !== null ? Buffer.from(shell) : shell,
  };
}

// priorities are remembered, as if set (nothing here schedules threads)
const priorities = new Map();

function getPriority(pid = 0) {
  validateInt32(pid, 'pid');
  return priorities.get(pid) ?? 0;
}

function setPriority(pid, priority) {
  if (priority === undefined) {
    priority = pid;
    pid = 0;
  }
  validateInt32(pid, 'pid');
  validateInt32(priority, 'priority', -20, 19);
  priorities.set(pid, priority);
}

// Node's os functions also convert to their value
for (const fn of [arch, availableParallelism, endianness, freemem, homedir, hostname, machine, platformFn, release,
  tmpdir, totalmem, type, uptime, version]) {
  fn[Symbol.toPrimitive] = () => fn();
}

module.exports = {
  arch,
  availableParallelism,
  cpus,
  endianness,
  freemem,
  getPriority,
  homedir,
  hostname,
  loadavg,
  machine,
  networkInterfaces,
  platform: platformFn,
  release,
  setPriority,
  tmpdir,
  totalmem,
  type,
  uptime,
  userInfo,
  version,
};

Object.defineProperties(module.exports, {
  constants: { configurable: false, enumerable: true, value: constants },
  EOL: { configurable: true, enumerable: true, writable: false, value: isWindows ? '\r\n' : '\n' },
  devNull: { configurable: true, enumerable: true, writable: false, value: isWindows ? '\\\\.\\nul' : '/dev/null' },
});
