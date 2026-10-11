'use strict';
// Deep equality as util.isDeepStrictEqual (and later assert.deepStrictEqual) has it: same prototypes and tags, the
// same own enumerable keys and symbols, equal values (Object.is for primitives), Maps and Sets by content, cycles
// matched pair by pair. The loose mode (assert.deepEqual) is not here yet.

const types = binding.types;
const uncurry = (fn) => Function.prototype.call.bind(fn);
const ObjectPrototypeHasOwnProperty = uncurry(Object.prototype.hasOwnProperty);
const ObjectPrototypePropertyIsEnumerable = uncurry(Object.prototype.propertyIsEnumerable);
const ObjectPrototypeToString = uncurry(Object.prototype.toString);
const DatePrototypeGetTime = uncurry(Date.prototype.getTime);
const NumberPrototypeValueOf = uncurry(Number.prototype.valueOf);
const StringPrototypeValueOf = uncurry(String.prototype.valueOf);
const BooleanPrototypeValueOf = uncurry(Boolean.prototype.valueOf);
const BigIntPrototypeValueOf = uncurry(BigInt.prototype.valueOf);
const SymbolPrototypeValueOf = uncurry(Symbol.prototype.valueOf);

const kNoIterator = 0;
const kIsArray = 1;
const kIsSet = 2;
const kIsMap = 3;

let compareBytes;
function bytesEqual(a, b) {
  if (compareBytes === undefined) compareBytes = require('buffer').Buffer.compare;
  return compareBytes(a, b) === 0;
}

function isError(v) {
  return types.isNativeError(v) || v instanceof Error;
}

function areSimilarRegExps(a, b) {
  return a.source === b.source && a.flags === b.flags && a.lastIndex === b.lastIndex;
}

function areSimilarTypedArrays(a, b) {
  if (a.byteLength !== b.byteLength) return false;
  return bytesEqual(new Uint8Array(a.buffer, a.byteOffset, a.byteLength), new Uint8Array(b.buffer, b.byteOffset, b.byteLength));
}

function areEqualArrayBuffers(a, b) {
  return a.byteLength === b.byteLength && bytesEqual(new Uint8Array(a), new Uint8Array(b));
}

function isEqualBoxedPrimitive(a, b) {
  if (types.isNumberObject(a)) return types.isNumberObject(b) && Object.is(NumberPrototypeValueOf(a), NumberPrototypeValueOf(b));
  if (types.isStringObject(a)) return types.isStringObject(b) && StringPrototypeValueOf(a) === StringPrototypeValueOf(b);
  if (types.isBooleanObject(a)) return types.isBooleanObject(b) && BooleanPrototypeValueOf(a) === BooleanPrototypeValueOf(b);
  if (types.isBigIntObject(a)) return types.isBigIntObject(b) && BigIntPrototypeValueOf(a) === BigIntPrototypeValueOf(b);
  return types.isSymbolObject(b) && SymbolPrototypeValueOf(a) === SymbolPrototypeValueOf(b);
}

function innerDeepEqual(val1, val2, memos) {
  if (val1 === val2) return val1 !== 0 || Object.is(val1, val2);
  if (typeof val1 === 'number') return Number.isNaN(val1) && Number.isNaN(val2);
  if (typeof val2 !== 'object' || typeof val1 !== 'object' || val1 === null || val2 === null) {
    // functions are equal only when identical
    return false;
  }
  if (Object.getPrototypeOf(val1) !== Object.getPrototypeOf(val2)) return false;
  const val1Tag = ObjectPrototypeToString(val1);
  const val2Tag = ObjectPrototypeToString(val2);
  if (val1Tag !== val2Tag) return false;

  if (Array.isArray(val1)) {
    if (!Array.isArray(val2) || val1.length !== val2.length) return false;
    const keys1 = types.ownNonIndexKeys(val1, false);
    const keys2 = types.ownNonIndexKeys(val2, false);
    if (keys1.length !== keys2.length) return false;
    return keyCheck(val1, val2, memos, kIsArray, keys1);
  } else if (val1Tag === '[object Object]' && !types.isBoxedPrimitive(val1) && !isError(val1)) {
    return keyCheck(val1, val2, memos, kNoIterator);
  } else if (types.isDate(val1)) {
    if (!types.isDate(val2) || DatePrototypeGetTime(val1) !== DatePrototypeGetTime(val2)) return false;
  } else if (types.isRegExp(val1)) {
    if (!types.isRegExp(val2) || !areSimilarRegExps(val1, val2)) return false;
  } else if (types.isArrayBufferView(val1)) {
    if (!types.isArrayBufferView(val2) || types.isTypedArray(val1) !== types.isTypedArray(val2)) return false;
    if (!areSimilarTypedArrays(val1, val2)) return false;
    const keys1 = types.ownNonIndexKeys(val1, false);
    const keys2 = types.ownNonIndexKeys(val2, false);
    if (keys1.length !== keys2.length) return false;
    return keyCheck(val1, val2, memos, kNoIterator, keys1);
  } else if (types.isSet(val1)) {
    if (!types.isSet(val2) || val1.size !== val2.size) return false;
    return keyCheck(val1, val2, memos, kIsSet);
  } else if (types.isMap(val1)) {
    if (!types.isMap(val2) || val1.size !== val2.size) return false;
    return keyCheck(val1, val2, memos, kIsMap);
  } else if (types.isAnyArrayBuffer(val1)) {
    if (!types.isAnyArrayBuffer(val2) || !areEqualArrayBuffers(val1, val2)) return false;
  } else if (isError(val1)) {
    // the stacks may differ for errors otherwise the same
    if (!isError(val2) || val1.message !== val2.message || val1.name !== val2.name) return false;
    const has1 = ObjectPrototypeHasOwnProperty(val1, 'cause');
    if (has1 !== ObjectPrototypeHasOwnProperty(val2, 'cause') || (has1 && !innerDeepEqual(val1.cause, val2.cause, memos))) return false;
  } else if (types.isBoxedPrimitive(val1)) {
    if (!isEqualBoxedPrimitive(val1, val2)) return false;
  } else if (Array.isArray(val2) || types.isArrayBufferView(val2) || types.isSet(val2) || types.isMap(val2) ||
             types.isDate(val2) || types.isRegExp(val2) || types.isAnyArrayBuffer(val2) || types.isBoxedPrimitive(val2) ||
             isError(val2)) {
    return false;
  } else if (types.isWeakMap(val1) || types.isWeakSet(val1) || types.isPromise(val1)) {
    // their contents cannot be compared: equal only when identical
    return false;
  }
  return keyCheck(val1, val2, memos, kNoIterator);
}

function getEnumerables(val, keys) {
  return keys.filter((k) => ObjectPrototypePropertyIsEnumerable(val, k));
}

function keyCheck(val1, val2, memos, iterationType, aKeys) {
  const isArrayLike = aKeys !== undefined;
  if (aKeys === undefined) aKeys = Object.keys(val1);
  if (!isArrayLike) {
    if (aKeys.length !== Object.keys(val2).length) return false;
  }
  for (let i = 0; i < aKeys.length; i++) {
    if (!ObjectPrototypePropertyIsEnumerable(val2, aKeys[i])) return false;
  }
  if (!isArrayLike) {
    const symbolKeysA = Object.getOwnPropertySymbols(val1);
    if (symbolKeysA.length !== 0) {
      let count = 0;
      for (const key of symbolKeysA) {
        if (ObjectPrototypePropertyIsEnumerable(val1, key)) {
          if (!ObjectPrototypePropertyIsEnumerable(val2, key)) return false;
          aKeys.push(key);
          count++;
        } else if (ObjectPrototypePropertyIsEnumerable(val2, key)) {
          return false;
        }
      }
      const symbolKeysB = Object.getOwnPropertySymbols(val2);
      if (symbolKeysA.length !== symbolKeysB.length && getEnumerables(val2, symbolKeysB).length !== count) return false;
    } else {
      const symbolKeysB = Object.getOwnPropertySymbols(val2);
      if (symbolKeysB.length !== 0 && getEnumerables(val2, symbolKeysB).length !== 0) return false;
    }
  }
  if (aKeys.length === 0 &&
      (iterationType === kNoIterator || (iterationType === kIsArray && val1.length === 0) || val1.size === 0)) {
    return true;
  }
  // a pair already being compared further up: equal unless something else says otherwise
  if (memos === undefined) {
    memos = { val1: new Map(), val2: new Map(), position: 0 };
  } else {
    const val2MemoA = memos.val1.get(val1);
    if (val2MemoA !== undefined) {
      const val2MemoB = memos.val2.get(val2);
      if (val2MemoB !== undefined) return val2MemoA === val2MemoB;
    }
    memos.position++;
  }
  memos.val1.set(val1, memos.position);
  memos.val2.set(val2, memos.position);
  const areEq = objEquiv(val1, val2, aKeys, memos, iterationType);
  memos.val1.delete(val1);
  memos.val2.delete(val2);
  return areEq;
}

function setHasEqualElement(set, val1, memo) {
  for (const val2 of set) {
    if (innerDeepEqual(val1, val2, memo)) {
      set.delete(val2);
      return true;
    }
  }
  return false;
}

function setEquiv(a, b, memo) {
  let set = null;
  for (const val of a) {
    if (typeof val === 'object' && val !== null) {
      if (set === null) set = new Set();
      set.add(val);
    } else if (!b.has(val)) {
      return false;
    }
  }
  if (set !== null) {
    for (const val of b) {
      if (typeof val === 'object' && val !== null) {
        if (!setHasEqualElement(set, val, memo)) return false;
      }
    }
    return set.size === 0;
  }
  return true;
}

function mapHasEqualEntry(set, map, key1, item1, memo) {
  for (const key2 of set) {
    if (innerDeepEqual(key1, key2, memo) && innerDeepEqual(item1, map.get(key2), memo)) {
      set.delete(key2);
      return true;
    }
  }
  return false;
}

function mapEquiv(a, b, memo) {
  let set = null;
  for (const [key, item1] of a) {
    if (typeof key === 'object' && key !== null) {
      if (set === null) set = new Set();
      set.add(key);
    } else {
      const item2 = b.get(key);
      if ((item2 === undefined && !b.has(key)) || !innerDeepEqual(item1, item2, memo)) return false;
    }
  }
  if (set !== null) {
    for (const [key, item] of b) {
      if (typeof key === 'object' && key !== null) {
        if (!mapHasEqualEntry(set, a, key, item, memo)) return false;
      }
    }
    return set.size === 0;
  }
  return true;
}

function objEquiv(a, b, keys, memos, iterationType) {
  for (let i = 0; i < keys.length; i++) {
    if (!innerDeepEqual(a[keys[i]], b[keys[i]], memos)) return false;
  }
  if (iterationType === kIsArray) {
    for (let i = 0; i < a.length; i++) {
      if (ObjectPrototypeHasOwnProperty(a, i)) {
        if (!ObjectPrototypeHasOwnProperty(b, i) || !innerDeepEqual(a[i], b[i], memos)) return false;
      } else if (ObjectPrototypeHasOwnProperty(b, i)) {
        return false;
      } else {
        // holes: the rest by key
        const keysA = Object.keys(a);
        for (; i < keysA.length; i++) {
          const key = keysA[i];
          if (!ObjectPrototypeHasOwnProperty(b, key) || !innerDeepEqual(a[key], b[key], memos)) return false;
        }
        return keysA.length === Object.keys(b).length;
      }
    }
  } else if (iterationType === kIsSet) {
    if (!setEquiv(a, b, memos)) return false;
  } else if (iterationType === kIsMap) {
    if (!mapEquiv(a, b, memos)) return false;
  }
  return true;
}

function isDeepStrictEqual(val1, val2) {
  return innerDeepEqual(val1, val2, undefined);
}

module.exports = { isDeepStrictEqual };
