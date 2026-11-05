s = "hello"
print(s.upper(), len(s), s[1], s[-1])
parts = []
for ch in s:
    parts.append(ch)
print("-".join(parts))
print("a" + "b" * 3, 2 * "xy")
print(repr("it's"), repr('say "hi"'), repr("tab\there"), "é", len("한국어"))
print(str(12), str(1.5), int("42") + 1, float("2.5"), int(-3.7), bool(""), bool("x"))
print("abc" < "abd", "b" > "abc", "x" == "x")
