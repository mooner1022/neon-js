#!/usr/bin/env python3
"""Generates dev/mooner/neonjs/unicode/IdnaData.kt: the data of UTS #46 (Unicode IDNA Compatibility Processing).

Usage:
    python -I gen_idna.py <ucd-dir> <output-file>

<ucd-dir> must contain the following Unicode 17.0.0 files (flat directory):
  from https://www.unicode.org/Public/17.0.0/idna/ :      IdnaMappingTable.txt
  from https://www.unicode.org/Public/17.0.0/ucd/ :       UnicodeData.txt,
                                                          extracted/DerivedBidiClass.txt,
                                                          extracted/DerivedJoiningType.txt

Tables (integers encoded as in gen_unicode.py):
* STATUS: triples (start - previous start, status, mapping index) over all code points, a range ending where the next
  begins; status 0 valid, 1 mapped, 2 disallowed, 3 ignored, 4 deviation; the index is into MAPPINGS (mapped only).
* MAPPINGS: a string list (count, then each string's length and code points).
* BIDI: pairs (start - previous start, class index into BIDI_CLASSES) over all code points (Bidi_Class, for the Bidi
  rule of RFC 5893).
* JOINING: pairs (start - previous start, type index into JOINING_TYPES) over all code points (Joining_Type, for the
  ContextJ rules of RFC 5892).
* VIRAMA: the code points whose Canonical_Combining_Class is 9 (an inversion list).
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_unicode import enc_ints, enc_set, enc_strings, to_inversion_list, kotlin_literal  # noqa: E402

MAX_CP = 0x110000
STATUSES = ["valid", "mapped", "disallowed", "ignored", "deviation"]
BIDI_CLASSES = ["L", "R", "AL", "EN", "ES", "ET", "AN", "CS", "NSM", "BN", "B", "S", "WS", "ON",
                "LRE", "LRO", "RLE", "RLO", "PDF", "LRI", "RLI", "FSI", "PDI"]
JOINING_TYPES = ["U", "C", "D", "L", "R", "T"]


def fields(path):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                yield [x.strip() for x in line.split(";")]


def missing_defaults(path):
    """The '# @missing: lo..hi; value' lines of a derived property file, in order."""
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("# @missing:"):
                body = line[len("# @missing:"):].strip()
                rng, value = [x.strip() for x in body.split(";")[:2]]
                out.append((parse_range(rng), value))
    return out


def parse_range(s):
    if ".." in s:
        lo, hi = s.split("..")
        return int(lo, 16), int(hi, 16)
    return int(s, 16), int(s, 16)


def runs(values):
    """(start, value) for each run of equal values in a per-code-point list."""
    out = []
    prev = None
    for cp, v in enumerate(values):
        if v != prev:
            out.append((cp, v))
            prev = v
    return out


def enc_runs(rs, width):
    vals = []
    prev = 0
    for r in rs:
        vals.append(r[0] - prev)
        vals.extend(r[1:width])
        prev = r[0]
    return enc_ints(vals)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    ucd, out_path = sys.argv[1], sys.argv[2]
    p = lambda name: os.path.join(ucd, name)

    # ---------------- IDNA mapping: status and mapping per code point
    status = [None] * MAX_CP
    mapping = [None] * MAX_CP
    for f in fields(p("IdnaMappingTable.txt")):
        lo, hi = parse_range(f[0])
        st = f[1]
        assert st in STATUSES, st
        target = tuple(int(x, 16) for x in f[2].split()) if len(f) > 2 and f[2] else ()
        for cp in range(lo, hi + 1):
            status[cp] = st
            mapping[cp] = target if st == "mapped" else None
    assert all(s is not None for s in status), "the mapping table covers every code point"
    strings = []
    index = {}
    per_cp = []
    for cp in range(MAX_CP):
        st = STATUSES.index(status[cp])
        mi = 0
        if status[cp] == "mapped":
            t = mapping[cp]
            if t not in index:
                index[t] = len(strings)
                strings.append(list(t))
            mi = index[t]
        per_cp.append((st, mi))
    status_runs = [(start, v[0], v[1]) for start, v in runs(per_cp)]

    # ---------------- Bidi_Class: defaults of the @missing lines, then the listed values
    bidi_file = p("DerivedBidiClass.txt")
    bidi = ["L"] * MAX_CP
    for (lo, hi), value in missing_defaults(bidi_file):
        for cp in range(lo, hi + 1):
            bidi[cp] = value
    long_to_short = {"Left_To_Right": "L", "Right_To_Left": "R", "Arabic_Letter": "AL", "European_Number": "EN",
                     "European_Separator": "ES", "European_Terminator": "ET", "Arabic_Number": "AN",
                     "Common_Separator": "CS", "Nonspacing_Mark": "NSM", "Boundary_Neutral": "BN",
                     "Paragraph_Separator": "B", "Segment_Separator": "S", "White_Space": "WS", "Other_Neutral": "ON",
                     "Left_To_Right_Embedding": "LRE", "Left_To_Right_Override": "LRO",
                     "Right_To_Left_Embedding": "RLE", "Right_To_Left_Override": "RLO",
                     "Pop_Directional_Format": "PDF", "Left_To_Right_Isolate": "LRI",
                     "Right_To_Left_Isolate": "RLI", "First_Strong_Isolate": "FSI", "Pop_Directional_Isolate": "PDI"}
    for i, v in enumerate(bidi):
        bidi[i] = long_to_short.get(v, v)
    for f in fields(bidi_file):
        lo, hi = parse_range(f[0])
        for cp in range(lo, hi + 1):
            bidi[cp] = f[1]
    bidi_runs = [(start, BIDI_CLASSES.index(v)) for start, v in runs(bidi)]

    # ---------------- Joining_Type (U by default)
    joining = ["U"] * MAX_CP
    for f in fields(p("DerivedJoiningType.txt")):
        lo, hi = parse_range(f[0])
        for cp in range(lo, hi + 1):
            joining[cp] = f[1]
    joining_runs = [(start, JOINING_TYPES.index(v)) for start, v in runs(joining)]

    # ---------------- Virama: Canonical_Combining_Class 9
    virama = []
    for f in fields(p("UnicodeData.txt")):
        if f[3] == "9":
            virama.append(int(f[0], 16))

    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        w = lambda s: out.write(s + "\n")
        w("// GENERATED FILE - DO NOT EDIT.")
        w("// Generated by neonjs-core/tools/gen_idna.py from the Unicode 17.0.0 IDNA mapping table and Character Database.")
        w("// Encoding: see the documentation in gen_idna.py; decoded by Idna.")
        w("package dev.mooner.neonjs.unicode")
        w("")
        w("internal object IdnaData {")
        w("    const val VERSION = \"17.0.0\"")
        w("")
        w("    /** Code point statuses of UTS #46, in the order of their codes. */")
        w("    @JvmField val STATUSES: Array<String> = arrayOf(%s)" % ", ".join('"%s"' % s for s in STATUSES))
        w("    @JvmField val STATUS: String = %s" % kotlin_literal(enc_runs(status_runs, 3)))
        w("    @JvmField val MAPPINGS: String = %s" % kotlin_literal(enc_strings(strings)))
        w("")
        w("    /** Bidi_Class values, in the order of their codes. */")
        w("    @JvmField val BIDI_CLASSES: Array<String> = arrayOf(%s)" % ", ".join('"%s"' % s for s in BIDI_CLASSES))
        w("    @JvmField val BIDI: String = %s" % kotlin_literal(enc_runs(bidi_runs, 2)))
        w("")
        w("    /** Joining_Type values, in the order of their codes. */")
        w("    @JvmField val JOINING_TYPES: Array<String> = arrayOf(%s)" % ", ".join('"%s"' % s for s in JOINING_TYPES))
        w("    @JvmField val JOINING: String = %s" % kotlin_literal(enc_runs(joining_runs, 2)))
        w("")
        w("    @JvmField val VIRAMA: String = %s" % kotlin_literal(enc_set(to_inversion_list(virama))))
        w("")
        w("    private fun cat(vararg parts: String): String = parts.joinToString(\"\")")
        w("}")
    print("status runs %d, mappings %d, bidi runs %d, joining runs %d, viramas %d" %
          (len(status_runs), len(strings), len(bidi_runs), len(joining_runs), len(virama)))


if __name__ == "__main__":
    main()
