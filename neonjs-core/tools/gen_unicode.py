#!/usr/bin/env python3
"""Generates dev/mooner/neonjs/unicode/UnicodeData.kt from the Unicode Character Database.

Usage:
    python -I gen_unicode.py <ucd-dir> <output-file>

<ucd-dir> must contain the following Unicode 17.0.0 files (flat directory):
  from https://www.unicode.org/Public/17.0.0/ucd/ :
    UnicodeData.txt, CaseFolding.txt, SpecialCasing.txt, Scripts.txt, ScriptExtensions.txt,
    PropertyValueAliases.txt, PropList.txt, DerivedCoreProperties.txt, DerivedNormalizationProps.txt,
    extracted/DerivedGeneralCategory.txt, extracted/DerivedBinaryProperties.txt, emoji/emoji-data.txt
  from https://www.unicode.org/Public/17.0.0/emoji/ :
    emoji-sequences.txt, emoji-zwj-sequences.txt

Encoding of the generated tables
--------------------------------
Integers are written as variable-length digit strings over a 92-character printable ASCII alphabet
(0x20..0x7E without '"', '$' and '\\'), base 46, most significant digit first: every digit except the last
uses the upper half of the alphabet ("continuation" digits), the last digit uses the lower half.

* A code point set is an inversion list p0 < p1 < ... (ranges [p0,p1), [p2,p3), ...), stored as
  p0, p1-p0-1, p2-p1-1, ...
* A mapping table (case folding) is a list of pairs sorted by key, stored as
  key - previousKey - 1, zigzag(value - key).
* A string list is stored as: count, then for each string its length followed by its code points.
"""

import os
import sys

UNICODE_VERSION = "17.0.0"
MAX_CP = 0x110000

ALPHABET = [chr(c) for c in range(0x20, 0x7F) if chr(c) not in '"$\\']
assert len(ALPHABET) == 92
HALF = 46


def enc_int(v, out):
    assert v >= 0
    digits = []
    while True:
        digits.append(v % HALF)
        v //= HALF
        if v == 0:
            break
    digits.reverse()
    for i, d in enumerate(digits):
        out.append(ALPHABET[d + HALF] if i < len(digits) - 1 else ALPHABET[d])


def enc_ints(values):
    out = []
    for v in values:
        enc_int(v, out)
    return "".join(out)


def zigzag(v):
    return v * 2 if v >= 0 else -v * 2 - 1


# ------------------------------------------------------------------ code point sets

def to_inversion_list(cps):
    """cps: iterable of code points -> sorted inversion list."""
    pts = []
    prev = None
    for c in sorted(set(cps)):
        if prev is not None and c == prev + 1:
            pts[-1] = c + 1
        else:
            pts.append(c)
            pts.append(c + 1)
        prev = c
    return pts


def ranges_to_inversion_list(ranges):
    """ranges: list of (lo, hi) inclusive, possibly unsorted/overlapping."""
    rs = sorted(ranges)
    pts = []
    for lo, hi in rs:
        if pts and lo <= pts[-1]:
            pts[-1] = max(pts[-1], hi + 1)
        else:
            pts.append(lo)
            pts.append(hi + 1)
    return pts


def enc_set(pts):
    vals = []
    prev = None
    for p in pts:
        if prev is None:
            vals.append(p)
        else:
            assert p > prev
            vals.append(p - prev - 1)
        prev = p
    return enc_ints(vals)


def enc_map(pairs):
    vals = []
    prev = -1
    for k, v in sorted(pairs):
        assert k > prev
        vals.append(k - prev - 1)
        vals.append(zigzag(v - k))
        prev = k
    return enc_ints(vals)


def enc_strings(strings):
    vals = [len(strings)]
    for s in strings:
        vals.append(len(s))
        vals.extend(s)
    return enc_ints(vals)


# ------------------------------------------------------------------ UCD parsing

def read_lines(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                yield [x.strip() for x in line.split(";")]


def parse_range(s):
    if ".." in s:
        a, b = s.split("..")
        return int(a, 16), int(b, 16)
    v = int(s, 16)
    return v, v


def range_property_file(path):
    """Returns {value: [(lo, hi), ...]} for files of the form 'range ; value'."""
    res = {}
    for f in read_lines(path):
        lo, hi = parse_range(f[0])
        res.setdefault(f[1], []).append((lo, hi))
    return res


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    ucd = sys.argv[1]
    out_path = sys.argv[2]
    p = lambda name: os.path.join(ucd, name)

    # ---------------- General_Category
    gc_ranges = range_property_file(p("DerivedGeneralCategory.txt"))
    gc_of = bytearray(MAX_CP)  # index into leaf list
    gc_names = []   # list of alias lists
    gc_groups = {}  # short name -> member short names
    for f in read_lines(p("PropertyValueAliases.txt")):
        if f[0] != "gc":
            continue
        gc_names.append(f[1:])
    with open(p("PropertyValueAliases.txt"), encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("gc ;") and "#" in line:
                fields = [x.strip() for x in line.split("#", 1)[0].split(";")]
                members = [m.strip() for m in line.split("#", 1)[1].split("|")]
                gc_groups[fields[1]] = members
    gc_sets = {}
    for names in gc_names:
        short = names[0]
        if short in gc_groups:
            rs = []
            for m in gc_groups[short]:
                rs.extend(gc_ranges.get(m, []))
        else:
            rs = gc_ranges.get(short, [])
        gc_sets[short] = ranges_to_inversion_list(rs)
    # Cn must cover every unlisted code point
    listed = ranges_to_inversion_list([r for v in gc_ranges.values() for r in v])
    assert listed == [0, MAX_CP], "DerivedGeneralCategory.txt must cover all code points"

    # ---------------- Scripts / Script_Extensions
    sc_ranges = range_property_file(p("Scripts.txt"))
    sc_names = []  # alias lists, short name first
    for f in read_lines(p("PropertyValueAliases.txt")):
        if f[0] == "sc":
            sc_names.append(f[1:])
    long_to_short = {n[1]: n[0] for n in sc_names}
    short_to_long = {n[0]: n[1] for n in sc_names}
    script_of = {}
    for long_name, rs in sc_ranges.items():
        for lo, hi in rs:
            for c in range(lo, hi + 1):
                script_of[c] = long_to_short[long_name]
    scx_of = {}
    for f in read_lines(p("ScriptExtensions.txt")):
        lo, hi = parse_range(f[0])
        scripts = f[1].split()
        for c in range(lo, hi + 1):
            scx_of[c] = scripts
    sc_sets = {}
    scx_sets = {}
    for names in sc_names:
        short = names[0]
        if short == "Zzzz":
            cps = [c for c in range(MAX_CP) if c not in script_of]
            sc_sets[short] = to_inversion_list(cps)
            scx_sets[short] = sc_sets[short]
            continue
        sc_sets[short] = ranges_to_inversion_list(sc_ranges.get(short_to_long[short], []))
        cps = []
        for lo, hi in sc_ranges.get(short_to_long[short], []):
            for c in range(lo, hi + 1):
                if c not in scx_of:
                    cps.append(c)
        for c, lst in scx_of.items():
            if short in lst:
                cps.append(c)
        scx_sets[short] = to_inversion_list(cps)

    # ---------------- binary properties (ECMA-262 table "Binary Unicode property aliases")
    binary_spec = [
        ("ASCII", []), ("ASCII_Hex_Digit", ["AHex"]), ("Alphabetic", ["Alpha"]), ("Any", []), ("Assigned", []),
        ("Bidi_Control", ["Bidi_C"]), ("Bidi_Mirrored", ["Bidi_M"]), ("Case_Ignorable", ["CI"]), ("Cased", []),
        ("Changes_When_Casefolded", ["CWCF"]), ("Changes_When_Casemapped", ["CWCM"]),
        ("Changes_When_Lowercased", ["CWL"]), ("Changes_When_NFKC_Casefolded", ["CWKCF"]),
        ("Changes_When_Titlecased", ["CWT"]), ("Changes_When_Uppercased", ["CWU"]), ("Dash", []),
        ("Default_Ignorable_Code_Point", ["DI"]), ("Deprecated", ["Dep"]), ("Diacritic", ["Dia"]), ("Emoji", []),
        ("Emoji_Component", ["EComp"]), ("Emoji_Modifier", ["EMod"]), ("Emoji_Modifier_Base", ["EBase"]),
        ("Emoji_Presentation", ["EPres"]), ("Extended_Pictographic", ["ExtPict"]), ("Extender", ["Ext"]),
        ("Grapheme_Base", ["Gr_Base"]), ("Grapheme_Extend", ["Gr_Ext"]), ("Hex_Digit", ["Hex"]),
        ("IDS_Binary_Operator", ["IDSB"]), ("IDS_Trinary_Operator", ["IDST"]), ("ID_Continue", ["IDC"]),
        ("ID_Start", ["IDS"]), ("Ideographic", ["Ideo"]), ("Join_Control", ["Join_C"]),
        ("Logical_Order_Exception", ["LOE"]), ("Lowercase", ["Lower"]), ("Math", []),
        ("Noncharacter_Code_Point", ["NChar"]), ("Pattern_Syntax", ["Pat_Syn"]),
        ("Pattern_White_Space", ["Pat_WS"]), ("Quotation_Mark", ["QMark"]), ("Radical", []),
        ("Regional_Indicator", ["RI"]), ("Sentence_Terminal", ["STerm"]), ("Soft_Dotted", ["SD"]),
        ("Terminal_Punctuation", ["Term"]), ("Unified_Ideograph", ["UIdeo"]), ("Uppercase", ["Upper"]),
        ("Variation_Selector", ["VS"]), ("White_Space", ["space"]), ("XID_Continue", ["XIDC"]),
        ("XID_Start", ["XIDS"]),
    ]
    binary_src = {}
    for fname in ["PropList.txt", "DerivedCoreProperties.txt", "DerivedNormalizationProps.txt",
                  "DerivedBinaryProperties.txt", "emoji-data.txt"]:
        for f in read_lines(p(fname)):
            if len(f) != 2:
                continue  # skip non-binary entries (e.g. InCB, NFKC_CF)
            lo, hi = parse_range(f[0])
            binary_src.setdefault(f[1], []).append((lo, hi))
    binary_sets = {}
    for name, _ in binary_spec:
        if name == "ASCII":
            binary_sets[name] = [0, 0x80]
        elif name == "Any":
            binary_sets[name] = [0, MAX_CP]
        elif name == "Assigned":
            cn = gc_sets["Cn"]
            binary_sets[name] = invert(cn)
        else:
            assert name in binary_src, name
            binary_sets[name] = ranges_to_inversion_list(binary_src[name])

    # ---------------- case folding (simple, statuses C and S)
    scf = {}
    for f in read_lines(p("CaseFolding.txt")):
        if f[1] in ("C", "S"):
            scf[int(f[0], 16)] = int(f[2], 16)

    # ---------------- non-unicode Canonicalize (toUppercase must yield a single code unit)
    simple_upper = {}
    for f in read_lines(p("UnicodeData.txt")):
        if f[12]:
            simple_upper[int(f[0], 16)] = int(f[12], 16)
    special_upper = {}
    for f in read_lines(p("SpecialCasing.txt")):
        if len(f) >= 5 and f[4]:
            continue  # conditional mapping
        special_upper[int(f[0], 16)] = [int(x, 16) for x in f[3].split()]
    canon = []
    for c in range(0x10000):
        u = special_upper.get(c)
        if u is None:
            u = [simple_upper.get(c, c)]
        if len(u) != 1 or u[0] > 0xFFFF:
            continue
        cu = u[0]
        if c >= 128 and cu < 128:
            continue
        if cu != c:
            canon.append((c, cu))

    # ---------------- properties of strings
    seq_props = ["Basic_Emoji", "Emoji_Keycap_Sequence", "RGI_Emoji_Modifier_Sequence",
                 "RGI_Emoji_Flag_Sequence", "RGI_Emoji_Tag_Sequence", "RGI_Emoji_ZWJ_Sequence"]
    seq_chars = {n: [] for n in seq_props}
    seq_strings = {n: [] for n in seq_props}
    for fname in ["emoji-sequences.txt", "emoji-zwj-sequences.txt"]:
        for f in read_lines(p(fname)):
            prop = f[1]
            if prop not in seq_chars:
                raise Exception("unknown sequence property " + prop)
            if ".." in f[0]:
                lo, hi = parse_range(f[0])
                seq_chars[prop].extend(range(lo, hi + 1))
            else:
                cps = [int(x, 16) for x in f[0].split()]
                if len(cps) == 1:
                    seq_chars[prop].append(cps[0])
                else:
                    seq_strings[prop].append(cps)

    # ---------------- output
    lines = []
    w = lines.append
    w("// GENERATED FILE - DO NOT EDIT.")
    w("// Generated by neonjs-core/tools/gen_unicode.py from the Unicode %s Character Database." % UNICODE_VERSION)
    w("// Encoding: see the documentation in gen_unicode.py; decoded by UnicodeTables.")
    w("package dev.mooner.neonjs.unicode")
    w("")
    w("internal object UnicodeData {")
    w('    const val VERSION = "%s"' % UNICODE_VERSION)
    w("")
    emit_table(w, "GC", gc_names, [gc_sets[n[0]] for n in gc_names])
    emit_table(w, "SCRIPT", sc_names, [sc_sets[n[0]] for n in sc_names])
    w("    /** Script_Extensions sets, parallel to SCRIPT_NAMES. */")
    emit_array(w, "SCX_DATA", [enc_set(scx_sets[n[0]]) for n in sc_names])
    binary_names = [[n] + a for n, a in binary_spec]
    emit_table(w, "BINARY", binary_names, [binary_sets[n] for n, _ in binary_spec])
    w("    /** Properties of strings: single code points (sets) and multi-code-point strings. */")
    emit_array(w, "SEQ_NAMES", seq_props, raw=True)
    emit_array(w, "SEQ_CHARS", [enc_set(to_inversion_list(seq_chars[n])) for n in seq_props])
    emit_array(w, "SEQ_STRINGS", [enc_strings(seq_strings[n]) for n in seq_props])
    w("    /** Simple case folding (CaseFolding.txt statuses C and S). */")
    emit_value(w, "SIMPLE_CASE_FOLDING", enc_map(scf.items()))
    w("    /** Canonicalize for non-unicode case-insensitive matching (single code unit toUppercase). */")
    emit_value(w, "NON_UNICODE_CANONICALIZE", enc_map(canon))
    w("    private fun cat(vararg parts: String): String = parts.joinToString(\"\")")
    w("}")
    w("")
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))
    print("wrote %s: %d gc, %d scripts, %d binary, %d scf, %d canon" %
          (out_path, len(gc_names), len(sc_names), len(binary_spec), len(scf), len(canon)))


def invert(pts):
    if pts and pts[0] == 0:
        res = pts[1:]
    else:
        res = [0] + pts
    if res and res[-1] == MAX_CP:
        res = res[:-1]
    else:
        res = res + [MAX_CP]
    return res


CHUNK = 20000


def kotlin_literal(s):
    """A Kotlin expression for string s, split into chunks below the class-file constant limit."""
    if len(s) <= CHUNK:
        return '"%s"' % s
    parts = ['"%s"' % s[i:i + CHUNK] for i in range(0, len(s), CHUNK)]
    return "cat(%s)" % ", ".join(parts)


def emit_value(w, name, s):
    w("    @JvmField val %s: String = %s" % (name, kotlin_literal(s)))
    w("")


def emit_array(w, name, values, raw=False):
    w("    @JvmField val %s: Array<String> = arrayOf(" % name)
    for v in values:
        w("        %s," % (('"%s"' % v) if raw else kotlin_literal(v)))
    w("    )")
    w("")


def emit_table(w, prefix, names, sets):
    w("    /** %s value names: comma-separated aliases. */" % prefix)
    emit_array(w, prefix + "_NAMES", [",".join(n) for n in names], raw=True)
    w("    /** %s code point sets (encoded inversion lists), parallel to %s_NAMES. */" % (prefix, prefix))
    emit_array(w, prefix + "_DATA", [enc_set(s) for s in sets])


if __name__ == "__main__":
    main()
