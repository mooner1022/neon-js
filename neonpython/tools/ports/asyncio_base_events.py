# asyncio/base_events: CPython's, but for shutdown_default_executor, which starts a thread to join the default
# executor's. NeonPy has one thread (docs/PYTHON_PLATFORM.md §12.2): a run_in_executor() made the executor and could
# start none of its workers, so it is shut down in the loop's thread, with nothing to wait for (the lines kept).
NOTE = "the default executor shut down in the loop's thread (one thread: it has no workers to join)"

REPLACEMENTS = [
    ('''        thread = threading.Thread(target=self._do_shutdown, args=(future,))
        thread.start()
''',
     '''        thread = None  # NeonPy: one thread, the executor's workers none (§12.2)
        self._do_shutdown(future)
'''),
    ('''        else:
            thread.join()

    def _do_shutdown(self, future):''',
     '''        else:
            pass

    def _do_shutdown(self, future):'''),
]
