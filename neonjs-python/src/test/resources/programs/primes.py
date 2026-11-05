def primes(n):
    out = []
    for c in range(2, n):
        is_p = True
        d = 2
        while d * d <= c:
            if c % d == 0:
                is_p = False
                break
            d += 1
        if is_p:
            out.append(c)
    return out

ps = primes(3000)
print(len(ps), ps[-1], sum(ps))

def collatz(n):
    steps = 0
    while n != 1:
        if n % 2 == 0:
            n = n // 2
        else:
            n = 3 * n + 1
        steps += 1
    return steps

best = 0
arg = 0
for k in range(1, 3000):
    c = collatz(k)
    if c > best:
        best = c
        arg = k
print(arg, best)
