'use strict';
// util.inspect and util.format, after Node's lib/internal/util/inspect.js: the same layout (breakLength, compact
// grouping, array grouping, circular references) and the same hooks (inspect.custom with depth, options and inspect;
// options.stylize; inspect.colors / styles / defaultOptions). What JS cannot see (a promise's state, a proxy's target,
// internal slots) comes from binding.types, which never runs script code; a proxy is shown as its target, without
// asking its handler anything. Not here: prototype properties (showHidden).

const { validateObject, validateString } = binding.internal('errors');
const types = binding.types;

const uncurry = (fn) => Function.prototype.call.bind(fn);
const ObjectPrototypeHasOwnProperty = uncurry(Object.prototype.hasOwnProperty);
const ObjectPrototypePropertyIsEnumerable = uncurry(Object.prototype.propertyIsEnumerable);
const ObjectPrototypeToString = uncurry(Object.prototype.toString);
const ErrorPrototypeToString = uncurry(Error.prototype.toString);
const RegExpPrototypeToString = uncurry(RegExp.prototype.toString);
const DatePrototypeGetTime = uncurry(Date.prototype.getTime);
const DatePrototypeToISOString = uncurry(Date.prototype.toISOString);
const DatePrototypeToString = uncurry(Date.prototype.toString);
const NumberPrototypeValueOf = uncurry(Number.prototype.valueOf);
const StringPrototypeValueOf = uncurry(String.prototype.valueOf);
const BooleanPrototypeValueOf = uncurry(Boolean.prototype.valueOf);
const BigIntPrototypeValueOf = uncurry(BigInt.prototype.valueOf);
const SymbolPrototypeValueOf = uncurry(Symbol.prototype.valueOf);
const SymbolPrototypeToString = uncurry(Symbol.prototype.toString);
const MapPrototypeEntries = uncurry(Map.prototype.entries);
const MapPrototypeGetSize = uncurry(Object.getOwnPropertyDescriptor(Map.prototype, 'size').get);
const SetPrototypeValues = uncurry(Set.prototype.values);
const SetPrototypeGetSize = uncurry(Object.getOwnPropertyDescriptor(Set.prototype, 'size').get);
const MapIteratorNext = uncurry(Object.getPrototypeOf(new Map().entries()).next);
const SetIteratorNext = uncurry(Object.getPrototypeOf(new Set().values()).next);
const TypedArrayPrototype = Object.getPrototypeOf(Uint8Array.prototype);
const TypedArrayPrototypeGetLength = uncurry(Object.getOwnPropertyDescriptor(TypedArrayPrototype, 'length').get);
const TypedArrayPrototypeGetSymbolToStringTag = uncurry(Object.getOwnPropertyDescriptor(TypedArrayPrototype, Symbol.toStringTag).get);

const customInspectSymbol = Symbol.for('nodejs.util.inspect.custom');

const inspectDefaultOptions = Object.seal({
  showHidden: false,
  depth: 2,
  colors: false,
  customInspect: true,
  showProxy: false,
  maxArrayLength: 100,
  maxStringLength: 10000,
  breakLength: 80,
  compact: 3,
  sorted: false,
  getters: false,
  numericSeparator: false,
});

const kObjectType = 0;
const kArrayType = 1;
const kArrayExtrasType = 2;
const kMinLineWidth = 16;

// the global constructors (Node's builtInObjects), for %s
const builtInObjects = new Set(Object.getOwnPropertyNames(globalThis).filter((e) => /^[A-Z][a-zA-Z0-9]+$/.test(e)));

/* eslint-disable no-control-regex */
const strEscapeSequencesRegExp = /[\x00-\x1f\x27\x5c\x7f-\x9f]|[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/;
const strEscapeSequencesReplacer = /[\x00-\x1f\x27\x5c\x7f-\x9f]|[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g;
const strEscapeSequencesRegExpSingle = /[\x00-\x1f\x5c\x7f-\x9f]|[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/;
const strEscapeSequencesReplacerSingle = /[\x00-\x1f\x5c\x7f-\x9f]|[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g;
/* eslint-enable no-control-regex */

const keyStrRegExp = /^[a-zA-Z_][a-zA-Z_0-9]*$/;
const numberRegExp = /^(0|[1-9][0-9]*)$/;
const colorRegExp = /\u001b\[\d\d?m/g;

// escapes of the control characters, the single quote and the backslash
const meta = [
  '\\x00', '\\x01', '\\x02', '\\x03', '\\x04', '\\x05', '\\x06', '\\x07',
  '\\b', '\\t', '\\n', '\\x0B', '\\f', '\\r', '\\x0E', '\\x0F',
  '\\x10', '\\x11', '\\x12', '\\x13', '\\x14', '\\x15', '\\x16', '\\x17',
  '\\x18', '\\x19', '\\x1A', '\\x1B', '\\x1C', '\\x1D', '\\x1E', '\\x1F',
  '', '', '', '', '', '', '', "\\'", '', '', '', '', '', '', '', '',
  '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '',
  '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '',
  '', '', '', '', '', '', '', '', '', '', '', '', '\\\\', '', '', '',
  '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '',
  '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '\\x7F',
  '\\x80', '\\x81', '\\x82', '\\x83', '\\x84', '\\x85', '\\x86', '\\x87',
  '\\x88', '\\x89', '\\x8A', '\\x8B', '\\x8C', '\\x8D', '\\x8E', '\\x8F',
  '\\x90', '\\x91', '\\x92', '\\x93', '\\x94', '\\x95', '\\x96', '\\x97',
  '\\x98', '\\x99', '\\x9A', '\\x9B', '\\x9C', '\\x9D', '\\x9E', '\\x9F',
];

const escapeFn = (str) => {
  const code = str.charCodeAt(0);
  return code < meta.length ? meta[code] : `\\u${code.toString(16)}`;
};

// ---------------------------------------------------------------- inspect

function inspect(value, opts) {
  const ctx = {
    budget: {},
    indentationLvl: 0,
    seen: [],
    currentDepth: 0,
    stylize: stylizeNoColor,
    showHidden: inspectDefaultOptions.showHidden,
    depth: inspectDefaultOptions.depth,
    colors: inspectDefaultOptions.colors,
    customInspect: inspectDefaultOptions.customInspect,
    showProxy: inspectDefaultOptions.showProxy,
    maxArrayLength: inspectDefaultOptions.maxArrayLength,
    maxStringLength: inspectDefaultOptions.maxStringLength,
    breakLength: inspectDefaultOptions.breakLength,
    compact: inspectDefaultOptions.compact,
    sorted: inspectDefaultOptions.sorted,
    getters: inspectDefaultOptions.getters,
    numericSeparator: inspectDefaultOptions.numericSeparator,
  };
  if (arguments.length > 1) {
    // the legacy signature: inspect(value, showHidden, depth, colors)
    if (arguments.length > 2) {
      if (arguments[2] !== undefined) ctx.depth = arguments[2];
      if (arguments.length > 3 && arguments[3] !== undefined) ctx.colors = arguments[3];
    }
    if (typeof opts === 'boolean') {
      ctx.showHidden = opts;
    } else if (opts) {
      const optKeys = Object.keys(opts);
      for (let i = 0; i < optKeys.length; ++i) {
        const key = optKeys[i];
        if (ObjectPrototypeHasOwnProperty(inspectDefaultOptions, key) || key === 'stylize') {
          ctx[key] = opts[key];
        } else if (ctx.userOptions === undefined) {
          // what the caller gave beyond the known options goes on to custom inspect functions
          ctx.userOptions = opts;
        }
      }
    }
  }
  if (ctx.colors) ctx.stylize = stylizeWithColor;
  if (ctx.maxArrayLength === null) ctx.maxArrayLength = Infinity;
  if (ctx.maxStringLength === null) ctx.maxStringLength = Infinity;
  return formatValue(ctx, value, 0);
}
inspect.custom = customInspectSymbol;

Object.defineProperty(inspect, 'defaultOptions', {
  get() {
    return inspectDefaultOptions;
  },
  set(options) {
    validateObject(options, 'options');
    return Object.assign(inspectDefaultOptions, options);
  },
});

const defaultFG = 39;
const defaultBG = 49;
inspect.colors = {
  __proto__: null,
  reset: [0, 0],
  bold: [1, 22],
  dim: [2, 22],
  italic: [3, 23],
  underline: [4, 24],
  blink: [5, 25],
  inverse: [7, 27],
  hidden: [8, 28],
  strikethrough: [9, 29],
  doubleunderline: [21, 24],
  black: [30, defaultFG],
  red: [31, defaultFG],
  green: [32, defaultFG],
  yellow: [33, defaultFG],
  blue: [34, defaultFG],
  magenta: [35, defaultFG],
  cyan: [36, defaultFG],
  white: [37, defaultFG],
  bgBlack: [40, defaultBG],
  bgRed: [41, defaultBG],
  bgGreen: [42, defaultBG],
  bgYellow: [43, defaultBG],
  bgBlue: [44, defaultBG],
  bgMagenta: [45, defaultBG],
  bgCyan: [46, defaultBG],
  bgWhite: [47, defaultBG],
  framed: [51, 54],
  overlined: [53, 55],
  gray: [90, defaultFG],
  redBright: [91, defaultFG],
  greenBright: [92, defaultFG],
  yellowBright: [93, defaultFG],
  blueBright: [94, defaultFG],
  magentaBright: [95, defaultFG],
  cyanBright: [96, defaultFG],
  whiteBright: [97, defaultFG],
  bgGray: [100, defaultBG],
  bgRedBright: [101, defaultBG],
  bgGreenBright: [102, defaultBG],
  bgYellowBright: [103, defaultBG],
  bgBlueBright: [104, defaultBG],
  bgMagentaBright: [105, defaultBG],
  bgCyanBright: [106, defaultBG],
  bgWhiteBright: [107, defaultBG],
};

function defineColorAlias(target, alias) {
  Object.defineProperty(inspect.colors, alias, {
    get() {
      return this[target];
    },
    set(value) {
      this[target] = value;
    },
    configurable: true,
    enumerable: false,
  });
}
defineColorAlias('gray', 'grey');
defineColorAlias('gray', 'blackBright');
defineColorAlias('bgGray', 'bgGrey');
defineColorAlias('bgGray', 'bgBlackBright');
defineColorAlias('dim', 'faint');
defineColorAlias('strikethrough', 'crossedout');
defineColorAlias('strikethrough', 'strikeThrough');
defineColorAlias('strikethrough', 'crossedOut');
defineColorAlias('hidden', 'conceal');
defineColorAlias('inverse', 'swapColors');
defineColorAlias('inverse', 'swapcolors');
defineColorAlias('doubleunderline', 'doubleUnderline');

inspect.styles = Object.assign({ __proto__: null }, {
  special: 'cyan',
  number: 'yellow',
  bigint: 'yellow',
  boolean: 'yellow',
  undefined: 'grey',
  null: 'bold',
  string: 'green',
  symbol: 'green',
  date: 'magenta',
  regexp: 'red',
  module: 'underline',
});

function stylizeWithColor(str, styleType) {
  const style = inspect.styles[styleType];
  if (style !== undefined) {
    const color = inspect.colors[style];
    if (color !== undefined) return `\u001b[${color[0]}m${str}\u001b[${color[1]}m`;
  }
  return str;
}

function stylizeNoColor(str) {
  return str;
}

function getUserOptions(ctx) {
  return {
    stylize: ctx.stylize,
    showHidden: ctx.showHidden,
    depth: ctx.depth,
    colors: ctx.colors,
    customInspect: ctx.customInspect,
    showProxy: ctx.showProxy,
    maxArrayLength: ctx.maxArrayLength,
    maxStringLength: ctx.maxStringLength,
    breakLength: ctx.breakLength,
    compact: ctx.compact,
    sorted: ctx.sorted,
    getters: ctx.getters,
    numericSeparator: ctx.numericSeparator,
    ...ctx.userOptions,
  };
}

function formatValue(ctx, value, recurseTimes, typedArray) {
  if (typeof value !== 'object' && typeof value !== 'function') return formatPrimitive(ctx.stylize, value, ctx);
  if (value === null) return ctx.stylize('null', 'null');

  const context = value;
  // a proxy is shown as its target: its handler is never asked anything
  const proxy = types.proxyDetails(value);
  if (proxy !== undefined) {
    if (proxy[0] === null) return ctx.stylize('<Revoked Proxy>', 'special');
    if (ctx.showProxy) return formatProxy(ctx, proxy, recurseTimes);
    value = proxy[0];
    for (let inner = types.proxyDetails(value); inner !== undefined; inner = types.proxyDetails(value)) {
      if (inner[0] === null) return ctx.stylize('<Revoked Proxy>', 'special');
      value = inner[0];
    }
  }
  // a host (Java) object is one opaque line: its members are not walked
  const host = types.hostTag(value);
  if (host !== undefined) return ctx.stylize(host, 'special');

  if (ctx.customInspect) {
    const maybeCustom = value[customInspectSymbol];
    if (typeof maybeCustom === 'function' && maybeCustom !== inspect &&
        !(value.constructor && value.constructor.prototype === value)) {
      const depth = ctx.depth === null ? null : ctx.depth - recurseTimes;
      const ret = maybeCustom.call(context, depth, getUserOptions(ctx), inspect);
      if (ret !== context) {
        if (typeof ret !== 'string') return formatValue(ctx, ret, recurseTimes);
        return ret.replaceAll('\n', `\n${' '.repeat(ctx.indentationLvl)}`);
      }
    }
  }

  if (ctx.seen.includes(value)) {
    let index = 1;
    if (ctx.circular === undefined) {
      ctx.circular = new Map();
      ctx.circular.set(value, index);
    } else {
      index = ctx.circular.get(value);
      if (index === undefined) {
        index = ctx.circular.size + 1;
        ctx.circular.set(value, index);
      }
    }
    return ctx.stylize(`[Circular *${index}]`, 'special');
  }
  return formatRaw(ctx, value, recurseTimes, typedArray);
}

function formatRaw(ctx, value, recurseTimes, typedArray) {
  let keys;
  const constructor = getConstructorName(value, ctx, recurseTimes);
  let tag = value[Symbol.toStringTag];
  // the tag is only shown when it is not an own (enumerable) property, which is shown anyway
  if (typeof tag !== 'string' ||
      (tag !== '' && (ctx.showHidden ? ObjectPrototypeHasOwnProperty : ObjectPrototypePropertyIsEnumerable)(value, Symbol.toStringTag))) {
    tag = '';
  }
  let base = '';
  let formatter = getEmptyFormatArray;
  let braces;
  let noIterator = true;
  let extrasType = kObjectType;

  if (Symbol.iterator in value || constructor === null) {
    noIterator = false;
    if (Array.isArray(value)) {
      const prefix = (constructor !== 'Array' || tag !== '') ? getPrefix(constructor, tag, 'Array', `(${value.length})`) : '';
      keys = types.ownNonIndexKeys(value, ctx.showHidden);
      braces = [`${prefix}[`, ']'];
      if (value.length === 0 && keys.length === 0) return `${braces[0]}]`;
      extrasType = kArrayExtrasType;
      formatter = formatArray;
    } else if (types.isSet(value)) {
      const size = SetPrototypeGetSize(value);
      const prefix = getPrefix(constructor, tag, 'Set', `(${size})`);
      keys = getKeys(value, ctx.showHidden);
      formatter = (c, v, r) => formatSet(value, c, v, r);
      if (size === 0 && keys.length === 0) return `${prefix}{}`;
      braces = [`${prefix}{`, '}'];
    } else if (types.isMap(value)) {
      const size = MapPrototypeGetSize(value);
      const prefix = getPrefix(constructor, tag, 'Map', `(${size})`);
      keys = getKeys(value, ctx.showHidden);
      formatter = (c, v, r) => formatMap(value, c, v, r);
      if (size === 0 && keys.length === 0) return `${prefix}{}`;
      braces = [`${prefix}{`, '}'];
    } else if (types.isTypedArray(value)) {
      keys = types.ownNonIndexKeys(value, ctx.showHidden);
      const fallback = constructor === null ? TypedArrayPrototypeGetSymbolToStringTag(value) : '';
      const size = TypedArrayPrototypeGetLength(value);
      const prefix = getPrefix(constructor, tag, fallback, `(${size})`);
      braces = [`${prefix}[`, ']'];
      if (size === 0 && keys.length === 0 && !ctx.showHidden) return `${braces[0]}]`;
      formatter = (c, v, r) => formatTypedArray(value, size, c, v, r);
      extrasType = kArrayExtrasType;
    } else if (types.isMapIterator(value)) {
      keys = getKeys(value, ctx.showHidden);
      braces = getIteratorBraces('Map', tag);
      formatter = (c, v, r) => formatIterator(braces, c, v, r);
    } else if (types.isSetIterator(value)) {
      keys = getKeys(value, ctx.showHidden);
      braces = getIteratorBraces('Set', tag);
      formatter = (c, v, r) => formatIterator(braces, c, v, r);
    } else {
      noIterator = true;
    }
  }
  if (noIterator) {
    keys = getKeys(value, ctx.showHidden);
    braces = ['{', '}'];
    if (constructor === 'Object') {
      if (types.isArgumentsObject(value)) {
        braces[0] = '[Arguments] {';
      } else if (tag !== '') {
        braces[0] = `${getPrefix(constructor, tag, 'Object')}{`;
      }
      if (keys.length === 0) return `${braces[0]}}`;
    } else if (typeof value === 'function') {
      base = getFunctionBase(value, constructor, tag);
      if (keys.length === 0) return ctx.stylize(base, 'special');
    } else if (types.isRegExp(value)) {
      base = RegExpPrototypeToString(constructor !== null ? value : new RegExp(value));
      const prefix = getPrefix(constructor, tag, 'RegExp');
      if (prefix !== 'RegExp ') base = `${prefix}${base}`;
      if (keys.length === 0 || (recurseTimes > ctx.depth && ctx.depth !== null)) return ctx.stylize(base, 'regexp');
    } else if (types.isDate(value)) {
      base = Number.isNaN(DatePrototypeGetTime(value)) ? DatePrototypeToString(value) : DatePrototypeToISOString(value);
      const prefix = getPrefix(constructor, tag, 'Date');
      if (prefix !== 'Date ') base = `${prefix}${base}`;
      if (keys.length === 0) return ctx.stylize(base, 'date');
    } else if (isError(value)) {
      base = formatError(value, constructor, tag, ctx, keys);
      if (keys.length === 0) return base;
    } else if (types.isAnyArrayBuffer(value)) {
      const arrayType = types.isArrayBuffer(value) ? 'ArrayBuffer' : 'SharedArrayBuffer';
      const prefix = getPrefix(constructor, tag, arrayType);
      if (typedArray === undefined) {
        formatter = formatArrayBuffer;
      } else if (keys.length === 0) {
        return `${prefix}{ byteLength: ${formatNumber(ctx.stylize, value.byteLength, false)} }`;
      }
      braces[0] = `${prefix}{`;
      keys.unshift('byteLength');
    } else if (types.isDataView(value)) {
      braces[0] = `${getPrefix(constructor, tag, 'DataView')}{`;
      keys.unshift('byteLength', 'byteOffset', 'buffer');
    } else if (types.isPromise(value)) {
      braces[0] = `${getPrefix(constructor, tag, 'Promise')}{`;
      formatter = formatPromise;
    } else if (types.isWeakSet(value)) {
      braces[0] = `${getPrefix(constructor, tag, 'WeakSet')}{`;
      formatter = ctx.showHidden ? formatWeakSet : formatWeakCollection;
    } else if (types.isWeakMap(value)) {
      braces[0] = `${getPrefix(constructor, tag, 'WeakMap')}{`;
      formatter = ctx.showHidden ? formatWeakMap : formatWeakCollection;
    } else if (types.isModuleNamespaceObject(value)) {
      braces[0] = `${getPrefix(constructor, tag, 'Module')}{`;
      const namespaceKeys = keys;
      formatter = (c, v, r) => formatNamespaceObject(namespaceKeys, c, v, r);
    } else if (types.isBoxedPrimitive(value)) {
      base = getBoxedBase(value, ctx, keys, constructor, tag);
      if (keys.length === 0) return base;
    } else if (isURL(value) && !(recurseTimes > ctx.depth && ctx.depth !== null)) {
      base = value.href;
      if (keys.length === 0) return base;
    } else {
      if (keys.length === 0) return `${getCtxStyle(value, constructor, tag)}{}`;
      braces[0] = `${getCtxStyle(value, constructor, tag)}{`;
    }
  }

  if (recurseTimes > ctx.depth && ctx.depth !== null) {
    let constructorName = getCtxStyle(value, constructor, tag).slice(0, -1);
    if (constructor !== null) constructorName = `[${constructorName}]`;
    return ctx.stylize(constructorName, 'special');
  }
  recurseTimes += 1;
  ctx.seen.push(value);
  ctx.currentDepth = recurseTimes;
  let output;
  const indentationLvl = ctx.indentationLvl;
  try {
    output = formatter(ctx, value, recurseTimes);
    for (let i = 0; i < keys.length; i++) {
      output.push(formatProperty(ctx, value, recurseTimes, keys[i], extrasType));
    }
  } catch (err) {
    const constructorName = getCtxStyle(value, constructor, tag).slice(0, -1);
    return handleMaxCallStackSize(ctx, err, constructorName, indentationLvl);
  }
  if (ctx.circular !== undefined) {
    const index = ctx.circular.get(value);
    if (index !== undefined) {
      const reference = ctx.stylize(`<ref *${index}>`, 'special');
      if (ctx.compact !== true) base = base === '' ? reference : `${reference} ${base}`;
      else braces[0] = `${reference} ${braces[0]}`;
    }
  }
  ctx.seen.pop();

  if (ctx.sorted) {
    const comparator = ctx.sorted === true ? undefined : ctx.sorted;
    if (extrasType === kObjectType) {
      output.sort(comparator);
    } else if (keys.length > 1) {
      const sorted = output.slice(output.length - keys.length).sort(comparator);
      output.splice(output.length - keys.length, keys.length, ...sorted);
    }
  }

  const res = reduceToSingleString(ctx, output, base, braces, extrasType, recurseTimes, value);
  const budget = ctx.budget[ctx.indentationLvl] || 0;
  const newLength = budget + res.length;
  ctx.budget[ctx.indentationLvl] = newLength;
  // a huge output: what is left is inspected at depth -1 (as Node does past 2^27 characters)
  if (newLength > 2 ** 27) ctx.depth = -1;
  return res;
}

function getEmptyFormatArray() {
  return [];
}

function isError(value) {
  return types.isNativeError(value) || value instanceof Error;
}

function isInstanceof(object, proto) {
  try {
    return object instanceof proto;
  } catch {
    return false;
  }
}

function getConstructorName(obj, ctx, recurseTimes) {
  let firstProto;
  const tmp = obj;
  while (obj) {
    const descriptor = Object.getOwnPropertyDescriptor(obj, 'constructor');
    if (descriptor !== undefined && typeof descriptor.value === 'function' && descriptor.value.name !== '' &&
        isInstanceof(tmp, descriptor.value)) {
      return String(descriptor.value.name);
    }
    obj = Object.getPrototypeOf(obj);
    // a proxy in the chain: stop there rather than ask it
    if (obj !== null && types.isProxy(obj)) break;
    if (firstProto === undefined) firstProto = obj;
  }
  if (firstProto === null) return null;
  const res = types.className(tmp);
  if (recurseTimes > ctx.depth && ctx.depth !== null) return `${res} <Complex prototype>`;
  if (firstProto === undefined || types.isProxy(firstProto)) return `${res} <Complex prototype>`;
  const protoConstr = getConstructorName(firstProto, ctx, recurseTimes + 1);
  if (protoConstr === null) {
    return `${res} <${inspect(firstProto, { ...ctx, customInspect: false, depth: -1 })}>`;
  }
  return `${res} <${protoConstr}>`;
}

// a URL (when its own custom inspection is not used), shown as its href
const URLConstructor = globalThis.URL;
function isURL(value) {
  return typeof value.href === 'string' && typeof URLConstructor === 'function' && value instanceof URLConstructor;
}

function getCtxStyle(value, constructor, tag) {
  let fallback = '';
  if (constructor === null) {
    fallback = types.className(value);
    if (fallback === tag) fallback = 'Object';
  }
  return getPrefix(constructor, tag, fallback);
}

function getIteratorBraces(type, tag) {
  if (tag !== `${type} Iterator`) {
    if (tag !== '') tag += '] [';
    tag += `${type} Iterator`;
  }
  return [`[${tag}] {`, '}'];
}

function getPrefix(constructor, tag, fallback, size = '') {
  if (constructor === null) {
    if (tag !== '' && fallback !== tag) return `[${fallback}${size}: null prototype] [${tag}] `;
    return `[${fallback}${size}: null prototype] `;
  }
  if (tag !== '' && constructor !== tag) return `${constructor}${size} [${tag}] `;
  return `${constructor}${size} `;
}

function getKeys(value, showHidden) {
  let keys;
  const symbols = Object.getOwnPropertySymbols(value);
  if (showHidden) {
    keys = Object.getOwnPropertyNames(value);
    if (symbols.length !== 0) keys.push(...symbols);
  } else {
    try {
      keys = Object.keys(value);
    } catch (err) {
      // a module namespace with a binding still uninitialized
      if (!types.isModuleNamespaceObject(value)) throw err;
      keys = Object.getOwnPropertyNames(value);
    }
    if (symbols.length !== 0) keys.push(...symbols.filter((key) => ObjectPrototypePropertyIsEnumerable(value, key)));
  }
  return keys;
}

function getFunctionBase(value, constructor, tag) {
  if (types.isClass(value)) return getClassBase(value, constructor, tag);
  let type = 'Function';
  if (types.isGeneratorFunction(value)) type = `Generator${type}`;
  if (types.isAsyncFunction(value)) type = `Async${type}`;
  let base = `[${type}`;
  if (constructor === null) base += ' (null prototype)';
  if (value.name === '') base += ' (anonymous)';
  else base += `: ${value.name}`;
  base += ']';
  if (constructor !== type && constructor !== null) base += ` [${constructor}]`;
  if (tag !== '' && constructor !== tag) base += ` [${tag}]`;
  return base;
}

function getClassBase(value, constructor, tag) {
  const hasName = ObjectPrototypeHasOwnProperty(value, 'name');
  const name = (hasName && value.name) || '(anonymous)';
  let base = `class ${name}`;
  if (constructor !== 'Function' && constructor !== null) base += ` [${constructor}]`;
  if (tag !== '' && constructor !== tag) base += ` [${tag}]`;
  if (constructor !== null) {
    const superName = Object.getPrototypeOf(value).name;
    if (superName) base += ` extends ${superName}`;
  } else {
    base += ' extends [null prototype]';
  }
  return `[${base}]`;
}

function getBoxedBase(value, ctx, keys, constructor, tag) {
  let fn;
  let type;
  if (types.isNumberObject(value)) {
    fn = NumberPrototypeValueOf;
    type = 'Number';
  } else if (types.isStringObject(value)) {
    fn = StringPrototypeValueOf;
    type = 'String';
    // the indices of a String object repeat its value
    keys.splice(0, value.length);
  } else if (types.isBooleanObject(value)) {
    fn = BooleanPrototypeValueOf;
    type = 'Boolean';
  } else if (types.isBigIntObject(value)) {
    fn = BigIntPrototypeValueOf;
    type = 'BigInt';
  } else {
    fn = SymbolPrototypeValueOf;
    type = 'Symbol';
  }
  let base = `[${type}`;
  if (type !== constructor) {
    if (constructor === null) base += ' (null prototype)';
    else base += ` (${constructor})`;
  }
  base += `: ${formatPrimitive(stylizeNoColor, fn(value), ctx)}]`;
  if (tag !== '' && tag !== constructor) base += ` [${tag}]`;
  if (keys.length !== 0 || ctx.stylize === stylizeNoColor) return base;
  return ctx.stylize(base, type.toLowerCase());
}

// ---------------------------------------------------------------- errors

function getStackString(error) {
  return error.stack ? String(error.stack) : ErrorPrototypeToString(error);
}

function removeDuplicateErrorKeys(ctx, keys, err, stack) {
  if (!ctx.showHidden && keys.length !== 0) {
    for (const name of ['name', 'message', 'stack']) {
      const index = keys.indexOf(name);
      // only when the stack shows it already
      if (index !== -1 && stack.includes(err[name])) keys.splice(index, 1);
    }
  }
}

function improveStack(stack, constructor, name, tag) {
  let len = name.length;
  if (constructor === null ||
      (name.endsWith('Error') && stack.startsWith(name) && (stack.length === len || stack[len] === ':' || stack[len] === '\n'))) {
    let fallback = 'Error';
    if (constructor === null) {
      const start = /^([A-Z][a-z_ A-Z0-9[\]()-]+)(?::|\n\s+at)/.exec(stack) || /^([a-z_A-Z0-9-]*Error)$/.exec(stack);
      fallback = (start && start[1]) || '';
      len = fallback.length;
      fallback = fallback || 'Error';
    }
    const prefix = getPrefix(constructor, tag, fallback).slice(0, -1);
    if (name !== prefix) {
      if (prefix.includes(name)) {
        if (len === 0) stack = `${prefix}: ${stack}`;
        else stack = `${prefix}${stack.slice(len)}`;
      } else {
        stack = `${prefix} [${name}]${stack.slice(len)}`;
      }
    }
  }
  return stack;
}

function formatError(err, constructor, tag, ctx, keys) {
  const name = err.name != null ? String(err.name) : 'Error';
  let stack = getStackString(err);
  removeDuplicateErrorKeys(ctx, keys, err, stack);
  if ('cause' in err && (keys.length === 0 || !keys.includes('cause'))) keys.push('cause');
  // the errors of an AggregateError
  if (Array.isArray(err.errors) && (keys.length === 0 || !keys.includes('errors'))) keys.push('errors');
  stack = improveStack(stack, constructor, name, tag);

  // the message is not looked for frames in
  let pos = (err.message && stack.indexOf(err.message)) || -1;
  if (pos !== -1) pos += err.message.length;
  const stackStart = stack.indexOf('\n    at', pos);
  if (stackStart === -1) {
    // no frames: the error in brackets
    stack = `[${stack}]`;
  } else {
    const lines = getStackFrames(ctx, err, stack.slice(stackStart + 1));
    // in color, the frames of the node: libraries are dimmed, as Node dims its own
    const shown = ctx.colors ? lines.map((line) => (coreModuleRegExp.exec(line) !== null ? ctx.stylize(line, 'undefined') : line)) : lines;
    stack = `${stack.slice(0, stackStart)}\n${shown.join('\n')}`;
  }
  if (ctx.indentationLvl !== 0) {
    const indentation = ' '.repeat(ctx.indentationLvl);
    stack = stack.replace(/\n/g, `\n${indentation}`);
  }
  return stack;
}

const coreModuleRegExp = /^ {4}at (?:[^/\\(]+ \(|)node:(.+):\d+:\d+\)?$/;

// the first run (over three) of b's frames found in a: where it starts in a and how long it is
function identicalSequenceRange(a, b) {
  for (let i = 0; i < a.length - 3; i++) {
    const pos = b.indexOf(a[i]);
    if (pos !== -1) {
      const rest = b.length - pos;
      if (rest > 3) {
        let len = 1;
        const maxLen = Math.min(a.length - i, rest);
        while (maxLen > len && a[i + len] === b[pos + len]) len++;
        if (len > 3) return { len, offset: i };
      }
    }
  }
  return { len: 0, offset: 0 };
}

// the frames of a stack, the ones it shares with its cause's collapsed into one line
function getStackFrames(ctx, err, stack) {
  const frames = stack.split('\n');
  let cause;
  try {
    ({ cause } = err);
  } catch {
    // a cause getter that throws is left out
  }
  if (cause != null && isError(cause)) {
    const causeStack = getStackString(cause);
    const causeStackStart = causeStack.indexOf('\n    at');
    if (causeStackStart !== -1) {
      const causeFrames = causeStack.slice(causeStackStart + 1).split('\n');
      const { len, offset } = identicalSequenceRange(frames, causeFrames);
      if (len > 0) {
        const skipped = len - 2;
        frames.splice(offset + 1, skipped, ctx.stylize(`    ... ${skipped} lines matching cause stack trace ...`, 'undefined'));
      }
    }
  }
  return frames;
}

function handleMaxCallStackSize(ctx, err, constructorName, indentationLvl) {
  ctx.seen.pop();
  ctx.indentationLvl = indentationLvl;
  if (err instanceof RangeError && /call stack/i.test(err.message)) {
    return ctx.stylize(`[${constructorName}: Inspection interrupted prematurely. Maximum call stack size exceeded.]`, 'special');
  }
  throw err;
}

// ---------------------------------------------------------------- primitives

function addQuotes(str, quotes) {
  if (quotes === -1) return `"${str}"`;
  if (quotes === -2) return `\`${str}\``;
  return `'${str}'`;
}

// a string as a single-quoted literal (or "..." or `...` when that needs fewer escapes)
function strEscape(str) {
  let escapeTest = strEscapeSequencesRegExp;
  let escapeReplace = strEscapeSequencesReplacer;
  let singleQuote = 39;
  if (str.includes("'")) {
    if (!str.includes('"')) singleQuote = -1;
    else if (!str.includes('`') && !str.includes('${')) singleQuote = -2;
    if (singleQuote !== 39) {
      escapeTest = strEscapeSequencesRegExpSingle;
      escapeReplace = strEscapeSequencesReplacerSingle;
    }
  }
  if (str.length < 5000 && escapeTest.exec(str) === null) return addQuotes(str, singleQuote);
  if (str.length > 100) {
    str = str.replace(escapeReplace, escapeFn);
    return addQuotes(str, singleQuote);
  }
  let result = '';
  let last = 0;
  for (let i = 0; i < str.length; i++) {
    const point = str.charCodeAt(i);
    if (point === singleQuote || point === 92 || point < 32 || (point > 126 && point < 160)) {
      if (last === i) result += meta[point];
      else result += `${str.slice(last, i)}${meta[point]}`;
      last = i + 1;
    } else if (point >= 0xd800 && point <= 0xdfff) {
      if (point <= 0xdbff && i + 1 < str.length) {
        const next = str.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          i++;
          continue;
        }
      }
      result += `${str.slice(last, i)}\\u${point.toString(16)}`;
      last = i + 1;
    }
  }
  if (last !== str.length) result += str.slice(last);
  return addQuotes(result, singleQuote);
}

function addNumericSeparator(integerString) {
  let result = '';
  let i = integerString.length;
  const start = integerString.startsWith('-') ? 1 : 0;
  for (; i >= start + 4; i -= 3) result = `_${integerString.slice(i - 3, i)}${result}`;
  return i === integerString.length ? integerString : `${integerString.slice(0, i)}${result}`;
}

function addNumericSeparatorEnd(integerString) {
  let result = '';
  let i = 0;
  for (; i < integerString.length - 3; i += 3) result += `${integerString.slice(i, i + 3)}_`;
  return i === 0 ? integerString : `${result}${integerString.slice(i)}`;
}

function formatNumber(fn, number, numericSeparator) {
  if (!numericSeparator) {
    if (Object.is(number, -0)) return fn('-0', 'number');
    return fn(`${number}`, 'number');
  }
  const integer = Math.trunc(number);
  const string = String(integer);
  if (integer === number) {
    if (!Number.isFinite(number) || string.includes('e')) return fn(string, 'number');
    return fn(`${addNumericSeparator(string)}`, 'number');
  }
  if (Number.isNaN(number)) return fn(string, 'number');
  return fn(`${addNumericSeparator(string)}.${addNumericSeparatorEnd(String(number).slice(string.length + 1))}`, 'number');
}

function formatBigInt(fn, bigint, numericSeparator) {
  const string = String(bigint);
  if (!numericSeparator) return fn(`${string}n`, 'bigint');
  return fn(`${addNumericSeparator(string)}n`, 'bigint');
}

function formatPrimitive(fn, value, ctx) {
  if (typeof value === 'string') {
    let trailer = '';
    if (value.length > ctx.maxStringLength) {
      const remaining = value.length - ctx.maxStringLength;
      value = value.slice(0, ctx.maxStringLength);
      trailer = `... ${remaining} more character${remaining > 1 ? 's' : ''}`;
    }
    if (ctx.compact !== true && value.length > kMinLineWidth && value.length > ctx.breakLength - ctx.indentationLvl - 4) {
      // a long string with line breaks: one literal per line, joined by +
      return value.split(/(?<=\n)/).map((line) => fn(strEscape(line), 'string'))
        .join(` +\n${' '.repeat(ctx.indentationLvl + 2)}`) + trailer;
    }
    return fn(strEscape(value), 'string') + trailer;
  }
  if (typeof value === 'number') return formatNumber(fn, value, ctx.numericSeparator);
  if (typeof value === 'bigint') return formatBigInt(fn, value, ctx.numericSeparator);
  if (typeof value === 'boolean') return fn(`${value}`, 'boolean');
  if (typeof value === 'undefined') return fn('undefined', 'undefined');
  return fn(SymbolPrototypeToString(value), 'symbol');
}

// ---------------------------------------------------------------- entries

function remainingText(remaining) {
  return `... ${remaining} more item${remaining > 1 ? 's' : ''}`;
}

function formatArray(ctx, value, recurseTimes) {
  const valLen = value.length;
  const len = Math.min(Math.max(0, ctx.maxArrayLength), valLen);
  const remaining = valLen - len;
  const output = [];
  for (let i = 0; i < len; i++) {
    if (!ObjectPrototypeHasOwnProperty(value, i)) return formatSpecialArray(ctx, value, recurseTimes, len, output, i);
    output.push(formatProperty(ctx, value, recurseTimes, i, kArrayType));
  }
  if (remaining > 0) output.push(remainingText(remaining));
  return output;
}

// an array with holes
function formatSpecialArray(ctx, value, recurseTimes, maxLength, output, i) {
  const keys = Object.keys(value);
  let index = i;
  for (; i < keys.length && output.length < maxLength; i++) {
    const key = keys[i];
    const tmp = +key;
    if (tmp > 2 ** 32 - 2) break;
    if (`${index}` !== key) {
      if (numberRegExp.exec(key) === null) break;
      const emptyItems = tmp - index;
      const ending = emptyItems > 1 ? 's' : '';
      output.push(ctx.stylize(`<${emptyItems} empty item${ending}>`, 'undefined'));
      index = tmp;
      if (output.length === maxLength) break;
    }
    output.push(formatProperty(ctx, value, recurseTimes, key, kArrayType));
    index++;
  }
  const remaining = value.length - index;
  if (output.length !== maxLength) {
    if (remaining > 0) {
      const ending = remaining > 1 ? 's' : '';
      output.push(ctx.stylize(`<${remaining} empty item${ending}>`, 'undefined'));
    }
  } else if (remaining > 0) {
    output.push(remainingText(remaining));
  }
  return output;
}

const kHex = 4; // binding.buffer's index of hex
function formatArrayBuffer(ctx, value) {
  let buffer;
  try {
    buffer = new Uint8Array(value);
  } catch {
    return [ctx.stylize('(detached)', 'special')];
  }
  let str = binding.buffer.toString(buffer, kHex, 0, Math.min(ctx.maxArrayLength, buffer.length)).replace(/(.{2})/g, '$1 ').trim();
  const remaining = buffer.length - ctx.maxArrayLength;
  if (remaining > 0) str += ` ... ${remaining} more byte${remaining > 1 ? 's' : ''}`;
  return [`${ctx.stylize('[Uint8Contents]', 'special')}: <${str}>`];
}

function formatTypedArray(value, length, ctx, ignored, recurseTimes) {
  const maxLength = Math.min(Math.max(0, ctx.maxArrayLength), length);
  const remaining = length - maxLength;
  const output = new Array(maxLength);
  const elementFormatter = length > 0 && typeof value[0] === 'number' ? formatNumber : formatBigInt;
  for (let i = 0; i < maxLength; ++i) output[i] = elementFormatter(ctx.stylize, value[i], ctx.numericSeparator);
  if (remaining > 0) output[maxLength] = remainingText(remaining);
  if (ctx.showHidden) {
    ctx.indentationLvl += 2;
    for (const key of ['BYTES_PER_ELEMENT', 'length', 'byteLength', 'byteOffset', 'buffer']) {
      const str = formatValue(ctx, value[key], recurseTimes, true);
      output.push(`[${key}]: ${str}`);
    }
    ctx.indentationLvl -= 2;
  }
  return output;
}

function formatSet(set, ctx, ignored, recurseTimes) {
  const length = SetPrototypeGetSize(set);
  const maxLength = Math.min(Math.max(0, ctx.maxArrayLength), length);
  const remaining = length - maxLength;
  const output = [];
  ctx.indentationLvl += 2;
  const it = SetPrototypeValues(set);
  for (let i = 0; i < maxLength; i++) {
    const r = SetIteratorNext(it);
    if (r.done) break;
    output.push(formatValue(ctx, r.value, recurseTimes));
  }
  if (remaining > 0) output.push(remainingText(remaining));
  ctx.indentationLvl -= 2;
  return output;
}

function formatMap(map, ctx, ignored, recurseTimes) {
  const length = MapPrototypeGetSize(map);
  const maxLength = Math.min(Math.max(0, ctx.maxArrayLength), length);
  const remaining = length - maxLength;
  const output = [];
  ctx.indentationLvl += 2;
  const it = MapPrototypeEntries(map);
  for (let i = 0; i < maxLength; i++) {
    const r = MapIteratorNext(it);
    if (r.done) break;
    output.push(`${formatValue(ctx, r.value[0], recurseTimes)} => ${formatValue(ctx, r.value[1], recurseTimes)}`);
  }
  if (remaining > 0) output.push(remainingText(remaining));
  ctx.indentationLvl -= 2;
  return output;
}

function formatPromise(ctx, value, recurseTimes) {
  const details = types.promiseDetails(value);
  if (details[0] === 0) return [ctx.stylize('<pending>', 'special')];
  ctx.indentationLvl += 2;
  const str = formatValue(ctx, details[1], recurseTimes);
  ctx.indentationLvl -= 2;
  return [details[0] === 2 ? `${ctx.stylize('<rejected>', 'special')} ${str}` : str];
}

function formatWeakCollection(ctx) {
  return [ctx.stylize('<items unknown>', 'special')];
}

const kWeak = 0;
const kIterator = 1;
const kMapEntries = 2;

function formatSetIterInner(ctx, recurseTimes, entries, state) {
  const maxArrayLength = Math.max(ctx.maxArrayLength, 0);
  const maxLength = Math.min(maxArrayLength, entries.length);
  const output = new Array(maxLength);
  ctx.indentationLvl += 2;
  for (let i = 0; i < maxLength; i++) output[i] = formatValue(ctx, entries[i], recurseTimes);
  ctx.indentationLvl -= 2;
  // weak entries come in no order: sorted, for an output halfway stable
  if (state === kWeak && !ctx.sorted) output.sort();
  const remaining = entries.length - maxLength;
  if (remaining > 0) output.push(remainingText(remaining));
  return output;
}

function formatMapIterInner(ctx, recurseTimes, entries, state) {
  const maxArrayLength = Math.max(ctx.maxArrayLength, 0);
  // [key1, val1, key2, val2, ...]
  const len = entries.length / 2;
  const remaining = len - maxArrayLength;
  const maxLength = Math.min(maxArrayLength, len);
  const output = new Array(maxLength);
  let i = 0;
  ctx.indentationLvl += 2;
  if (state === kWeak) {
    for (; i < maxLength; i++) {
      const pos = i * 2;
      output[i] = `${formatValue(ctx, entries[pos], recurseTimes)} => ${formatValue(ctx, entries[pos + 1], recurseTimes)}`;
    }
    if (!ctx.sorted) output.sort();
  } else {
    for (; i < maxLength; i++) {
      const pos = i * 2;
      const res = [formatValue(ctx, entries[pos], recurseTimes), formatValue(ctx, entries[pos + 1], recurseTimes)];
      output[i] = reduceToSingleString(ctx, res, '', ['[', ']'], kArrayExtrasType, recurseTimes);
    }
  }
  ctx.indentationLvl -= 2;
  if (remaining > 0) output.push(remainingText(remaining));
  return output;
}

function formatWeakSet(ctx, value, recurseTimes) {
  return formatSetIterInner(ctx, recurseTimes, types.previewEntries(value), kWeak);
}

function formatWeakMap(ctx, value, recurseTimes) {
  return formatMapIterInner(ctx, recurseTimes, types.previewEntries(value), kWeak);
}

function formatIterator(braces, ctx, value, recurseTimes) {
  const { 0: entries, 1: isKeyValue } = types.previewEntries(value);
  if (isKeyValue) {
    // entries() iterators are shown as such
    braces[0] = braces[0].replace(/ Iterator] {$/, ' Entries] {');
    return formatMapIterInner(ctx, recurseTimes, entries, kMapEntries);
  }
  return formatSetIterInner(ctx, recurseTimes, entries, kIterator);
}

function formatNamespaceObject(keys, ctx, value, recurseTimes) {
  const output = new Array(keys.length);
  for (let i = 0; i < keys.length; i++) {
    try {
      output[i] = formatProperty(ctx, value, recurseTimes, keys[i], kObjectType);
    } catch (err) {
      if (!(err instanceof ReferenceError)) throw err;
      // a binding not yet initialized
      const tmp = { [keys[i]]: '' };
      output[i] = formatProperty(ctx, tmp, recurseTimes, keys[i], kObjectType);
      const pos = output[i].lastIndexOf(' ');
      output[i] = output[i].slice(0, pos + 1) + ctx.stylize('<uninitialized>', 'special');
    }
  }
  keys.length = 0;
  return output;
}

function formatProxy(ctx, proxy, recurseTimes) {
  if (recurseTimes > ctx.depth && ctx.depth !== null) return ctx.stylize('Proxy [Array]', 'special');
  recurseTimes += 1;
  ctx.indentationLvl += 2;
  const res = [formatValue(ctx, proxy[0], recurseTimes), formatValue(ctx, proxy[1], recurseTimes)];
  ctx.indentationLvl -= 2;
  return reduceToSingleString(ctx, res, '', ['Proxy [', ']'], kArrayExtrasType, recurseTimes);
}

function formatProperty(ctx, value, recurseTimes, key, type, desc, original = value) {
  let name;
  let str;
  let extra = ' ';
  desc = desc || Object.getOwnPropertyDescriptor(value, key) || { value: value[key], enumerable: true };
  if (desc.value !== undefined) {
    const diff = (ctx.compact !== true || type !== kObjectType) ? 2 : 3;
    ctx.indentationLvl += diff;
    str = formatValue(ctx, desc.value, recurseTimes);
    if (diff === 3 && ctx.breakLength < getStringWidth(str, ctx.colors)) extra = `\n${' '.repeat(ctx.indentationLvl)}`;
    ctx.indentationLvl -= diff;
  } else if (desc.get !== undefined) {
    const label = desc.set !== undefined ? 'Getter/Setter' : 'Getter';
    const s = ctx.stylize;
    const sp = 'special';
    if (ctx.getters && (ctx.getters === true ||
        (ctx.getters === 'get' && desc.set === undefined) ||
        (ctx.getters === 'set' && desc.set !== undefined))) {
      try {
        const tmp = desc.get.call(original);
        ctx.indentationLvl += 2;
        if (tmp === null) str = `${s(`[${label}:`, sp)} ${s('null', 'null')}${s(']', sp)}`;
        else if (typeof tmp === 'object') str = `${s(`[${label}]`, sp)} ${formatValue(ctx, tmp, recurseTimes)}`;
        else str = `${s(`[${label}:`, sp)} ${formatPrimitive(s, tmp, ctx)}${s(']', sp)}`;
        ctx.indentationLvl -= 2;
      } catch (err) {
        str = `${s(`[${label}:`, sp)} <Inspection threw (${err.message})>${s(']', sp)}`;
      }
    } else {
      str = ctx.stylize(`[${label}]`, sp);
    }
  } else if (desc.set !== undefined) {
    str = ctx.stylize('[Setter]', 'special');
  } else {
    str = ctx.stylize('undefined', 'undefined');
  }
  // an element: no key
  if (type === kArrayType) return str;
  if (typeof key === 'symbol') {
    name = `[${ctx.stylize(SymbolPrototypeToString(key).replace(strEscapeSequencesReplacer, escapeFn), 'symbol')}]`;
  } else if (key === '__proto__') {
    name = "['__proto__']";
  } else if (desc.enumerable === false) {
    name = `[${String(key).replace(strEscapeSequencesReplacer, escapeFn)}]`;
  } else if (keyStrRegExp.exec(key) !== null) {
    name = ctx.stylize(key, 'name');
  } else {
    name = ctx.stylize(strEscape(String(key)), 'string');
  }
  return `${name}:${extra}${str}`;
}

// ---------------------------------------------------------------- layout

function isBelowBreakLength(ctx, output, start, base) {
  let totalLength = output.length + start;
  if (totalLength + output.length > ctx.breakLength) return false;
  for (let i = 0; i < output.length; i++) {
    totalLength += ctx.colors ? removeColors(output[i]).length : output[i].length;
    if (totalLength > ctx.breakLength) return false;
  }
  return base === '' || !base.includes('\n');
}

function reduceToSingleString(ctx, output, base, braces, extrasType, recurseTimes, value) {
  if (ctx.compact !== true) {
    if (typeof ctx.compact === 'number' && ctx.compact >= 1) {
      const entries = output.length;
      if (extrasType === kArrayExtrasType && entries > 6) output = groupArrayElements(ctx, output, value);
      // the innermost ctx.compact levels go on one line when they fit
      if (ctx.currentDepth - recurseTimes < ctx.compact && entries === output.length) {
        const start = output.length + ctx.indentationLvl + braces[0].length + base.length + 10;
        if (isBelowBreakLength(ctx, output, start, base)) {
          const joinedOutput = output.join(', ');
          if (!joinedOutput.includes('\n')) {
            return `${base ? `${base} ` : ''}${braces[0]} ${joinedOutput}` + ` ${braces[1]}`;
          }
        }
      }
    }
    const indentation = `\n${' '.repeat(ctx.indentationLvl)}`;
    return `${base ? `${base} ` : ''}${braces[0]}${indentation}  ${output.join(`,${indentation}  `)}${indentation}${braces[1]}`;
  }
  if (isBelowBreakLength(ctx, output, 0, base)) {
    return `${braces[0]}${base ? ` ${base}` : ''} ${output.join(', ')} ` + braces[1];
  }
  const indentation = ' '.repeat(ctx.indentationLvl);
  const ln = base === '' && braces[0].length === 1 ? ' ' : `${base ? ` ${base}` : ''}\n${indentation}  `;
  return `${braces[0]}${ln}${output.join(`,\n${indentation}  `)} ${braces[1]}`;
}

// short array entries in columns
function groupArrayElements(ctx, output, value) {
  let totalLength = 0;
  let maxLength = 0;
  let i = 0;
  let outputLength = output.length;
  if (ctx.maxArrayLength < output.length) outputLength--;
  const separatorSpace = 2;
  const dataLen = new Array(outputLength);
  for (; i < outputLength; i++) {
    const len = getStringWidth(output[i], ctx.colors);
    dataLen[i] = len;
    totalLength += len + separatorSpace;
    if (maxLength < len) maxLength = len;
  }
  const actualMax = maxLength + separatorSpace;
  if (actualMax * 3 + ctx.indentationLvl < ctx.breakLength && (totalLength / actualMax > 5 || maxLength <= 6)) {
    const approxCharHeights = 2.5;
    const averageBias = Math.sqrt(actualMax - totalLength / output.length);
    const biasedMax = Math.max(actualMax - 3 - averageBias, 1);
    const columns = Math.min(
      Math.round(Math.sqrt(approxCharHeights * biasedMax * outputLength) / biasedMax),
      Math.floor((ctx.breakLength - ctx.indentationLvl) / actualMax),
      ctx.compact * 4,
      15,
    );
    if (columns <= 1) return output;
    const tmp = [];
    const maxLineLength = [];
    for (let i = 0; i < columns; i++) {
      let lineLength = 0;
      for (let j = i; j < output.length; j += columns) {
        if (dataLen[j] > lineLength) lineLength = dataLen[j];
      }
      maxLineLength.push(lineLength + separatorSpace);
    }
    let padStart = true;
    if (value !== undefined) {
      for (let i = 0; i < output.length; i++) {
        if (typeof value[i] !== 'number' && typeof value[i] !== 'bigint') {
          padStart = false;
          break;
        }
      }
    }
    for (let i = 0; i < outputLength; i += columns) {
      const max = Math.min(i + columns, outputLength);
      let str = '';
      let j = i;
      for (; j < max - 1; j++) {
        const padding = maxLineLength[j - i] + output[j].length - dataLen[j];
        str += padStart ? `${output[j]}, `.padStart(padding, ' ') : `${output[j]}, `.padEnd(padding, ' ');
      }
      if (padStart) {
        const padding = maxLineLength[j - i] + output[j].length - dataLen[j] - separatorSpace;
        str += output[j].padStart(padding, ' ');
      } else {
        str += output[j];
      }
      tmp.push(str);
    }
    if (ctx.maxArrayLength < output.length) tmp.push(output[outputLength]);
    output = tmp;
  }
  return output;
}

function removeColors(str) {
  return String(str).replace(colorRegExp, '');
}

const ansi = new RegExp('[\\u001B\\u009B][[\\]()#;?]*' +
  '(?:(?:(?:(?:;[-a-zA-Z\\d\\/\\#&.:=?%@~_]+)*' +
  '|[a-zA-Z\\d]+(?:;[-a-zA-Z\\d\\/\\#&.:=?%@~_]*)*)?' +
  '(?:\\u0007|\\u001B\\u005C|\\u009C))' +
  '|(?:(?:\\d{1,4}(?:;\\d{0,4})*)?' +
  '[\\dA-PR-TZcf-nq-uy=><~]))', 'g');

function stripVTControlCharacters(str) {
  validateString(str, 'str');
  return str.replace(ansi, '');
}

function isFullWidthCodePoint(code) {
  return code >= 0x1100 && (
    code <= 0x115f ||
    code === 0x2329 ||
    code === 0x232a ||
    (code >= 0x2e80 && code <= 0x3247 && code !== 0x303f) ||
    (code >= 0x3250 && code <= 0x4dbf) ||
    (code >= 0x4e00 && code <= 0xa4c6) ||
    (code >= 0xa960 && code <= 0xa97c) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe10 && code <= 0xfe19) ||
    (code >= 0xfe30 && code <= 0xfe6b) ||
    (code >= 0xff01 && code <= 0xff60) ||
    (code >= 0xffe0 && code <= 0xffe6) ||
    (code >= 0x1b000 && code <= 0x1b001) ||
    (code >= 0x1f200 && code <= 0x1f251) ||
    (code >= 0x1f300 && code <= 0x1f64f) ||
    (code >= 0x20000 && code <= 0x3fffd)
  );
}

function isZeroWidthCodePoint(code) {
  return code <= 0x1F ||
    (code >= 0x7F && code <= 0x9F) ||
    (code >= 0x300 && code <= 0x36F) ||
    (code >= 0x200B && code <= 0x200F) ||
    (code >= 0x20D0 && code <= 0x20FF) ||
    (code >= 0xFE00 && code <= 0xFE0F) ||
    (code >= 0xFE20 && code <= 0xFE2F) ||
    (code >= 0xE0100 && code <= 0xE01EF);
}

// the columns a string takes in a terminal (East Asian wide characters two, combining marks none)
function getStringWidth(str, removeControlChars = true) {
  let width = 0;
  if (removeControlChars) str = str.replace(ansi, '');
  str = str.normalize('NFC');
  for (const char of str) {
    const code = char.codePointAt(0);
    if (isFullWidthCodePoint(code)) width += 2;
    else if (!isZeroWidthCodePoint(code)) width++;
  }
  return width;
}

// ---------------------------------------------------------------- format

let CIRCULAR_ERROR_MESSAGE;
function tryStringify(arg) {
  try {
    return JSON.stringify(arg);
  } catch (err) {
    if (!CIRCULAR_ERROR_MESSAGE) {
      try {
        const a = {};
        a.a = a;
        JSON.stringify(a);
      } catch (circularError) {
        CIRCULAR_ERROR_MESSAGE = circularError.message;
      }
    }
    if (err.name === 'TypeError' && err.message === CIRCULAR_ERROR_MESSAGE) return '[Circular]';
    throw err;
  }
}

function hasBuiltInToString(value) {
  const proxy = types.proxyDetails(value);
  if (proxy !== undefined) {
    if (proxy[0] === null) return true;
    value = proxy[0];
  }
  if (typeof value[Symbol.toPrimitive] === 'function') return false;
  if (typeof value.toString !== 'function') return true;
  if (ObjectPrototypeHasOwnProperty(value, 'toString')) return false;
  let pointer = value;
  do {
    pointer = Object.getPrototypeOf(pointer);
  } while (!ObjectPrototypeHasOwnProperty(pointer, 'toString'));
  const descriptor = Object.getOwnPropertyDescriptor(pointer, 'constructor');
  return descriptor !== undefined && typeof descriptor.value === 'function' && builtInObjects.has(descriptor.value.name);
}

function formatNumberNoColor(number, options) {
  return formatNumber(stylizeNoColor, number, options?.numericSeparator ?? inspectDefaultOptions.numericSeparator);
}

function formatBigIntNoColor(bigint, options) {
  return formatBigInt(stylizeNoColor, bigint, options?.numericSeparator ?? inspectDefaultOptions.numericSeparator);
}

function formatWithOptionsInternal(inspectOptions, args) {
  const first = args[0];
  let a = 0;
  let str = '';
  let join = '';
  if (typeof first === 'string') {
    if (args.length === 1) return first;
    let tempStr;
    let lastPos = 0;
    for (let i = 0; i < first.length - 1; i++) {
      if (first.charCodeAt(i) === 37) { // '%'
        const nextChar = first.charCodeAt(++i);
        if (a + 1 !== args.length) {
          switch (nextChar) {
            case 115: { // 's'
              const tempArg = args[++a];
              if (typeof tempArg === 'number') tempStr = formatNumberNoColor(tempArg, inspectOptions);
              else if (typeof tempArg === 'bigint') tempStr = formatBigIntNoColor(tempArg, inspectOptions);
              else if (typeof tempArg !== 'object' || tempArg === null || !hasBuiltInToString(tempArg)) tempStr = String(tempArg);
              else tempStr = inspect(tempArg, { ...inspectOptions, depth: 0, colors: false, compact: 3 });
              break;
            }
            case 106: // 'j'
              tempStr = tryStringify(args[++a]);
              break;
            case 100: { // 'd'
              const tempNum = args[++a];
              if (typeof tempNum === 'bigint') tempStr = formatBigIntNoColor(tempNum, inspectOptions);
              else if (typeof tempNum === 'symbol') tempStr = 'NaN';
              else tempStr = formatNumberNoColor(Number(tempNum), inspectOptions);
              break;
            }
            case 79: // 'O'
              tempStr = inspect(args[++a], inspectOptions);
              break;
            case 111: // 'o'
              tempStr = inspect(args[++a], { ...inspectOptions, showHidden: true, showProxy: true, depth: 4 });
              break;
            case 105: { // 'i'
              const tempInteger = args[++a];
              if (typeof tempInteger === 'bigint') tempStr = formatBigIntNoColor(tempInteger, inspectOptions);
              else if (typeof tempInteger === 'symbol') tempStr = 'NaN';
              else tempStr = formatNumberNoColor(Number.parseInt(tempInteger), inspectOptions);
              break;
            }
            case 102: { // 'f'
              const tempFloat = args[++a];
              if (typeof tempFloat === 'symbol') tempStr = 'NaN';
              else tempStr = formatNumberNoColor(Number.parseFloat(tempFloat), inspectOptions);
              break;
            }
            case 99: // 'c'
              a += 1;
              tempStr = '';
              break;
            case 37: // '%'
              str += first.slice(lastPos, i);
              lastPos = i + 1;
              continue;
            default:
              continue;
          }
          if (lastPos !== i - 1) str += first.slice(lastPos, i - 1);
          str += tempStr;
          lastPos = i + 1;
        } else if (nextChar === 37) {
          str += first.slice(lastPos, i);
          lastPos = i + 1;
        }
      }
    }
    if (lastPos !== 0) {
      a++;
      join = ' ';
      if (lastPos < first.length) str += first.slice(lastPos);
    }
  }
  while (a < args.length) {
    const value = args[a];
    str += join;
    str += typeof value !== 'string' ? inspect(value, inspectOptions) : value;
    join = ' ';
    a++;
  }
  return str;
}

function format(...args) {
  return formatWithOptionsInternal(undefined, args);
}

function formatWithOptions(inspectOptions, ...args) {
  validateObject(inspectOptions, 'inspectOptions', { allowArray: true, allowFunction: true });
  return formatWithOptionsInternal(inspectOptions, args);
}

module.exports = {
  inspect,
  inspectDefaultOptions,
  format,
  formatWithOptions,
  stripVTControlCharacters,
  getStringWidth,
  stylizeWithColor,
};
