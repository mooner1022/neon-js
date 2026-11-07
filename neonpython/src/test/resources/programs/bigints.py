def fact(n):
    r = 1
    for i in range(2, n + 1):
        r *= i
    return r

print(fact(30))
print(fact(25) // fact(23))
x = 9223372036854775807
print(x + 1, -x - 2, x * x)
print(2 ** 100, (2 ** 100) % 1000007, -(2 ** 64) // 3)
print(1 << 70, (1 << 70) >> 68, 255 & 15, 8 | 3, 6 ^ 3, ~5)
