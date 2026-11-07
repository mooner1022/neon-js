package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * compile() of AST objects where the program can't hold it (programs/ast_compile.py has CPython's behaviour): trees
 * CPython's compiler crashes on (it reads a None where its validator let one through), which NeonPy refuses with
 * the validator's kind of error; every bundled library file compiled from its tree to the code its source compiles
 * to, and copied by PyCF_ONLY_AST as it is.
 */
class PyAstCompileTest {
    private fun run(src: String): String {
        val out = StringBuilder()
        PyContext(out).exec(src.trimIndent() + "\n")
        return out.toString()
    }

    private fun lines(vararg l: String) = l.joinToString("\n", postfix = "\n")

    @Test
    fun treesCPythonCrashesOnAreRefused() {
        val out = run("""
            import ast
            def t(label, tree, mode='exec', flags=0):
                try:
                    compile(ast.fix_missing_locations(tree), '<t>', mode, flags)
                    print(label, 'ok')
                except Exception as e:
                    print(label, type(e).__name__, e)
            def mod(*body):
                return ast.Module(list(body), [])
            x = ast.Name('x', ast.Load())
            case = lambda p: ast.match_case(p, None, [ast.Pass()])
            t('global', mod(ast.Global(['x', None])))
            t('nonlocal', mod(ast.FunctionDef('f', ast.arguments(), [ast.Nonlocal([None])])))
            t('kwd_attrs', mod(ast.Match(x, [case(ast.MatchClass(x, [], [None], [ast.MatchAs()]))])))
            t('patterns', mod(ast.Match(x, [case(ast.MatchSequence([None]))])))
            t('handlers', mod(ast.Try([ast.Pass()], [None], [], [])))
            t('type_params', mod(ast.TypeAlias(ast.Name('T', ast.Store()), [None], x)))
            t('func_type', ast.Expression(x), 'func_type', ast.PyCF_ONLY_AST)
        """)
        assertEquals(lines(
            "global ValueError None disallowed in identifier list",
            "nonlocal ValueError None disallowed in identifier list",
            "kwd_attrs ValueError None disallowed in identifier list",
            "patterns ValueError None disallowed in pattern list",
            "handlers ValueError None disallowed in excepthandler list",
            "type_params ValueError None disallowed in type_param list",
            "func_type ValueError compile() mode must be 'exec', 'eval' or 'single'",
        ), out)
    }

    @Test
    fun everyLibraryFileCompilesFromItsTreeAsFromItsSource() {
        val out = run("""
            import ast, os
            def same(a, b, path):
                for attr in ('co_name', 'co_qualname', 'co_firstlineno', 'co_flags', 'co_argcount', 'co_posonlyargcount',
                             'co_kwonlyargcount', 'co_varnames', 'co_freevars', 'co_cellvars', 'co_names', 'co_code'):
                    if getattr(a, attr) != getattr(b, attr):
                        return path + ' ' + attr
                if list(a.co_lines()) != list(b.co_lines()):
                    return path + ' co_lines'
                if len(a.co_consts) != len(b.co_consts):
                    return path + ' co_consts'
                for x, y in zip(a.co_consts, b.co_consts):
                    if hasattr(x, 'co_code'):
                        r = same(x, y, path + '/' + x.co_name)
                        if r:
                            return r
                    elif type(x) is not type(y) or x != y and x == x:
                        return path + ' constant ' + repr(x)
            def check(path):
                src = open(path, encoding='utf-8').read()
                tree = ast.parse(src, path)
                r = same(compile(src, path, 'exec'), compile(tree, path, 'exec'), path)
                if r:
                    return r
                copy = compile(tree, path, 'exec', ast.PyCF_ONLY_AST)
                if copy is tree or ast.dump(copy, include_attributes=True) != ast.dump(tree, include_attributes=True):
                    return path + ' ONLY_AST'
            n = 0
            for root, dirs, files in os.walk(os.path.dirname(ast.__file__)):
                for f in sorted(files):
                    if f.endswith('.py'):
                        n += 1
                        r = check(root + '/' + f)
                        if r:
                            print(r)
            print(n > 250)
        """)
        assertTrue(out == "True\n", out)
    }
}
