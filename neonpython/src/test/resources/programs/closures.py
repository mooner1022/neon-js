# closures: cells shared by the functions that read them
def counter():
    count = 0
    def inc(by=1):
        nonlocal count
        count += by
        return count
    def get():
        return count
    return inc, get

inc, get = counter()
inc()
inc(5)
print(get())
inc2, get2 = counter()
print(inc2(), get(), get2())

# a parameter in a cell
def adder(n):
    return lambda x: x + n

add3 = adder(3)
print(add3(4), adder(10)(1))

# three levels: the middle one passes the cell through
def outer():
    x = 'outer'
    def middle():
        def inner():
            return x
        return inner
    return middle()

print(outer()())

def make():
    fs = []
    for i in range(3):
        fs.append(lambda: i)
    return fs

fs = make()
print(fs[0](), fs[1](), fs[2]())

# nonlocal assignment seen by the enclosing function
def change():
    v = 1
    def set_it():
        nonlocal v
        v = 2
    set_it()
    return v

print(change())

# global inside a function
total = 0
def add_total(x):
    global total
    total += x

add_total(5)
add_total(6)
print(total)

# decorators
def twice(fn):
    def wrapper(*args, **kwargs):
        return fn(*args, **kwargs) * 2
    return wrapper

def tag(name):
    def deco(fn):
        def wrapped(*a):
            return name + ':' + str(fn(*a))
        return wrapped
    return deco

@tag('t')
@twice
def five():
    return 5

print(five())

@twice
def plus(a, b=1):
    return a + b

print(plus(1), plus(2, b=3))

# a cell read before its value
def early():
    def read():
        return later
    try_it = read
    later = 'set'
    return try_it()

print(early())
