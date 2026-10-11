'use strict';
// What the libraries take from Node's lib/internal/options.js (MIT license, see NOTICE): there is no command line
// here, so each option has its default (the experimental flags are off).

function getOptionValue(option) {
  return false;
}

module.exports = {
  getOptionValue,
};
