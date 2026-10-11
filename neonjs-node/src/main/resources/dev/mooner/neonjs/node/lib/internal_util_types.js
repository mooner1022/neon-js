'use strict';
// util.types: the engine's own checks of internal slots (binding.types), which never run script code.

const types = {};
for (const name of Object.getOwnPropertyNames(binding.types)) {
  if (name.startsWith('is')) types[name] = binding.types[name];
}

module.exports = types;
