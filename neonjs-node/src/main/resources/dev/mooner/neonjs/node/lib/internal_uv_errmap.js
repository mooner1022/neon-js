'use strict';
// libuv's error map (Node's internalBinding('uv') getErrorMap, getErrorMessage): the errors a sandbox can report, with
// libuv's names and messages and Linux's numbering whatever the platform (MIT license, see NOTICE).

const ERRNO = [
  [-1, 'EPERM', 'operation not permitted'], [-2, 'ENOENT', 'no such file or directory'],
  [-4, 'EINTR', 'interrupted system call'], [-5, 'EIO', 'i/o error'], [-9, 'EBADF', 'bad file descriptor'],
  [-11, 'EAGAIN', 'resource temporarily unavailable'], [-12, 'ENOMEM', 'not enough memory'],
  [-13, 'EACCES', 'permission denied'], [-16, 'EBUSY', 'resource busy or locked'], [-17, 'EEXIST', 'file already exists'],
  [-20, 'ENOTDIR', 'not a directory'], [-21, 'EISDIR', 'illegal operation on a directory'],
  [-22, 'EINVAL', 'invalid argument'], [-24, 'EMFILE', 'too many open files'], [-28, 'ENOSPC', 'no space left on device'],
  [-32, 'EPIPE', 'broken pipe'], [-38, 'ENOSYS', 'function not implemented'], [-39, 'ENOTEMPTY', 'directory not empty'],
  [-98, 'EADDRINUSE', 'address already in use'], [-99, 'EADDRNOTAVAIL', 'address not available'],
  [-101, 'ENETUNREACH', 'network is unreachable'], [-103, 'ECONNABORTED', 'software caused connection abort'],
  [-104, 'ECONNRESET', 'connection reset by peer'], [-107, 'ENOTCONN', 'socket is not connected'],
  [-110, 'ETIMEDOUT', 'connection timed out'], [-111, 'ECONNREFUSED', 'connection refused'],
  [-113, 'EHOSTUNREACH', 'host is unreachable'], [-125, 'ECANCELED', 'operation canceled'],
  [-3008, 'ENOTFOUND', 'getaddrinfo ENOTFOUND'], [-4095, 'EOF', 'end of file'],
];

function getErrorMap() {
  const map = new Map();
  for (let i = 0; i < ERRNO.length; i++) map.set(ERRNO[i][0], [ERRNO[i][1], ERRNO[i][2]]);
  return map;
}

function getErrorMessage(err) {
  for (let i = 0; i < ERRNO.length; i++) if (ERRNO[i][0] === err) return ERRNO[i][2];
  return `Unknown system error ${err}`;
}

module.exports = {
  getErrorMap,
  getErrorMessage,
};
