total = 0
i = 0
while i < 100000:
    total += i
    i += 1
print(total, i)

def sum_squares(n):
    s = 0
    for k in range(n):
        s += k * k
    return s

print(sum_squares(1000))
print(sum_squares(100000))

n = 0
while True:
    n += 1
    if n < 3:
        continue
    if n > 5:
        break
print(n)
for j in range(3):
    pass
else:
    print("done", j)
w = 10
while w > 0:
    w -= 3
else:
    print("while else", w)
