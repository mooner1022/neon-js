def grow(n):
    s = 1
    i = 0
    while i < n:
        s = s * 3 + i
        i += 1
    return s

def mix(n):
    t = 0
    for i in range(n):
        if i % 3 == 0:
            t += i // 2
        else:
            t -= i % 7
    return t

def floats(n):
    x = 0.0
    y = 1.5
    for i in range(n):
        x = x + y * 0.5 - 0.25
        if x > 100.0:
            x = x - 100.0
    return x

def ints_then_floats(n):
    a = 0
    for i in range(n):
        a = a + 1
    b = a + 0.5
    return b

for k in range(3):
    print(grow(10), grow(50), grow(200) % 1000000007)
print(mix(100000), floats(100000), ints_then_floats(10000))
print(-(2 ** 63) // -1, (-7) // 2, (-7) % 3, 7 // -2, 7 % -3)
