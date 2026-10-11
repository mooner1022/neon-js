"""Ports Node.js's own lib files into neonjs-node's lib resources (dev/mooner/neonjs/node/lib).

Each line of PORTS is `<path in Node's lib> <resource name> [<rules in tools/ports>]`. A rules file defines
REPLACEMENTS = [(old, new), ...] (each old must occur exactly once in Node's file), and optionally NOTE (said in the
header), PREPEND and APPEND. Node's sources are expected in third_party/node (the release named in VERSION).

Usage, from the repository root: python neonjs-node/tools/port_node.py [resource-name...]
(without names, every port is made again; after a Node update, check the rules still apply and run the tests).
"""
import os
import runpy
import sys

VERSION = 'v22.12.0'
PORTS = """
internal/per_context/primordials.js  primordials.js                       primordials
internal/validators.js               internal_validators.js               validators
internal/util/colors.js              internal_util_colors.js              colors
internal/util/comparisons.js         internal_util_comparisons.js         comparisons
internal/assert.js                   internal_assert.js
internal/assert/assertion_error.js   internal_assert_assertion_error.js
internal/assert/myers_diff.js        internal_assert_myers_diff.js
internal/assert/calltracker.js       internal_assert_calltracker.js
internal/events/abort_listener.js    internal_events_abort_listener.js    abort_listener
internal/events/symbols.js           internal_events_symbols.js
internal/fixed_queue.js              internal_fixed_queue.js
assert.js                            assert.js
assert/strict.js                     assert_strict.js
events.js                            events.js                            events
string_decoder.js                    string_decoder.js                    string_decoder
"""

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
LIB = os.path.join(ROOT, 'neonjs-node/src/main/resources/dev/mooner/neonjs/node/lib')


def port(src_rel, target, rules):
    src = open(os.path.join(ROOT, 'third_party/node/lib', src_rel), encoding='utf-8').read().replace('\r\n', '\n')
    cfg = runpy.run_path(os.path.join(TOOLS, 'ports', rules + '.py')) if rules else {}
    for old, new in cfg.get('REPLACEMENTS', []):
        n = src.count(old)
        if n != 1:
            sys.exit(f'{target}: expected one occurrence, found {n}: {old[:80]!r}')
        src = src.replace(old, new)
    note = f'; {cfg["NOTE"]}' if cfg.get('NOTE') else ''
    header = f'// Ported from Node.js {VERSION} lib/{src_rel} (MIT license, see NOTICE){note}.\n'
    out = header + cfg.get('PREPEND', '') + src + cfg.get('APPEND', '')
    open(os.path.join(LIB, target), 'w', encoding='utf-8', newline='\n').write(out)
    print('ported', target)


def main(names):
    for line in PORTS.strip().splitlines():
        parts = line.split()
        src_rel, target = parts[0], parts[1]
        rules = parts[2] if len(parts) > 2 else None
        if not names or target in names:
            port(src_rel, target, rules)


if __name__ == '__main__':
    main(sys.argv[1:])
