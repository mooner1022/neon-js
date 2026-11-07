# Copies every .py file under SRC into OUT with what CPython's parser makes of it beside it (name.ast: its
# ast.dump, or "SyntaxError: <msg>"), for PyAstTest's check of a whole tree (-Dneonpy.astDir=OUT):
#
#   python -X utf8 -I record_tree.py SRC OUT      (CPython 3.14; SRC its Lib, say)
import ast
import os
import shutil
import sys

src, out = sys.argv[1], sys.argv[2]
n = 0
for root, dirs, files in os.walk(src):
    dirs[:] = [d for d in dirs if d not in ("__pycache__", "site-packages")]
    for f in files:
        if not f.endswith(".py"):
            continue
        path = os.path.join(root, f)
        try:
            text = open(path, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        rel = os.path.relpath(path, src)
        dest = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        try:
            dump = ast.dump(ast.parse(text))
        except (SyntaxError, ValueError) as e:
            dump = "SyntaxError: " + str(getattr(e, "msg", e))
        except RecursionError:
            continue
        open(dest, "w", encoding="utf-8", newline="\n").write(text)
        open(dest[:-3] + ".ast", "w", encoding="utf-8", newline="\n").write(dump + "\n")
        n += 1
print(n, "files")
