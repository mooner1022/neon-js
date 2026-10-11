'use strict';
// util.types: the engine's own checks of internal slots (binding.types), which never run script code; the names Node
// 26's util.types has (the engine's other checks, as isClass, are for inspect only).

const NAMES = [
  'isAnyArrayBuffer', 'isArgumentsObject', 'isArrayBuffer', 'isArrayBufferView', 'isAsyncFunction',
  'isBigInt64Array', 'isBigIntObject', 'isBigUint64Array', 'isBooleanObject', 'isBoxedPrimitive', 'isCryptoKey',
  'isDataView', 'isDate', 'isExternal', 'isFloat16Array', 'isFloat32Array', 'isFloat64Array', 'isGeneratorFunction',
  'isGeneratorObject', 'isInt16Array', 'isInt32Array', 'isInt8Array', 'isKeyObject', 'isMap', 'isMapIterator',
  'isModuleNamespaceObject', 'isNativeError', 'isNumberObject', 'isPromise', 'isProxy', 'isRegExp', 'isSet',
  'isSetIterator', 'isSharedArrayBuffer', 'isStringObject', 'isSymbolObject', 'isTypedArray', 'isUint16Array',
  'isUint32Array', 'isUint8Array', 'isUint8ClampedArray', 'isWeakMap', 'isWeakSet',
];

const types = {};
for (const name of NAMES) {
  if (typeof binding.types[name] === 'function') types[name] = binding.types[name];
}

module.exports = types;
