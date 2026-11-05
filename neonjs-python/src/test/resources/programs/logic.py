x = 5
print(1 < x < 10, 1 < x > 10, x == 5 and x != 4, None or "dflt", 0 and 1)
print(not x, x is None, x is not None, 3 in [1, 2, 3], "z" not in "abc")
print(1 if x > 3 else 2, [] or [0], 2 in range(5), 7 in range(5))
flag = x > 3
if flag:
    print("flag")
y = 0
while not y > 3:
    y += 1
print(y, True + True, False * 3)
