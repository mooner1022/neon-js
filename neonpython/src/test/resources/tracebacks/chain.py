class AppError(Exception):
    pass


def parse(x):
    return int(x)


def load(xs):
    out = []
    for x in xs:
        try:
            out.append(parse(x))
        except ValueError as e:
            raise AppError("bad item %r" % x) from e
    return out


def run():
    try:
        load(["1", "two"])
    except AppError:
        {}["missing"]


run()
