# The linter (`neonjs-lint`)

`neonjs-lint` checks JavaScript as NeonJS reads it. An editor or a tool calls it, for example StarLight's script editor
as one types. It reports every syntax error and still checks the code around and after them. Its rules report mistakes
that are valid code. It has no style rules.

Each syntax error is reported where the engine finds it, with the engine's own message. The parse records first the
error the engine throws; the linter lists all of them by offset. Where the parser's recovery mode can tell what the
mistake is, it says so instead, in its own words (an expression missing, a `)` left out, `elseif` for `else if`). The
list of those is in "Diagnoses of the recovery mode's own" below.

```
source ──▶ Parser.parseTolerant ──▶ Program (an ErrorNode for each statement it could not read) + syntax errors
               (the engine's parser,                    │
                in its recovery mode)                   ▼
                                                 ScopeAnalyzer (the compiler's): LocalRef / GlobalRef / DynamicRef
                                                        │
                                                        ▼
                                          rules over the tree and the scopes ──▶ diagnostics
```

## Using it

```kotlin
val result = NeonLint.lint(source, LintOptions(
    sourceType = SourceType.MODULE,                      // or SCRIPT: as the engine runs the code
    globals = EngineGlobals.of(webGlobals = true, net = true) + Globals.of(listOf("Chat", "Log")),
    rules = mapOf("var-before-define" to RuleSetting.OFF, "debugger" to RuleSetting.ERROR),
    maxDiagnostics = 100,
    isCancelled = { inputChanged },
))
for (d in result.diagnostics) println("${d.start}-${d.end} ${d.severity} ${d.code}: ${d.message} ${d.args}")
```

- **No engine needed.** `lint` needs no `NeonEngine` or `NeonContext`. It keeps no state between calls, may be called from
  any thread, and never throws.
- **Deep nesting.** Input nested too deeply to read is reported as one `too-deep` diagnostic, and the engine could not
  run it either. Run lint on a thread with a large stack to read deeper input.
- **Internal failures.** An internal failure is one `internal-error` diagnostic. A host rule that fails loses only its
  own findings.
- **Offsets.** `start` and `end` are UTF-16 offsets into the source; `end` is exclusive.
- **Translating messages.** `message` is English. A host translates by `code`, filling in `args` in the order
  `NeonLint.rules` names them.
- **Globals.** `globals` lists the names the code may use without declaring them. `EngineGlobals` covers the engine's own,
  for each builder option and extension. The host adds its own names: an app's API objects, StarLight's `toast`. Without
  `globals`, `undefined-name` and `readonly-global-assign` are off.
- **The diagnostic limit.** Syntax errors are kept first, then the rules' findings in source order. `truncated` says
  whether some were left out.
- **Cancelling.** `isCancelled` is asked at each statement and as the rules walk the tree. A cancelled check returns
  `cancelled = true`.
- **Host rules.** `extraRules` holds a host's own rules, such as an event function that is not exported. They read the
  tree and the scope analysis. Those are marked `@LintTreeApi`: they are the engine's internals and not a stable API.

## Diagnostics

Errors are what fails when the code runs (a TypeError or ReferenceError), or what does not parse. Warnings are
mistakes that run.

| Code | Severity | Args | What |
|---|---|---|---|
| `const-assign` | error | name | assigning a const, an import, a `using`, or a class's own name inside it |
| `use-before-define` | error | name | a let, const or class read before its declaration in the same function (its TDZ) |
| `this-before-super` | error | kind | `this` or `super.x` in a derived class's constructor before `super()` |
| `missing-super-call` | error | | a derived class's constructor that never calls `super()`, returns no object and does not always throw |
| `undefined-name` | warning | name | a name declared nowhere and not among `globals`; not `typeof x`, nor inside `with` |
| `readonly-global-assign` | warning | name | assigning a global given as read-only |
| `var-before-define` | warning | name | a `var` used before its declaration in the same function: it is `undefined` there |
| `class-assign` | warning | name | assigning a class declaration's name |
| `func-assign` | warning | name | assigning a function declaration's name, or a function expression's own name |
| `cond-assign` | warning | | an assignment as a condition, not in parentheses (a conditional's test needs two pairs) |
| `unreachable` | warning | | statements after `return`, `throw`, `break` or `continue` (not function declarations, nor `var` without initializers) |
| `fallthrough` | warning | keyword | a non-empty case that goes on into the next, with no comment matching `falls?\s?through` |
| `duplicate-key` | warning | name | an object literal's key twice (a getter and a setter are a pair; `__proto__:` is not a key) |
| `duplicate-param` | warning | name | a parameter name twice (sloppy functions; elsewhere it is a syntax error) |
| `duplicate-class-member` | warning | name | a class member's name twice (static and instance apart) |
| `duplicate-case` | warning | | a case test twice (compared token by token) |
| `duplicate-else-if` | warning | | an `else if` whose condition an earlier one in the chain covers |
| `self-assign` | warning | name | `a = a`, `a.b = a.b`, `[a, b] = [a, b]` (also `&&=`, `\|\|=`, `??=`) |
| `nan-comparison` | warning | | comparing with `NaN` or `Number.NaN`, `switch (NaN)`, `case NaN` |
| `invalid-typeof` | warning | value | `typeof x` compared with a string it never gives, or with `undefined` |
| `unsafe-negation` | warning | operator | `!a in b`, `!a instanceof b` |
| `debugger` | warning | | a `debugger` statement |
| `syntax/…` | error | (see below) | the parser's errors |
| `too-deep` | error | | the source is nested too deeply to read |
| `internal-error` | warning | detail | the linter failed (a bug) |

### Syntax error codes

Every message the parser and the lexer give has a code (`SyntaxCodes`). The message is the engine's own:

| Code | Args |
|---|---|
| `syntax/unexpected-end` | none |
| `syntax/unexpected-token` | token |
| `syntax/missing-token` | expected, found |
| `syntax/unterminated-string`, `syntax/unterminated-template`, `syntax/unterminated-comment`, `syntax/unterminated-regexp` | none |
| `syntax/invalid-regexp` | pattern, flags, detail |
| `syntax/redeclared`, `syntax/private-name-undeclared`, `syntax/duplicate-export`, `syntax/undefined-export` | name |
| everything else | none |

The codes in the last row are:
- `invalid-regexp-flags`, `invalid-character`, `invalid-escape`, `invalid-number`, `strict-octal-escape`;
- `duplicate-parameter`, `reserved-word`, `invalid-assignment-target`, `missing-initializer`;
- `illegal-return`, `illegal-break`, `import-export-placement`, `await-yield`, `super-new-target`;
- `class`, `strict-mode`, `declaration-placement`, `statement`, `module`.

A message none of them matches is `syntax/error`, with the message as its argument. In Test262 that is one message,
"Cannot use 'arguments' in class static initialization block".

When the source ends inside a block, `syntax/unexpected-end` has a related position: the innermost `{` the source
leaves open.

### Diagnoses of the recovery mode's own

For some mistakes, the recovery mode says what the mistake is rather than what the engine found. Their code, message
and span replace the engine's; the engine's message and position stay on the parse's record (`ParseProblem`). Some
also let the parse go on, or have the source mended, with the same length, and read again.

| Code | Args | Instead of the engine's | Where it points | The parse |
|---|---|---|---|---|
| `syntax/missing-expression` | none | `Unexpected token` at the token | the same token | goes on with an `ErrorNode` for the expression |
| `syntax/missing-paren` | none | `Expected ',' / ')' but found …` | the same token; related: the `(` | goes on as if the `)` were there, or passes over the statement |
| `syntax/missing-bracket` | none | `Expected ',' / ']' but found …` | the same token; related: the `[` | the same |
| `syntax/missing-brace` | none | `Expected ',' but found …` | the same token; related: the `{` | passes over the statement |
| `syntax/missing-comma` | items: properties, elements or items | `Expected ',' but found …` | the same token; related: the list's opener | goes on as if the `,` were there |
| `syntax/missing-colon` | none | `Expected ':' but found …` | the same token | goes on as if the `:` were there |
| `syntax/else-without-if` | none | `Unexpected reserved word 'else'` | the same `else` | reads the statement after the `else` |
| `syntax/elseif` | none | `Unexpected token '{'` | the word `elseif`, not the `{` after it | reads the block; the `else` after it is not told again |
| `syntax/missing-condition` | keyword | `Expected '(' but found '{'` | the keyword, not the `{` | `if {` is read as `   {` |
| `syntax/condition-parens` | keyword | `Expected '(' but found …` | the condition | `if a {` is read as `if(a){` |
| `syntax/catch-parens` | none | `Expected '{' but found …` | the parameter | `catch e {` is read as `catch(e){` |
| `syntax/unclosed-parameters` | none | the error where the parameter list failed | the body's `{`; related: the list's `(` | `function f(a, {` is read as `function f(a) {` |

So every syntax diagnostic is at the position the engine reports, except those of `syntax/elseif`,
`syntax/missing-condition` and `syntax/unclosed-parameters`. Only an `if`'s missing condition is mended: a loop's body
read as a block would make its `break` an error.

### Where the rules differ from ESLint

The rules follow ESLint's rules of the same purpose (named in the code's description), with these differences:

| Rule | Difference from ESLint |
|---|---|
| `use-before-define` | Reports only what is a runtime error: a let, const or class in its TDZ, read in the same function. In a destructuring pattern, a name is initialized once its element is bound: `let [a, b = a]` is fine, `let { x = x }` is not. |
| `var-before-define` | ESLint's `no-use-before-define` reports a `var` too, as an error (StarLight's setting). Here it is its own rule and a warning: the program runs. |
| `class-assign` | Assigning a class declaration's name is legal, so it is a warning. Inside the class the name is constant, so assigning it there is `const-assign`, an error. |
| `this-before-super` | Reports what is certain: `this` or `super.x` in the statements before the first one with `super()`, and in `super()`'s arguments. No code-path analysis, so `if (a) super(); this.x` is not reported. |
| `missing-super-call` | Counts a `super()` in an arrow function as a call. |
| `unreachable` | Follows statement lists, not code paths: an infinite loop does not make what follows unreachable. |
| `fallthrough` | Same simplification as `unreachable`. |
| `cond-assign` | Accepts the same parentheses as ESLint's `except-parens`. |
| `duplicate-else-if` | Uses ESLint's algorithm: `||` and `&&` operands, conditions compared by tokens. |

## The parser's recovery mode

`Parser.parseTolerant` (neonjs-core, marked `@UnstableParserApi`) is the engine's parser, `ParserImpl`, with one
subclass, `RecoveringParser`, which differs only where errors are found. The engine's own parse is unchanged, and three
checks confirm it:
- `ParseBaseline` finds the same error (offset and message) or the same tree for every Test262 file, in every mode.
- `tools/check.sh codegen` finds no generated class changed.
- `ParseBench` cannot tell the two apart.

### Where ParserImpl differs

`ParserImpl` is open. The subclass overrides methods of these kinds:
- **Called once per statement of a list:** `parseListedStatement`. It is called for the top level, blocks and switch
  cases.
- **Called once per declared name:** `declareName`, which the recovery mode logs so a failed statement's names can be
  undone.
- **Called once per class body:** `parseClassMembers`.
- **Called once per function or method:** `parseParams`, its parameter list.
- **Called only on an error's path:**
  - `raise`, an early error;
  - `fail`, an error the parse cannot go on from;
  - `expectFailed` and `semicolonFailed`;
  - `expressionExpected`, where no expression begins and one must;
  - `blockEndsEarly` and `casesEndEarly`;
  - `readFirstToken`, `declaredAtTop` and `declarePrivate`.
- **ScopeAnalyzer:** declares an `ErrorNode`'s names. A declaration of the same name in the statement list wins. It
  visits nothing inside an `ErrorNode`.

### What happens at each kind of error

- **An early error** (a rule broken where the syntax is intact, such as a name declared twice) is recorded. The parse
  goes on from it, as with the 154 `raise` sites.
- **A token missing** where the next token can follow it is recorded, and the parse goes on as if it were there. This
  covers:
  - `)` and `]`;
  - a list's `,`, except across a line break, where only a property's `key:` counts;
  - a conditional's `:`;
  - a statement's `;`.
- **Any other syntax error** ends the statement, or the class member, being read:
  - What it changed in the parser's state is undone: scopes, the names it declared (logged by `declareName`), labels,
    private names, strictness, `yield` and `await` positions, the switch depth, and the export books.
  - The names it had declared are kept in the `ErrorNode` that stands for it. For a failed `var`, `let` or `const`,
    these include its pattern's names, read from the tokens. So a later declaration of one of them is no redeclaration,
    and its uses are not undeclared.
  - The tokens are passed over to where the next statement can begin. The pass rereads the statement from its start,
    counting brackets. Regular expressions (decided from the token before) and templates (with their substitutions)
    count as one token. It stops at:
    - a `;` or a block end of its own;
    - a line that begins with a statement keyword;
    - the `}` of the enclosing block;
    - a switch's next case.
- **Echoes are not recorded.** After one syntax error in a statement, the next ones in it are its echoes. Early errors
  still are.
- **The source ending in blocks** is one error. The blocks keep what was read.
- **The recovery mode's own diagnoses** ("Diagnoses of the recovery mode's own" above) come from the errors the engine
  finds, and from a statement beginning with `else`, which nothing valid does. Some mend the source the same way as
  the lexer's errors below.
- **A token the lexer cannot read** is repaired in a copy of the source, then the source is read again. The replacement
  has the same length, so every offset is still the source's:
  - an unterminated string becomes `""` and spaces;
  - a template or comment left open is closed at the end of the source;
  - a malformed number becomes `0`;
  - a bad escape's `\` becomes `_`;
  - a stray character becomes a space.

  This keeps the statement around the token, and the statement before it, which was still reading its last token when
  the lexer failed. At most 20 repairs are made.
- **Limits and depth.** A limit of errors and a cancel stop the parse at the next statement. Nesting too deep for the
  thread's stack throws `StackOverflowError`, as the engine does.

## Measurements

Measured on 2026-11-12/13, HotSpot (Temurin 25), 8 cores, nothing else running.

### Engine parse time (`ParseBench`)

`ParseBench` parses Test262's 48,937 accepted programs (81 million characters). Each figure is the median of rounds
5–14, against the BASE build 545b7ef0.

| Build | Engine-only JVM | BASE, same run |
|---|---|---|
| P1a | 911–918.5 ms | 913.5–930 ms |
| P1b | 914–949 ms | 921–952 ms |
| P2, the negative tests also read between rounds | 913–942.5 ms | 924–942 ms |
| P4's hooks, the same | 929.5–954 ms | 933–996 ms |
| P4 done, the same | 930–943 ms | 920–953 ms |

In one JVM, the engine's warm parse can be slower after the recovery mode has read the 4,660 negative tests, and stays
so. `ParseBench --pollute` times 10 rounds of the engine, lets a mode read a set of programs 3 times, and times 10
rounds more. The change in the median, over every run made:

| Build | The recovery mode reads the negative tests | The control: the engine reads them | The recovery mode reads as many valid programs |
|---|---|---|---|
| P1, P2 | 6 runs: −1.5 to +4.0%, median +2.5 | 6 runs: −3.1 to +1.1%, median −0.5 | 3 runs: −2.2 to +1.0%, median −1.8 |
| P4's hooks | 5 runs: +1.2 to +11.0%, median +2.2 | 5 runs: +0.1 to +2.2%, median +0.7 | |
| P4 done | 5 runs: −0.7 to +4.9%, median +1.4 | 5 runs: +0.2 to +6.7%, median +1.0 | 5 runs: −0.6 to +2.6%, median +0.4 |

The medians are 0.4 to 3.0 points above the control's: up to 2–4% beyond the noise. One run tells little. The +11.0%
is one run (903.5 ms before, 1,002.5 after), and the control itself gave +6.7% and +4.5% in the last set.

The recovery mode on valid programs has no such effect, though it goes through the same hooks. The cost comes from
sharing the parser's code with a mode that runs past errors: HotSpot compiles the shared methods with those paths
taken. This cost was accepted. Not measured on ART.

### Recovery quality

- **Test262** (`TolerantProbeTest`): 102,956 parses.
  - The 8,659 the engine rejects are all read to the end, and each records the engine's error first.
  - The 94,297 it accepts record none.
  - Scope analysis takes every tree.
- **One token deleted, replaced or inserted** (`MutationTest`): 18,280 mutants of 2,846 programs (Test262 1 in 20,
  Node.js's `lib`).

  | | One error recorded | Mean errors | A declared name lost after the mutation |
  |---|---|---|---|
  | Test262 | 79% | 1.38 | 4.3% |
  | Node.js `lib` | 69% | 1.90 | 10.2% |

  In the samples read, most of the lost names come from a `}` inserted or moved, which really does change which
  function the code is in.

  The recovery mode's own diagnoses (P4) leave these numbers as they were: 79.4% and 69.3% with one error, 4.4% and
  10.2% with a name lost. Placeholders for a missing expression change neither number: the mistake is still one error,
  and the declaration was kept before too. What they add is the rest of the statement, checked. Mending a parameter
  list left open adds 0.2 points with one error, and 0.1 with a name lost in Test262. Those 4 mutants destroy the name
  themselves (`obj)` becomes `objlet`); without the mending, the function's body was never read, so nothing was lost.

### Linting

- **Test262** (`Test262LintTest`), every program in each mode:
  - No `internal-error`.
  - In each accepted program, the walk reaches every node.
  - In each rejected program, the engine's error is among the diagnostics: a syntax diagnostic at its offset or over
    it, or, in 8 programs, one of the diagnoses that point elsewhere.
  - Every code is one `NeonLint.rules` lists.
  - Error-severity diagnostics appear in 202 accepted programs; each file was read. Each is a test of that very
    runtime error (`*-use-before-initialization-*`, `*-put-const`, `assert.throws(TypeError, …)`,
    `super-must-be-called`), or a derived class the test never constructs. Reading them found one rule bug, now
    fixed: a constructor calling `super()` through a direct `eval`.
- **Node.js's `lib/events.js`** (1,256 lines, as a script, `LintBenchTest`), four runs. Each gives the median of 100
  lints after 500, and the median of 5 new JVMs' first lint:

  | | After 500 lints | First lint in a new JVM |
  |---|---|---|
  | As it is | 1.9 ms | 176–183 ms |
  | With a syntax error | 1.9–2.1 ms | 185–190 ms |

  After only 50 lints, the median was still 3.4–7.0 ms: HotSpot was still compiling. StarLight's ESLint-based check
  took about 110 ms for 1,350 lines.

## Tests and tools

| What | Where |
|---|---|
| `RecoveryTest`, `TolerantProbeTest`, `MutationTest` | neonjs-core, package `parser` |
| `LintTest` (each rule's examples, StarLight's editor's cases), `EngineGlobalsTest`, `Test262LintTest`, `LintBenchTest` | neonjs-lint |
| `ParseBaseline`, `ParseBench` | neonjs-test262 (docs/TESTING.md) |

- **Without the checkouts.** Tests that read Test262 or Node.js are skipped when `third_party/test262` (`TEST262`) or
  `third_party/node` is missing. In CI, `build` runs before Test262 is checked out, so they are skipped there.
- **Running them.** `tools/check.sh quick` runs neonjs-lint's tests too.
