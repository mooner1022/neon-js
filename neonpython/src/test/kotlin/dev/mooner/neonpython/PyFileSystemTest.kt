package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a context's os sees of the host (docs/PYTHON_PLATFORM.md §12.2): nothing by default, the bundled library
 * read-only whatever the file system is, a host directory without a way out of it, the context's own output on fds
 * 1 and 2.
 */
class PyFileSystemTest {
    private fun run(src: String, fs: PyFileSystem = PyFileSystem.NONE, environ: Map<String, String> = emptyMap()): String {
        val out = StringBuilder()
        PyContext(out, dev.mooner.neonjs.runtime.VmConfig(), null, out, fs, environ).exec(src.trimIndent() + "\n")
        return out.toString()
    }

    /** Each line of [src] printed as `label -> value` or `label !! Error message`. */
    private fun probe(src: String, fs: PyFileSystem = PyFileSystem.NONE): String = run(
        "def t(label, f):\n    try:\n        print(label, '->', f())\n    except Exception as e:\n        print(label, '!!', type(e).__name__, e)\n" +
            src.trimIndent(), fs)

    @Test
    fun nothingIsSeenByDefault() {
        val out = probe("""
            import os
            t('cwd', os.getcwd)
            t('listdir', lambda: os.listdir('/'))
            t('stat', lambda: os.stat('/etc/passwd'))
            t('open', lambda: os.open('x', os.O_WRONLY | os.O_CREAT))
            t('exists', lambda: (os.path.exists('/etc'), os.path.isfile('x')))
            t('environ', lambda: dict(os.environ))
        """)
        assertEquals("""
            cwd -> /
            listdir !! PermissionError [Errno 1] Operation not permitted: '/'
            stat !! PermissionError [Errno 1] Operation not permitted: '/etc/passwd'
            open !! PermissionError [Errno 1] Operation not permitted: 'x'
            exists -> (False, False)
            environ -> {}
        """.trimIndent() + "\n", out)
    }

    @Test
    fun theLibraryIsReadOnlyWhateverTheFileSystem() {
        for (fs in listOf(PyFileSystem.NONE, PyFileSystem.memory())) {
            val out = probe("""
                import os, stat
                lib = os.path.dirname(os.__file__)
                t('lib', lambda: (lib, 'os.py' in os.listdir(lib), 're' in os.listdir(lib), os.path.isdir(lib + '/re')))
                t('parents', lambda: ('lib' in os.listdir('/usr'), os.path.isdir('/usr/lib')))
                fd = os.open(os.__file__, os.O_RDONLY)
                t('read', lambda: (os.read(fd, 1), os.fstat(fd).st_size == os.stat(os.__file__).st_size, stat.S_ISREG(os.fstat(fd).st_mode)))
                os.close(fd)
                t('write', lambda: os.open(os.__file__, os.O_WRONLY))
                t('create', lambda: os.open(lib + '/new.py', os.O_WRONLY | os.O_CREAT))
                t('unlink', lambda: os.unlink(os.__file__))
                t('missing', lambda: os.stat(lib + '/nope.py'))
            """, fs)
            assertEquals("""
                lib -> ('/usr/lib/python3.14', True, True, True)
                parents -> (True, True)
                read -> (b'r', True, True)
                write !! OSError [Errno 30] Read-only file system: '/usr/lib/python3.14/os.py'
                create !! OSError [Errno 30] Read-only file system: '/usr/lib/python3.14/new.py'
                unlink !! OSError [Errno 30] Read-only file system: '/usr/lib/python3.14/os.py'
                missing !! FileNotFoundError [Errno 2] No such file or directory: '/usr/lib/python3.14/nope.py'
            """.trimIndent() + "\n", out, "$fs")
        }
    }

    @Test
    fun aHostDirectoryHasNoWayOut(@TempDir tmp: Path) {
        val root = Files.createDirectory(tmp.resolve("root"))
        Files.write(tmp.resolve("secret.txt"), "secret".toByteArray())
        Files.write(root.resolve("inside.txt"), "inside".toByteArray())
        val linked = try {
            Files.createSymbolicLink(root.resolve("out"), tmp.resolve("secret.txt"))
            true
        } catch (e: Exception) {
            false // (no symbolic links on this host: Windows without the privilege)
        }
        val out = probe("""
            import os
            t('dotdot', lambda: sorted(n for n in os.listdir('../../..') if n != 'out'))
            t('abs', lambda: sorted(n for n in os.listdir('/') if n != 'out'))
            fd = os.open('/../../inside.txt', os.O_RDONLY)
            t('read', lambda: os.read(fd, 100))
            os.close(fd)
            t('secret', lambda: os.stat('../secret.txt'))
            fd = os.open('made.txt', os.O_WRONLY | os.O_CREAT)
            os.write(fd, b'made')
            os.close(fd)
            os.mkdir('d')
            t('made', lambda: sorted(n for n in os.listdir('.') if n != 'out'))
        """, PyFileSystem.directory(root))
        assertEquals("""
            dotdot -> ['inside.txt']
            abs -> ['inside.txt']
            read -> b'inside'
            secret !! FileNotFoundError [Errno 2] No such file or directory: '../secret.txt'
            made -> ['d', 'inside.txt', 'made.txt']
        """.trimIndent() + "\n", out)
        assertEquals("made", String(Files.readAllBytes(root.resolve("made.txt"))))
        assertTrue(Files.isDirectory(root.resolve("d")))
        if (linked) {
            val esc = probe("""
                import os
                t('stat', lambda: os.stat('out'))
                t('open', lambda: os.open('out', os.O_RDONLY))
                t('lstat', lambda: os.path.islink('out'))
            """, PyFileSystem.directory(root))
            assertEquals("""
                stat !! PermissionError [Errno 13] Permission denied: 'out'
                open !! PermissionError [Errno 13] Permission denied: 'out'
                lstat -> True
            """.trimIndent() + "\n", esc)
        }
    }

    @Test
    fun aDirectoryLinkOutIsRefused(@TempDir tmp: Path) {
        val root = Files.createDirectory(tmp.resolve("root"))
        val outside = Files.createDirectory(tmp.resolve("outside"))
        Files.write(outside.resolve("f.txt"), "secret".toByteArray())
        val link = root.resolve("door")
        // (a symbolic link where the host allows one, else a Windows junction, which needs no privilege)
        val made = try {
            Files.createSymbolicLink(link, outside)
            true
        } catch (e: Exception) {
            System.getProperty("os.name").lowercase().startsWith("windows") &&
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).start().waitFor() == 0
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(made, "no directory links on this host")
        val out = probe("""
            import os
            t('listdir', lambda: os.listdir('door'))
            t('stat', lambda: os.stat('door/f.txt'))
            t('open', lambda: os.open('door/f.txt', os.O_RDONLY))
            t('create', lambda: os.open('door/new.txt', os.O_WRONLY | os.O_CREAT))
        """, PyFileSystem.directory(root))
        assertEquals("""
            listdir !! PermissionError [Errno 13] Permission denied: 'door'
            stat !! PermissionError [Errno 13] Permission denied: 'door/f.txt'
            open !! PermissionError [Errno 13] Permission denied: 'door/f.txt'
            create !! PermissionError [Errno 13] Permission denied: 'door/new.txt'
        """.trimIndent() + "\n", out)
        assertFalse(Files.exists(outside.resolve("new.txt")))
    }

    @Test
    fun aReadOnlyDirectoryRefusesChanges(@TempDir tmp: Path) {
        Files.write(tmp.resolve("a.txt"), "a".toByteArray())
        val out = probe("""
            import os
            t('read', lambda: os.read(os.open('a.txt', os.O_RDONLY), 9))
            t('write', lambda: os.open('a.txt', os.O_WRONLY))
            t('mkdir', lambda: os.mkdir('d'))
            t('unlink', lambda: os.unlink('a.txt'))
        """, PyFileSystem.directory(tmp, readOnly = true))
        assertEquals("""
            read -> b'a'
            write !! OSError [Errno 30] Read-only file system: 'a.txt'
            mkdir !! OSError [Errno 30] Read-only file system: 'd'
            unlink !! OSError [Errno 30] Read-only file system: 'a.txt'
        """.trimIndent() + "\n", out)
        assertFalse(Files.exists(tmp.resolve("d")))
    }

    @Test
    fun standardDescriptorsAreTheContextsOutput() {
        val out = StringBuilder()
        val err = StringBuilder()
        // (é's two bytes written apart: one character)
        PyContext(out, dev.mooner.neonjs.runtime.VmConfig(), null, err).exec(
            "import os\nos.write(1, b'out \\xc3')\nos.write(1, b'\\xa9\\n')\nos.write(2, b'err\\n')\nprint(os.read(0, 10), os.isatty(1))\n")
        assertEquals("out é\nb'' False\n", out.toString())
        assertEquals("err\n", err.toString())
    }
}
