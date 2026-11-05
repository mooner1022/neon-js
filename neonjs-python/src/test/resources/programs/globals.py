counter = 0

def bump():
    global counter
    counter += 1

for _ in range(5):
    bump()
print(counter)

def first_even(xs):
    for x in xs:
        if x % 2 == 0:
            return x
    return None

print(first_even([1, 3, 4, 5]), first_even([1, 3]))

def apply(f, v):
    return f(v)

def double(v):
    return v * 2

print(apply(double, 21), apply(len, "four"))
print(print)
