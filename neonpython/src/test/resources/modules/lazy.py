def __getattr__(name):
    return 'lazy ' + name
real = 'real'
