# threading over _thread: the main thread, locks, conditions, events, semaphores, barriers, thread-locals, queues,
# concurrent.futures, and _thread's own checks (no thread is started here: NeonPy runs one; PyThreadTest starts them)
import _thread
import atexit
import concurrent.futures as cf
import io
import queue
import sys
import threading
import time


def show(f):
    try:
        r = f()
        print('ok', r if isinstance(r, (bool, int, float, str, tuple, type(None))) else type(r).__name__)
    except BaseException as e:
        print(type(e).__name__, e)


def identity():
    t = threading.current_thread()
    print(t is threading.main_thread(), t.name, type(t).__name__, t.daemon, t.is_alive())
    print(t.ident == threading.get_ident() == _thread.get_ident() == _thread._get_main_thread_ident())
    print(t.native_id == threading.get_native_id(), type(threading.get_native_id()).__name__)
    print(threading.active_count(), threading.enumerate() == [t], _thread._count())
    print(threading.TIMEOUT_MAX, threading.ThreadError is RuntimeError, threading.Lock is _thread.LockType)
    print(type(threading.RLock()).__module__, type(threading.RLock()).__name__, threading.local is _thread._local)
    print(repr(t).split(',')[0], t._os_thread_handle.is_done(), t._os_thread_handle.ident == t.ident)


def locks():
    lk = threading.Lock()
    print(lk.acquire(), lk.locked(), lk.acquire(False), lk.acquire(timeout=0), lk.acquire(blocking=False))
    t0 = time.monotonic()
    r = lk.acquire(timeout=0.05)
    print(r, time.monotonic() - t0 >= 0.04, repr(lk).split(' at ')[0])
    lk.release()
    print(lk.locked(), repr(lk).split(' at ')[0])
    show(lk.release)
    with lk:
        print(lk.locked())
    print(lk.locked())
    show(lambda: lk.acquire(False, 1))
    show(lambda: lk.acquire(True, -2))
    show(lambda: lk.acquire(timeout='x'))
    show(lambda: setattr(lk, 'x', 1))


def rlocks():
    r = threading.RLock()
    print(r.acquire(), r.acquire(), r._recursion_count(), r._is_owned(), r.locked())
    print(repr(r).split(' at ')[0].replace(str(threading.get_ident()), 'ME'))
    st = r._release_save()
    print(st[0], st[1] == threading.get_ident(), r.locked(), r._is_owned(), r._recursion_count())
    r._acquire_restore(st)
    print(r._recursion_count())
    r.release()
    r.release()
    print(r.locked(), repr(r).split(' at ')[0])
    show(r.release)
    show(r._release_save)
    with r, r:
        print(r._recursion_count())
    class R(_thread.RLock):
        pass
    print(R().acquire(), repr(R()).split(' at ')[0])


def conditions():
    c = threading.Condition()
    with c:
        t0 = time.monotonic()
        print(c.wait(0), c.wait(0.03), time.monotonic() - t0 >= 0.02)
        print(c.wait_for(lambda: True), c.wait_for(lambda: False, 0.02))
        c.notify()
        c.notify_all()
    show(lambda: c.wait(0))
    show(c.notify)
    c2 = threading.Condition(threading.Lock())
    with c2:
        print(c2.wait(0.01), c2._is_owned())
    print(c2._is_owned())


def events():
    e = threading.Event()
    t0 = time.monotonic()
    print(e.is_set(), e.wait(0), e.wait(0.03), time.monotonic() - t0 >= 0.02)
    e.set()
    print(e.is_set(), e.wait(), e.wait(5))
    e.clear()
    print(e.is_set(), repr(e).split(': ')[1])


def semaphores():
    s = threading.Semaphore(2)
    print(s.acquire(), s.acquire(), s.acquire(False), s.acquire(timeout=0.02))
    s.release(2)
    print(s._value, repr(s).split(': ')[1])
    b = threading.BoundedSemaphore(1)
    print(b.acquire())
    b.release()
    show(b.release)
    show(lambda: threading.Semaphore(-1))
    show(lambda: s.acquire(False, 1))
    show(lambda: s.release(0))
    with s:
        print(s._value)


def barriers():
    b = threading.Barrier(1, action=lambda: print('action'))
    print(b.wait(), b.parties, b.n_waiting, b.broken)
    b2 = threading.Barrier(2, timeout=0.02)
    try:
        b2.wait()
    except threading.BrokenBarrierError as e:
        print(type(e).__name__, b2.broken, repr(b2).split(': ')[1])
    b2.reset()
    print(b2.broken, b2.n_waiting)
    b2.abort()
    print(b2.broken)
    show(lambda: threading.Barrier(0))


def thread_locals():
    loc = threading.local()
    loc.x = 1
    print(loc.x, loc.__dict__, type(loc).__module__, type(loc).__name__)
    class L(threading.local):
        def __init__(self, v):
            self.v = v
    print(L(5).v, L(6).__dict__)
    show(lambda: threading.local(1))
    show(lambda: setattr(loc, '__dict__', {}))
    show(lambda: delattr(loc, '__dict__'))
    show(lambda: loc.y)
    del loc.x
    print(hasattr(loc, 'x'))
    import _threading_local
    pl = _threading_local.local()
    pl.y = 2
    print(pl.y, pl.__dict__)


def threads():
    t = threading.Thread(target=print, name='w', args=(1,))
    print(t.name, t.is_alive(), t.ident, t.native_id, t.daemon, repr(t))
    show(t.join)
    print(threading.Thread(daemon=True).daemon, threading.Thread(target=threads).name)
    show(threading.main_thread().join)
    tm = threading.Timer(1, print)
    print(tm.interval, tm.finished.is_set())
    tm.cancel()
    print(tm.finished.is_set())
    f = lambda *a: None
    threading.settrace(f)
    threading.setprofile(f)
    print(threading.gettrace() is f, threading.getprofile() is f)
    threading.settrace(None)
    threading.setprofile(None)
    print(threading.stack_size(), threading.stack_size(65536), threading.stack_size(), threading.stack_size(0))
    show(lambda: threading.stack_size(100))


def hooks():
    print(threading.excepthook is threading.__excepthook__, threading.excepthook is _thread._excepthook)
    args = threading.ExceptHookArgs([ValueError, ValueError('boom'), None, threading.current_thread()])
    print(type(args).__name__, args.exc_type.__name__, args.thread is threading.current_thread(), len(args))
    old = sys.stderr
    sys.stderr = io.StringIO()
    try:
        threading.excepthook(args)
        threading.excepthook(threading.ExceptHookArgs([SystemExit, SystemExit(), None, None]))
        out = sys.stderr.getvalue()
    finally:
        sys.stderr = old
    print(out.splitlines())
    show(lambda: threading.excepthook(1))


def queues():
    q = queue.Queue()
    q.put(1)
    q.put_nowait(2)
    print(q.qsize(), q.get(), q.get_nowait(), q.empty())
    show(lambda: q.get(timeout=0.02))
    show(q.get_nowait)
    q.task_done()
    q.task_done()
    show(q.task_done)
    q.join()
    q2 = queue.Queue(1)
    q2.put(1)
    show(lambda: q2.put(2, timeout=0.02))
    lq, pq = queue.LifoQueue(), queue.PriorityQueue()
    for i in (3, 1, 2):
        lq.put(i)
        pq.put(i)
    print([lq.get() for _ in range(3)], [pq.get() for _ in range(3)])
    q.shutdown()
    show(lambda: q.put(1))


def simple_queues():
    sq = queue.SimpleQueue()
    sq.put(1)
    sq.put_nowait(2)
    print(sq.qsize(), sq.get(), sq.get_nowait(), sq.empty())
    show(lambda: sq.get(timeout=0.02))
    show(lambda: sq.get(block=False))
    show(lambda: sq.get(timeout=-1))


def futures():
    f = cf.Future()
    print(f.done(), f.running(), f.cancelled())
    f.add_done_callback(lambda x: print('done callback', x.result()))
    f.set_result(5)
    print(f.result(), f.done(), repr(f).split(' state=')[1])
    g = cf.Future()
    print(g.cancel(), g.cancelled(), g.set_running_or_notify_cancel())
    show(g.result)
    h = cf.Future()
    show(lambda: h.result(timeout=0.02))
    show(lambda: h.exception(timeout=0.01))
    done, not_done = cf.wait([f, h], timeout=0.02)
    print(len(done), len(not_done))
    show(lambda: list(cf.as_completed([h], timeout=0.02)))
    h.set_exception(ValueError('x'))
    print(type(h.exception()).__name__, [x is f for x in cf.as_completed([f])])
    try:
        h.set_result(1)
    except cf.InvalidStateError as e:
        print(type(e).__name__, str(e).split(':')[0])


def executors():
    ex = cf.ThreadPoolExecutor(max_workers=2, thread_name_prefix='p')
    print(ex._max_workers, ex._thread_name_prefix, cf._base.LOGGER.name)
    with ex:
        pass
    show(lambda: ex.submit(print))
    show(lambda: cf.ThreadPoolExecutor(max_workers=0))
    # (InterpreterPoolExecutor: CPython's, over _interpreters, which NeonPy has not)
    print(sorted(n for n in cf.__all__ if n != 'InterpreterPoolExecutor'))


def thread_module():
    print(sorted(n for n in dir(_thread) if not n.startswith('__')))
    print(_thread.daemon_threads_allowed(), _thread._is_main_interpreter(), _thread.TIMEOUT_MAX, _thread._NAME_MAXLEN)
    h = _thread._ThreadHandle()
    print(h.ident, h.is_done(), repr(h))
    show(h.join)
    show(h._set_done)
    show(lambda: _thread.start_joinable_thread(print, handle=1))
    show(lambda: _thread.start_new_thread(1, ()))
    show(lambda: _thread.start_new_thread(print, []))
    show(lambda: _thread.start_new_thread(print, (), 1))
    show(lambda: _thread.start_new_thread(print))
    show(lambda: _thread.start_joinable_thread(1))
    show(lambda: _thread.start_joinable_thread())
    m = _thread._make_thread_handle(threading.get_ident())
    show(m.join)
    print(m.is_done(), m._set_done(), m.is_done(), m.join())
    show(lambda: _thread._make_thread_handle('x'))
    show(lambda: _thread._make_thread_handle(-1))


def thread_module_more():
    show(lambda: _thread.lock(1))
    show(lambda: _thread.lock(a=1))
    show(lambda: type('X', (_thread.lock,), {}))
    show(lambda: _thread.allocate_lock().release(1))
    show(lambda: _thread.allocate_lock().acquire(1, 2, 3))
    show(lambda: _thread.allocate_lock().acquire(blockin=1))
    show(lambda: _thread.RLock()._acquire_restore(1))
    show(lambda: _thread.RLock()._acquire_restore((1,)))
    show(lambda: _thread.get_ident(1))
    show(lambda: _thread._excepthook(1))
    show(lambda: _thread._ExceptHookArgs([1, 2, 3]))
    show(lambda: _thread.interrupt_main(0))
    show(lambda: _thread.interrupt_main(15))
    show(_thread.interrupt_main)
    show(_thread.exit)
    show(lambda: _thread.set_name(1))
    show(lambda: _thread.set_name('0123456789abcdefgh'))
    show(_thread._get_name)
    show(lambda: _thread.stack_size(1, 2))


def exits():
    def f(*a, **k):
        print('exit', a, k)
    print(atexit.register(f, 1, k=2) is f, atexit.register(f, 2) is f)
    atexit.register(print, 'unregistered')
    atexit.unregister(print)
    show(lambda: atexit.register(1))
    show(atexit.register)
    atexit._run_exitfuncs()


def shutdown():
    calls = []
    threading._register_atexit(calls.append, 1)
    threading._shutdown()
    print(calls, threading.main_thread().is_alive(), threading._SHUTTING_DOWN)
    show(lambda: threading._register_atexit(print))
    show(lambda: cf.ThreadPoolExecutor().submit(print))


identity()
locks()
rlocks()
conditions()
events()
semaphores()
barriers()
thread_locals()
threads()
hooks()
queues()
simple_queues()
futures()
executors()
thread_module()
thread_module_more()
exits()
shutdown()
