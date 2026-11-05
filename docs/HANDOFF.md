# 작업 인수인계 (2026-11-05)

원격 서버의 세션에서 하던 작업을 다른 PC에서 그대로 이어가기 위한 문서다. 이 파일은 작업용이며, PR을 머지하기
전에 지운다. 세션 기억(memory)에 있던 사용자 선호와 결정도 아래에 옮겨 두었다.

## 1. 한눈에 보기

NeonJS를 여러 언어의 실행 플랫폼으로 만드는 중이다. 첫 대상은 Python 3.14이고, 제품명은 NeonPy다.

| 단계 | 상태 |
|---|---|
| T2(최적화 계층)를 언어 중립 코어와 JS dialect로 분리 (docs/IR_NEUTRALITY.md 1~8단계) | 완료. 전체 검사 통과, 푸시됨 (`ir-neutral-core`) |
| 9단계: 코어를 별도 모듈 `neon-vm`으로 분리 (IR_NEUTRALITY.md §10) | a~e는 `vm-core-split`, f는 Python 브랜치. quick과 정규화 codegen은 통과, **전체 검사 미완료** |
| NeonPy 첫 조각 (docs/PYTHON_PLATFORM.md §10.1) | 완료 (`python-first-slice`) |
| NeonPy 추측 최적화 (§10.2) | 완료. **마지막 커밋 a4eacd4는 quick 미실행** |
| NeonWasm (wasm 런타임) | 착수 전. 도구 선택이 사용자 결정 대기 (§6) |

사용자가 정한 순서는 "9단계(코어 모듈 분리) → NeonPy 추측 최적화 → NeonWasm"이다. 앞의 둘은 코드상 끝났고
검증·푸시·PR이 남았다.

## 2. 브랜치와 PR 구조

```
main
 └─ ir-neutral-core   (fc68a9c, 푸시됨)       → PR 1: 코어 중립화 1~8단계 + 다른 언어 라우팅
     └─ vm-core-split (43b3d3e, 미푸시)       → PR 3: 9단계 a~e, neon-vm 모듈
         └─ python-first-slice (a4eacd4~이 문서, 일부만 푸시됨)
                                                → PR 2: NeonPy (vm-core-split을 merge한 상태)
```

- **PR은 하나도 만들지 않았다.** 원격 세션에 GitHub 쓰기 도구가 없었다. `gh pr create`로 직접 만들면 된다
  (§5에 명령과 본문 요지).
- `python-first-slice`는 `vm-core-split`을 merge(189d89d)했다. 따라서 PR 2의 base는 `vm-core-split`이어야
  diff가 Python 쪽만 보인다. PR 3이 머지되면 `ir-neutral-core`로, 다시 `main`으로 retarget한다.
- 원격 세션 정책상 이미 푸시한 커밋의 amend/rebase/force-push가 거부됐다. 그래서 브랜치를 갱신할 때는 rebase
  대신 merge를 썼다. 로컬에서는 제약이 없지만, 공유된 브랜치는 merge로 유지하는 편이 안전하다.
- 원격 상태:
  - `origin/ir-neutral-core` = fc68a9c (최신)
  - `origin/python-first-slice` = 62f6ff9 (구버전; 로컬은 그 위에 merge·9f·추측 최적화·문서 커밋)
  - `origin/vm-core-split` 없음
  - 이 문서와 함께 두 브랜치를 모두 푸시했다.

## 3. 검증 상태 (정확히)

| 대상 | quick | codegen | full |
|---|---|---|---|
| `ir-neutral-core` f186d45 (8단계) | 통과 | — | **통과** (Test262 9모드 × 48,747, intl402 알려진 2건, WPT·Node 예상 외 실패 0) |
| `ir-neutral-core` fc68a9c (라우팅) | 통과 | f186d45 대비 동일 | 미실행 |
| `vm-core-split` 43b3d3e (9a~e) | 통과 | f186d45 대비 정규화 0 차이 | **중단됨**: interpreter·compiled·adaptive 3개 모드 통과(각 48,747) 후 이전 작업으로 중지. 다시 돌려야 함 |
| `python-first-slice` f453405 (추측 최적화) | 통과(1aa096a 기준, f453405 이전) / `:neonpython:test` 통과(f453405) | — | — |
| `python-first-slice` a4eacd4 (문서·OSR 테스트) | **미실행** | — | — |

- 정규화 codegen: 9d부터 `CompiledCode.run`이 `VmFrame`을 받으므로 바이트 동일은 불가능하다.
  - `tools/codegen-equiv.txt`의 규칙을 양쪽에 똑같이 적용해 비교한다: 이름 동치 4개, 진입 캐스트, 스택 맵 프레임 펼침, 기준 코드 이름의 해시.
  - 그대로 비교하면 t2 5,672 / stress 5,704 / adaptive 1,105개가 다르고, 정규화하면 0이다.
  - 컴파일되는 클래스 수는 같다(41,119 / 42,157 / 11,554).
- `bench/basic.js`: 9d 전후 컴파일 모드에서 동등하다(8회 중앙값). 적응 모드의 `closures`는 양쪽 모두
  24~63ms로 흔들린다(백그라운드 컴파일 시점 탓).
- 알려진 흔들림: `WebGlobalsTest.urlParsingIsLinearAndInterruptible`은 300ms 제한이 걸려 있어, 전체 단위
  테스트의 부하에서 한 번 실패했고 재실행에서 모두 통과했다.

## 4. 환경 준비 (새 PC)

1. **JDK 25:** Gradle toolchain이 25를 요구한다(`jvmToolchain(25)`, release 21). 자동 다운로드를 끈 환경이라면 다음처럼 넘긴다.
   ```
   export JAVA_HOME=<jdk25>
   export GRADLE_ARGS="-Porg.gradle.java.installations.paths=<jdk25> -Porg.gradle.java.installations.auto-download=false"
   ```
   `tools/check.sh`는 `GRADLE_ARGS`를 모든 gradle 호출에 붙인다. 사용자 홈의 gradle 설정 파일은 고치지 않는다(원격에서 거부됐던 작업).
2. **테스트 스위트**(CI와 같은 리비전, `.github/workflows/ci.yml` 참고):
   - **Test262:** `tools/check.sh`가 `third_party/test262`에 알아서 받는다(`TEST262_COMMIT`).
   - **WPT**(전체 검사용):
     ```
     git init -q third_party/wpt
     git -C third_party/wpt sparse-checkout set --no-cone --stdin < neonjs-test262/wpt-paths.txt
     git -C third_party/wpt fetch -q --depth 1 --filter=blob:none https://github.com/web-platform-tests/wpt.git d6713be63025929a1e8d8af45baaff0744b5e50d
     git -C third_party/wpt checkout -q FETCH_HEAD
     ```
   - **Node**(전체 검사용):
     ```
     git init -q third_party/node
     git -C third_party/node sparse-checkout set lib test/common test/parallel
     git -C third_party/node fetch -q --depth 1 --filter=blob:none https://github.com/nodejs/node.git 364593d0631def13e3043dd84f77ebca2c767ce5
     git -C third_party/node checkout -q FETCH_HEAD
     ```
3. **CPython 3.14** (`python3 -I`): NeonPy 테스트의 기대 출력(`neonpython/src/test/resources/programs/*.out`)과 오류 메시지를 이것으로 기록했다. 프로그램을 추가하면 `python3 -I x.py > x.out`으로 만든다.
4. **codegen 비교:** 기준 ref를 `build/check/base` worktree에 빌드하고 덤프를 `build/check/dumps/<sha>-*`에 캐시한다. 새 PC에서는 처음 한 번 오래 걸린다.

## 5. 바로 할 일 (순서대로)

1. **`vm-core-split` 전체 검사:**
   ```
   git switch vm-core-split
   tools/check.sh full
   ```
   통과하면 PR 3을 만든다.
2. **`python-first-slice` quick 검사:**
   ```
   git switch python-first-slice
   tools/check.sh quick
   ```
   quick은 이 브랜치에서 `:neon-vm:test :neonjs-core:test :neonpython:test`와 Test262 1/20 표본을 돌린다. a4eacd4의 OSR 테스트 변경(SPECULATING 모드 추가)을 처음 실행하는 것이다.
3. **NeonPy 추측 최적화 수치 재측정:** 원격 수치는 전체 검사와 겹쳐 신뢰할 수 없다. 기계가 한가할 때 잰다.
   - 대상: 적응 모드, `jitThreshold=2`, `backgroundJit=false`, tier2 끔/켬.
   - 프로그램: fib(27), `while` 3M 루프(`s += i & 7`), 20만 미만 소수 세기.
   - 측정 코드: 원격에서 쓴 임시 측정 클래스 내용은 아래 부록 A에 있다.
   - 결과는 PYTHON_PLATFORM.md §10.2에 기준 코드 표(§10.1)와 나란히 넣는다.
   - 참고(부하 상태 수치): loop 135→65ms, primes 467→216ms, fib 차이 미미.
4. **PR 생성** (base 주의).
   ```
   gh pr create -R mooner1022/neon-js --base main --head ir-neutral-core --title "IR-neutral core: the optimizing tier shared by languages (steps 1–8)"
   gh pr create -R mooner1022/neon-js --base ir-neutral-core --head vm-core-split --title "neon-vm: the execution core as a module of its own (step 9)"
   gh pr create -R mooner1022/neon-js --base vm-core-split --head python-first-slice --title "NeonPy: a first slice of Python on the neutral core"
   ```
   본문 요지:
   - **PR 1:** 1~8단계 요약과 검증. f186d45 전체 검사 통과, fc68a9c는 quick과 codegen 동일.
   - **PR 3:** IR_NEUTRALITY.md §10.1~10.2의 표와 결과, 정규화 codegen 수치, 전체 검사 결과.
   - **PR 2:** PYTHON_PLATFORM.md §10.1~10.2, 테스트 목록, 성능 표, 알려진 한계, §9의 결정 8~11(wasm 관련 계획).
   - 공통: 본문 끝에 `Assisted-by: Claude Opus 5.5`.
   - 머지 전에 이 문서(docs/HANDOFF.md)를 지운다.
5. **NeonWasm 착수**(§6). 도구 선택을 사용자에게 먼저 확인한다.

## 6. NeonWasm (다음 단계) — 정해진 것과 남은 결정

**정해진 것** (PYTHON_PLATFORM.md §9 결정 8~11, §5.2~5.6):
- C는 wasi-sdk(clang)로 미리 wasm 컴파일한다. Emscripten은 Pyodide 패키지 전용이다. 자체 C 컴파일러는 만들지 않는다.
- wasm → JVM/dex 번역은 직접 만든다(NeonWasm, 모듈 `neonwasm`).
  - 인터프리터와 spec 테스트로 먼저 시작하고, 이어서 중립 코어의 dialect로 간다.
  - f32를 위해 코어에 `Rep.FLOAT`를 추가해야 한다. 이 코어 변경은 코어 브랜치에 넣는다.
  - 거대 함수 분할이 주요 위험이다.
- 기기에서는 인터프리트하고, 뜨거워지면 번역해 캐시한다(`DexCodeDefiner`). 앱 빌드 시 번역도 선택지다. Play 정책 주의점은 §5.6에 있다.
- Pyodide 패키지는 호환 모드(Pyodide의 CPython을 NeonWasm 위에서 통째로)로 받는다(W4). NeonPy 안에 Pyodide ABI를 올리는 방식(2a)은 근거가 생길 때만 검토한다.

**첫 조각 범위 (권장):**
- 디코더, 검증기, 인터프리터.
- 대상 기능: MVP와 wasi-sdk가 기본으로 내는 확장들(multi-value, bulk-memory, sign-ext, nontrapping-fptoint, reference types).
- trap, import/export.
- spec 테스트 JSON 실행기와 known-failures 목록.
- 첫 조각에서 하지 않는 것: 번역, WASI, JS API.

**브랜치:** `neonwasm-first-slice`를 `vm-core-split`에서 딴다. Python 브랜치에서 따지 않는다.

**사용자 결정 대기 — spec 테스트(.wast) 변환 도구.** 원격에는 wabt와 node가 없었고 cargo만 있었다.
- (a) **권장:** `cargo install wasm-tools`를 개발 도구로 쓰고, `tools/wasm-spec.sh`가 고정 커밋의 spec testsuite를 `wasm-tools json-from-wast`로 `build/` 아래 JSON과 `.wasm`으로 변환한다. CI에도 설치가 필요하다.
- (b) 변환 결과를 저장소에 커밋한다.
- (c) Kotlin으로 WAT/WAST 파서를 직접 만든다. 크고, 런타임에는 필요 없다.

## 7. 구조 요약 (코드 위치)

- **`neon-vm/`** (패키지는 기존 `dev.mooner.neonjs.*` 유지; 코어 이름과 패키지는 나중에 따로 정한다)
  - `jit/`: `Jit`(정책, `DEOPT_STRESS`), `JitQueue`, `JitBackend`/`ClassBackend`, `CodeDefiner(s)`(probe 포함), `CompiledCode`(`run(VmFrame)`), `JitBailout`, `Emit.kt`(`Emit`, `JvmEmitter`, `JitClassWriter`, 공용 상수 `VM_*`·`L_*`), `VmRt`(`NO_OSR`, `DEOPTED`, `canTierUp`), `VmDeopt`(`write`, `tierUp`)
  - `jit/opt/`: 중립 IR, 패스, `GraphBuilder`, `IrCodegen`(훅: `frameClass`, `agentClass`, `entry`, `loadConstants`, `loadAgent`), `Dialect`(훅: `canCompile`, `compileBaselines`, `compilesAtLoops`, `interpret`, `leaveCompiled`, `dropInlined`, `constIdentity`), `KindSystem`(`osrCheck`), `T2`, `T2Code`, `GraphIdentity`
  - `compiler/CodeUnit`, `vm/VmFrame`, `runtime/VmAgent.kt`(`VmConfig`, `VmAgent`, `TerminationException` 계열)
  - 테스트: `ToyDialectTest`가 `ToyFrame : VmFrame`으로 JS 없이 돈다.
- **`neonjs-core/`**: JS 전부. `CodeBlock : CodeUnit`(init에서 `dialect = JsDialect`), `Frame : VmFrame`, `Agent : VmAgent`, `RuntimeConfig : VmConfig`, `JsDialect.kt`, `JvmEmitter.kt`(`JsEmit`, JS 상수, `Emit.rt` 확장), `JitRt`(별칭 `NO_OSR`, `DEOPTED`), `Deopt`(JS resume).
- **`neonpython/`** (`dev.mooner.neonpython`, 그룹 `dev.mooner.neonpython`; `neon-vm`에만 의존)
  - `PyLexer`, `PyParser`, `PyCompiler`(`PyCodeInfo`: 지역 이름, 줄 표, `profile`), `PyOp`
  - `PyInterpreter`(`PyContext`, `PyFrame : VmFrame`), `PyRt`, `PyBuiltins`, `PyValues`, `PyProfile`
  - `PyDialect`(`PyKinds`: BOOL/INT(long)/FLOAT(double); `PyOps`; `PyBuilder`(추측); `PyCodegen`)
- **도구**: `tools/check.sh quick|codegen REF|full`, `tools/codegen-equiv.txt`, `neonjs-test262`의 `ClassDiff`(`--equiv`).

## 8. 함정과 결정 (잊기 쉬운 것)

- **기준 코드 클래스 공유와 상수:**
  - 같은 그래프를 가진 블록들이 클래스 하나를 공유하고(`GraphIdentity`), 인덱스를 가진 상수는 인덱스로만 해시된다.
  - 그래서 생성 코드에 상수 값을 박아 넣으면 안 된다. 인덱스 상수는 언박싱할 때도 상수 배열에서 읽는다(`PyCodegen.pushConst`).
  - None/True/False는 인덱스 없는 값 상수다. 박힌 None 때문에 `None None None None None`이 출력됐던 버그가 있었다.
- NeonPy의 "값 없는 지역 변수"는 `null`이다(그래프 dead value도 null). JS의 `Undefined`를 쓰지 않는다.
- NeonPy 코드는 dialect의 클래스 로더에 정의된다(`T2.loaderOf`).
- `PyBuilder`는 deopt stress 출구를 만들지 않는다. Python의 탈출 경로는 실제 오버플로·타입 변화로만 테스트된다.
- Python 3.14의 오류 메시지:
  - `//`와 `%`도 "division by zero"
  - `0 ** -1`은 "zero to a negative power"
  - 리스트·튜플 언패킹은 "(expected 2, got 3)"
- 성능 측정은 다른 무거운 작업과 겹치지 않게 한다. 전체 검사 중에는 cargo 빌드 같은 CPU 작업을 하지 않는다(Test262 시간 제한).

## 9. 사용자 선호 (세션 기억에서 옮김)

- 개발 중에는 quick(과 코드 생성이 바뀌면 안 되는 리팩터링이면 codegen)만 돌리고, 전체 검사는 큰 PR 직전에 한 번만 돌린다.
- 푸시는 검사 통과 후에 한다. PR은 요청이 있을 때만 만든다. 코어 중립화와 Python은 다른 PR로 올린다(9단계는 별도 쌓은 PR 3).
- 이름: Python 구현은 NeonPy(기술 이름 `neonpython`), JS는 NeonJS, wasm은 NeonWasm(`neonwasm`). 코어의 최종 이름은 미정이다(`neon-vm`은 가칭).
- 사용자는 한국어로 소통한다. 커밋 메시지와 문서는 영어로 쓰고, 커밋 끝에 `Assisted-by: Claude Opus 5.5`를 붙인다.

## 부록 A. 추측 최적화 측정용 임시 테스트

`neonpython/src/test/kotlin/dev/mooner/neonpython/tmp/PyBench.kt`로 만들어
`./gradlew :neonpython:test --tests '*PyBench*' -i | grep BENCH`로 돌린 뒤 지운다.

```kotlin
package dev.mooner.neonpython.tmp

import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonpython.PyContext
import org.junit.jupiter.api.Test

class PyBench {
    @Test
    fun bench() {
        val progs = mapOf(
            "fib(27)" to "def fib(n):\n    if n < 2:\n        return n\n    return fib(n - 1) + fib(n - 2)\nfor _ in range(3):\n    fib(20)\nprint(fib(27))\n",
            "loop 3M" to "def f(n):\n    s = 0\n    i = 0\n    while i < n:\n        s += i & 7\n        i += 1\n    return s\nf(10)\nf(10)\nprint(f(3000000))\n",
            "primes" to "def p(n):\n    c = 0\n    for k in range(2, n):\n        d = 2\n        ok = True\n        while d * d <= k:\n            if k % d == 0:\n                ok = False\n                break\n            d += 1\n        if ok:\n            c += 1\n    return c\np(100)\np(100)\nprint(p(200000))\n",
        )
        for ((name, src) in progs) for ((label, t2) in listOf("adaptive" to false, "adaptive+t2" to true)) {
            val times = ArrayList<Long>()
            var out = ""
            repeat(5) {
                val sb = StringBuilder()
                val t = System.nanoTime()
                PyContext(sb, VmConfig().apply { executionMode = Jit.MODE_ADAPTIVE; jitThreshold = 2; backgroundJit = false; tier2 = t2 }).exec(src)
                times.add((System.nanoTime() - t) / 1_000_000)
                out = sb.toString().trim()
            }
            System.err.println("BENCH $name $label out=$out ms=$times")
        }
    }
}
```

CPython 3.14 기준(같은 PC에서 다시 잴 것): fib(27) 22ms, loop 3M 110ms, primes 292ms.
