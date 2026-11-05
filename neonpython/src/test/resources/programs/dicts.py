d = {"a": 1, "b": 2}
d["c"] = 3
for k in d:
    print(k, d[k])
print(d.get("z", 0), len(d), "a" in d, "q" not in d)
counts = {}
for w in ["x", "y", "x", "z", "x"]:
    counts[w] = counts.get(w, 0) + 1
print(counts)
print(list(counts.keys()), list(counts.values()))
for k, v in counts.items():
    print(k, "=", v)
m = {1: "one", 2.0: "two"}
m[True] = "true"
m[2] = "TWO"
print(m)
