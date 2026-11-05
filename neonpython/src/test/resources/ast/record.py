# Records what CPython's parser makes of each snippet of corpus.py (snippets separated by lines "#---"): one
# ast.dump per line, "SyntaxError: <msg>" where CPython refuses it. PyAstTest compares NeonPy's parser with this.
#
#   python -X utf8 -I record.py corpus.py > corpus.ast      (CPython 3.14)
import ast
import sys

src = open(sys.argv[1], encoding="utf-8").read()
for snippet in src.split("\n#---\n"):
    try:
        print(ast.dump(ast.parse(snippet)))
    except SyntaxError as e:
        print("SyntaxError: " + e.msg)
