'use strict';
// node:string_decoder: decodes a series of Buffers into strings without splitting a character across two of them
// (Node's JS implementation from before the native one, with base64url and typed array input added).

const { Buffer } = require('buffer');
const { codes: { ERR_INVALID_ARG_TYPE, ERR_UNKNOWN_ENCODING } } = require('internal/errors');

function normalizeEncoding(enc) {
  if (enc == null || enc === 'utf8' || enc === 'utf-8') return 'utf8';
  switch (`${enc}`.toLowerCase()) {
    case 'utf8': case 'utf-8': return 'utf8';
    case 'ucs2': case 'ucs-2': case 'utf16le': case 'utf-16le': return 'utf16le';
    case 'latin1': case 'binary': return 'latin1';
    case 'base64': return 'base64';
    case 'base64url': return 'base64url';
    case 'hex': return 'hex';
    case 'ascii': return 'ascii';
  }
  throw ERR_UNKNOWN_ENCODING(enc);
}

function toBuffer(buf) {
  if (buf instanceof Buffer) return buf;
  if (ArrayBuffer.isView(buf)) return Buffer.from(buf.buffer, buf.byteOffset, buf.byteLength);
  throw ERR_INVALID_ARG_TYPE('buf', ['Buffer', 'TypedArray', 'DataView'], buf);
}

// the kind of a UTF-8 byte: 0 ASCII, 2-4 a lead byte of that many, -1 a continuation byte, -2 invalid
function utf8CheckByte(byte) {
  if (byte <= 0x7F) return 0;
  if (byte >> 5 === 0x06) return 2;
  if (byte >> 4 === 0x0E) return 3;
  if (byte >> 3 === 0x1E) return 4;
  return byte >> 6 === 0x02 ? -1 : -2;
}

// how many bytes the character the buffer ends in needs (0 when it ends on a whole one); sets lastNeed
function utf8CheckIncomplete(self, buf, i) {
  let j = buf.length - 1;
  if (j < i) return 0;
  let nb = utf8CheckByte(buf[j]);
  if (nb >= 0) {
    if (nb > 0) self.lastNeed = nb - 1;
    return nb;
  }
  if (--j < i || nb === -2) return 0;
  nb = utf8CheckByte(buf[j]);
  if (nb >= 0) {
    if (nb > 0) self.lastNeed = nb - 2;
    return nb;
  }
  if (--j < i || nb === -2) return 0;
  nb = utf8CheckByte(buf[j]);
  if (nb >= 0) {
    if (nb > 0) {
      if (nb === 2) nb = 0;
      else self.lastNeed = nb - 3;
    }
    return nb;
  }
  return 0;
}

// a byte that is not a continuation where one was expected: the partial character is one U+FFFD
function utf8CheckExtraBytes(self, buf) {
  if ((buf[0] & 0xC0) !== 0x80) {
    self.lastNeed = 0;
    return '�';
  }
  if (self.lastNeed > 1 && buf.length > 1) {
    if ((buf[1] & 0xC0) !== 0x80) {
      self.lastNeed = 1;
      return '�';
    }
    if (self.lastNeed > 2 && buf.length > 2) {
      if ((buf[2] & 0xC0) !== 0x80) {
        self.lastNeed = 2;
        return '�';
      }
    }
  }
}

const kind = Symbol('kind');

class StringDecoder {
  constructor(encoding) {
    this.encoding = normalizeEncoding(encoding);
    let nb;
    switch (this.encoding) {
      case 'utf16le': nb = 4; break;
      case 'utf8': nb = 4; break;
      case 'base64': case 'base64url': nb = 3; break;
      default: nb = 0;
    }
    this[kind] = nb === 0 ? 'simple' : this.encoding === 'base64url' ? 'base64' : this.encoding;
    this.lastNeed = 0;
    this.lastTotal = 0;
    this.lastChar = Buffer.alloc(nb);
  }

  write(buf) {
    if (typeof buf === 'string') return buf;
    buf = toBuffer(buf);
    if (this[kind] === 'simple') return buf.toString(this.encoding);
    if (buf.length === 0) return '';
    let r;
    let i;
    if (this.lastNeed) {
      r = this[kind] === 'utf8' ? this._utf8FillLast(buf) : this._fillLast(buf);
      if (r === undefined) return '';
      i = this.lastNeed;
      this.lastNeed = 0;
    } else {
      i = 0;
    }
    if (i < buf.length) return r ? r + this.text(buf, i) : this.text(buf, i);
    return r || '';
  }

  end(buf) {
    const r = buf !== undefined && buf !== null && buf.length ? this.write(buf) : '';
    if (!this.lastNeed) return r;
    const need = this.lastNeed;
    this.lastNeed = 0;
    switch (this[kind]) {
      case 'utf8':
        return `${r}�`;
      case 'utf16le':
        return r + this.lastChar.toString('utf16le', 0, this.lastTotal - need);
      case 'base64':
        return r + this.lastChar.toString(this.encoding, 0, 3 - need);
    }
    return r;
  }

  // the complete characters of buf from i (a partial one at the end is kept for the next write)
  text(buf, i = 0) {
    buf = toBuffer(buf);
    switch (this[kind]) {
      case 'utf8': {
        const total = utf8CheckIncomplete(this, buf, i);
        if (!this.lastNeed) return buf.toString('utf8', i);
        this.lastTotal = total;
        const end = buf.length - (total - this.lastNeed);
        buf.copy(this.lastChar, 0, end);
        return buf.toString('utf8', i, end);
      }
      case 'utf16le': {
        if ((buf.length - i) % 2 === 0) {
          const r = buf.toString('utf16le', i);
          if (r) {
            const c = r.charCodeAt(r.length - 1);
            if (c >= 0xD800 && c <= 0xDBFF) {
              this.lastNeed = 2;
              this.lastTotal = 4;
              this.lastChar[0] = buf[buf.length - 2];
              this.lastChar[1] = buf[buf.length - 1];
              return r.slice(0, -1);
            }
          }
          return r;
        }
        this.lastNeed = 1;
        this.lastTotal = 2;
        this.lastChar[0] = buf[buf.length - 1];
        return buf.toString('utf16le', i, buf.length - 1);
      }
      case 'base64': {
        const n = (buf.length - i) % 3;
        if (n === 0) return buf.toString(this.encoding, i);
        this.lastNeed = 3 - n;
        this.lastTotal = 3;
        if (n === 1) {
          this.lastChar[0] = buf[buf.length - 1];
        } else {
          this.lastChar[0] = buf[buf.length - 2];
          this.lastChar[1] = buf[buf.length - 1];
        }
        return buf.toString(this.encoding, i, buf.length - n);
      }
    }
    return buf.toString(this.encoding, i);
  }

  _fillLast(buf) {
    if (this.lastNeed <= buf.length) {
      buf.copy(this.lastChar, this.lastTotal - this.lastNeed, 0, this.lastNeed);
      return this.lastChar.toString(this.encoding, 0, this.lastTotal);
    }
    buf.copy(this.lastChar, this.lastTotal - this.lastNeed, 0, buf.length);
    this.lastNeed -= buf.length;
  }

  _utf8FillLast(buf) {
    const p = this.lastTotal - this.lastNeed;
    const r = utf8CheckExtraBytes(this, buf);
    if (r !== undefined) return r;
    if (this.lastNeed <= buf.length) {
      buf.copy(this.lastChar, p, 0, this.lastNeed);
      return this.lastChar.toString('utf8', 0, this.lastTotal);
    }
    buf.copy(this.lastChar, p, 0, buf.length);
    this.lastNeed -= buf.length;
  }
}

module.exports = { StringDecoder };
