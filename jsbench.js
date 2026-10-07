// jsbench.js — JS 엔진 성능 측정용 단일 파일 벤치마크
// 실행: node jsbench.js | d8 jsbench.js | qjs jsbench.js | jsc jsbench.js | bun jsbench.js | 브라우저 콘솔
// 옵션: 첫 인자로 배율(scale) 지정 가능 (기본 1.0). 예) node jsbench.js 0.5
//
// 설계 원칙
//  - 서로 다른 엔진 하위 시스템을 겨냥한 10개 워크로드 (JIT 정수/부동소수, IC/형태 다형성, GC, 문자열, 정규식, 클로저, 컬렉션, TypedArray, JSON)
//  - 모든 결과는 체크섬으로 검증 → 데드코드 제거로 "가짜로 빠른" 결과 방지
//  - 워밍업 1회 후 여러 번 측정, 중앙값 사용 → JIT 티어업 노이즈 완화
//  - 점수 = 기준 시간 대비 속도의 기하평균 (높을수록 빠름)

"use strict";

// ---------- 환경 호환 레이어 ----------
const out = typeof console !== "undefined" && console.log ? (s) => console.log(s)
          : typeof print === "function" ? print : () => {};
const now = typeof performance !== "undefined" && performance.now ? () => performance.now()
          : () => Date.now();
const argv = typeof process !== "undefined" ? process.argv.slice(2)
           : typeof scriptArgs !== "undefined" ? scriptArgs.slice(1)
           : typeof arguments !== "undefined" ? Array.from(arguments) : [];
const SCALE = Number(argv[0]) > 0 ? Number(argv[0]) : 1.0;

// 결정적 PRNG (xorshift32) — Math.random 구현 차이 배제
function makeRng(seed) {
  let s = seed | 0 || 1;
  return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return (s >>> 0) / 4294967296; };
}

// ---------- 워크로드 ----------
// 각 워크로드는 n(반복 규모)을 받아 체크섬(숫자)을 반환한다.

// 1. 정수 연산 + 배열 인덱싱: 에라토스테네스의 체
function sieve(n) {
  const N = 2_000_000;
  let total = 0;
  for (let r = 0; r < n; r++) {
    const flags = new Uint8Array(N + 1);
    let count = 0;
    for (let i = 2; i <= N; i++) {
      if (flags[i] === 0) {
        count++;
        for (let j = i * i; j <= N; j += i) flags[j] = 1;
      }
    }
    total += count;
  }
  return total;
}

// 2. 부동소수 연산: N-body 시뮬레이션 (Computer Language Benchmarks Game 축약)
function nbody(n) {
  const PI = Math.PI, SOLAR_MASS = 4 * PI * PI, DPY = 365.24;
  function Body(x, y, z, vx, vy, vz, m) {
    this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.mass = m;
  }
  const bodies = [
    new Body(0, 0, 0, 0, 0, 0, SOLAR_MASS),
    new Body(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01,
      1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY, 9.54791938424326609e-04 * SOLAR_MASS),
    new Body(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01,
      -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY, 2.85885980666130812e-04 * SOLAR_MASS),
    new Body(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01,
      2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY, 4.36624404335156298e-05 * SOLAR_MASS),
    new Body(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01,
      2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY, 5.15138902046611451e-05 * SOLAR_MASS),
  ];
  let px = 0, py = 0, pz = 0;
  for (const b of bodies) { px += b.vx * b.mass; py += b.vy * b.mass; pz += b.vz * b.mass; }
  bodies[0].vx = -px / SOLAR_MASS; bodies[0].vy = -py / SOLAR_MASS; bodies[0].vz = -pz / SOLAR_MASS;
  const len = bodies.length, dt = 0.01;
  for (let s = 0; s < n; s++) {
    for (let i = 0; i < len; i++) {
      const bi = bodies[i];
      for (let j = i + 1; j < len; j++) {
        const bj = bodies[j];
        const dx = bi.x - bj.x, dy = bi.y - bj.y, dz = bi.z - bj.z;
        const d2 = dx * dx + dy * dy + dz * dz;
        const mag = dt / (d2 * Math.sqrt(d2));
        bi.vx -= dx * bj.mass * mag; bi.vy -= dy * bj.mass * mag; bi.vz -= dz * bj.mass * mag;
        bj.vx += dx * bi.mass * mag; bj.vy += dy * bi.mass * mag; bj.vz += dz * bi.mass * mag;
      }
    }
    for (let i = 0; i < len; i++) {
      const b = bodies[i];
      b.x += dt * b.vx; b.y += dt * b.vy; b.z += dt * b.vz;
    }
  }
  let e = 0;
  for (let i = 0; i < len; i++) {
    const bi = bodies[i];
    e += 0.5 * bi.mass * (bi.vx * bi.vx + bi.vy * bi.vy + bi.vz * bi.vz);
    for (let j = i + 1; j < len; j++) {
      const bj = bodies[j];
      const dx = bi.x - bj.x, dy = bi.y - bj.y, dz = bi.z - bj.z;
      e -= (bi.mass * bj.mass) / Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
  }
  return Math.round(e * 1e9);
}

// 3. 객체 할당 + GC 압박: 이진 트리 생성/순회 (binary-trees)
function binaryTrees(n) {
  function make(d) { return d === 0 ? { l: null, r: null } : { l: make(d - 1), r: make(d - 1) }; }
  function check(t) { return t.l === null ? 1 : 1 + check(t.l) + check(t.r); }
  const maxDepth = 16;
  const longLived = make(maxDepth);
  let total = 0;
  for (let r = 0; r < n; r++) {
    for (let d = 4; d <= maxDepth; d += 2) {
      const iters = 1 << (maxDepth - d + 4);
      for (let i = 0; i < iters; i++) total += check(make(d));
    }
  }
  return total + check(longLived);
}

// 4. 인라인 캐시 / 다형성: 서로 다른 Shape의 클래스 6종에 대한 가상 호출
function polymorphic(n) {
  class Circle  { constructor(r) { this.r = r; } area() { return 3.14159 * this.r * this.r; } }
  class Square  { constructor(s) { this.s = s; } area() { return this.s * this.s; } }
  class Rect    { constructor(w, h) { this.w = w; this.h = h; } area() { return this.w * this.h; } }
  class Tri     { constructor(b, h) { this.b = b; this.h = h; } area() { return 0.5 * this.b * this.h; } }
  class Ellipse { constructor(a, b) { this.a = a; this.b = b; } area() { return 3.14159 * this.a * this.b; } }
  class Trap    { constructor(a, b, h) { this.a = a; this.b = b; this.h = h; } area() { return 0.5 * (this.a + this.b) * this.h; } }
  const rnd = makeRng(42);
  const shapes = [];
  for (let i = 0; i < 4096; i++) {
    const k = (rnd() * 6) | 0, v = 1 + rnd() * 10;
    shapes.push(k === 0 ? new Circle(v) : k === 1 ? new Square(v) : k === 2 ? new Rect(v, v + 1)
              : k === 3 ? new Tri(v, v + 2) : k === 4 ? new Ellipse(v, v / 2) : new Trap(v, v + 1, v / 3));
  }
  let sum = 0;
  for (let r = 0; r < n; r++)
    for (let i = 0; i < shapes.length; i++) sum += shapes[i].area();
  return Math.round(sum);
}

// 5. 문자열 생성/연결/검색
function strings(n) {
  let acc = 0;
  for (let r = 0; r < n; r++) {
    const parts = [];
    for (let i = 0; i < 20000; i++) parts.push("item" + i + ":" + (i * 7919 % 10007).toString(36));
    const big = parts.join(",");
    let pos = 0, hits = 0;
    while ((pos = big.indexOf("z", pos)) !== -1) { hits++; pos++; }
    const up = big.slice(0, 50000).toUpperCase();
    let h = 0;
    for (let i = 0; i < up.length; i++) h = (h * 31 + up.charCodeAt(i)) | 0;
    acc = (acc + hits + h + big.split(",").length) | 0;
  }
  return acc;
}

// 6. 정규식 엔진
function regex(n) {
  const rnd = makeRng(7);
  const words = ["alpha", "beta", "gamma", "delta", "user", "admin", "mail", "host", "data", "node"];
  const lines = [];
  for (let i = 0; i < 2000; i++) {
    const w = () => words[(rnd() * words.length) | 0];
    lines.push(`${i} ${w()}.${w()}@${w()}.com 192.168.${(rnd() * 255) | 0}.${(rnd() * 255) | 0} ` +
               `2026-${String(1 + ((rnd() * 12) | 0)).padStart(2, "0")}-${String(1 + ((rnd() * 28) | 0)).padStart(2, "0")} ${w()}${(rnd() * 1000) | 0}`);
  }
  const text = lines.join("\n");
  const reEmail = /[a-z]+\.[a-z]+@[a-z]+\.com/g;
  const reIp = /\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/g;
  const reDate = /(\d{4})-(\d{2})-(\d{2})/g;
  let acc = 0;
  for (let r = 0; r < n; r++) {
    let m;
    reEmail.lastIndex = 0; while ((m = reEmail.exec(text)) !== null) acc += m[0].length;
    reIp.lastIndex = 0;    while ((m = reIp.exec(text)) !== null) acc += +m[4];
    acc += text.replace(reDate, "$3/$2/$1").length;
    acc = acc | 0;
  }
  return acc;
}

// 7. 클로저 + 고차 함수 + 배열 내장 메서드
function functional(n) {
  const base = Array.from({ length: 10000 }, (_, i) => i);
  let acc = 0;
  for (let r = 0; r < n; r++) {
    const k = r % 7 + 1;
    const res = base
      .map((x) => x * k)
      .filter((x) => (x & 3) !== 0)
      .map((x) => ({ v: x, sq: x * x }))
      .reduce((a, o) => (a + (o.sq % 1000) + o.v) | 0, 0);
    const sorted = base.slice(0, 2000).sort((a, b) => ((b * k) % 101) - ((a * k) % 101) || a - b);
    acc = (acc + res + sorted[0] + sorted[1999]) | 0;
  }
  return acc;
}

// 8. Map / Set 해시 컬렉션
function collections(n) {
  let acc = 0;
  for (let r = 0; r < n; r++) {
    const m = new Map(), s = new Set();
    for (let i = 0; i < 100000; i++) {
      const key = (i * 2654435761) >>> 12;
      m.set(key, i);
      s.add("k" + (key & 0xffff));
    }
    let sum = 0;
    for (let i = 0; i < 100000; i++) {
      const v = m.get((i * 2654435761) >>> 12);
      if (v !== undefined) sum += v;
      if (s.has("k" + (i & 0xffff))) sum++;
    }
    for (const [k, v] of m) if ((k & 7) === 0) m.delete(k);
    acc = (acc + sum + m.size + s.size) | 0;
  }
  return acc;
}

// 9. TypedArray 수치 커널: 행렬 곱 (Float64Array)
function matmul(n) {
  const N = 160;
  const rnd = makeRng(99);
  const A = new Float64Array(N * N), B = new Float64Array(N * N), C = new Float64Array(N * N);
  for (let i = 0; i < N * N; i++) { A[i] = rnd(); B[i] = rnd(); }
  let tr = 0;
  for (let r = 0; r < n; r++) {
    C.fill(0);
    for (let i = 0; i < N; i++)
      for (let k = 0; k < N; k++) {
        const a = A[i * N + k];
        for (let j = 0; j < N; j++) C[i * N + j] += a * B[k * N + j];
      }
    for (let i = 0; i < N; i++) tr += C[i * N + i];
    A[r % (N * N)] += 1e-3;
  }
  return Math.round(tr * 1000);
}

// 10. JSON 직렬화/역직렬화
function json(n) {
  const rnd = makeRng(2026);
  const doc = [];
  for (let i = 0; i < 2000; i++)
    doc.push({ id: i, name: "user_" + i, score: Math.round(rnd() * 1e6) / 100, active: (i & 1) === 0,
               tags: ["a" + (i % 5), "b" + (i % 7)], meta: { created: 1700000000 + i, nested: { x: i, y: [i, i + 1] } } });
  let acc = 0;
  for (let r = 0; r < n; r++) {
    const s = JSON.stringify(doc);
    const back = JSON.parse(s);
    acc = (acc + s.length + back[r % back.length].meta.nested.y[1]) | 0;
  }
  return acc;
}

// ---------- 테스트 정의 ----------
// iters: 규모, ref: 기준 시간(ms) — 기준 머신(Xeon 2.1GHz, Node 22/V8)에서 측정된 1회 실행 시간.
// expect: 정답 체크섬 (scale=1일 때만 검증 — 다른 배율에선 '-'로 표시).
// 점수 1000 = 기준 머신 V8과 동일, 2000 = 2배 빠름.
const TESTS = [
  { name: "sieve",       desc: "int/array loop",  fn: sieve,       iters: 36,      ref: 389.2, expect: 5361588 },
  { name: "nbody",       desc: "float math",      fn: nbody,       iters: 4000000, ref: 400.4, expect: -169049263 },
  { name: "binaryTrees", desc: "alloc/GC",        fn: binaryTrees, iters: 1,       ref: 319.4, expect: 14723759 },
  { name: "polymorphic", desc: "IC/polymorphism", fn: polymorphic, iters: 3000,    ref: 394.7, expect: 723410827 },
  { name: "strings",     desc: "string ops",      fn: strings,     iters: 120,     ref: 419.0, expect: -1696680200 },
  { name: "regex",       desc: "regexp",          fn: regex,       iters: 360,     ref: 364.0, expect: 148918680 },
  { name: "functional",  desc: "closure/HOF",     fn: functional,  iters: 500,     ref: 365.6, expect: -1868202749 },
  { name: "collections", desc: "Map/Set",         fn: collections, iters: 8,       ref: 361.6, expect: 1346317912 },
  { name: "matmul",      desc: "TypedArray math", fn: matmul,      iters: 50,      ref: 340.3, expect: 320270878 },
  { name: "json",        desc: "JSON",            fn: json,        iters: 120,     ref: 396.5, expect: 34442940 },
];

const RUNS = 3; // 측정 횟수(워밍업 제외)

// ---------- 실행 ----------
function median(a) { const s = a.slice().sort((x, y) => x - y); return s[s.length >> 1]; }
function pad(s, n) { s = String(s); return s + " ".repeat(Math.max(0, n - s.length)); }
function lpad(s, n) { s = String(s); return " ".repeat(Math.max(0, n - s.length)) + s; }

function main() {
  out(`jsbench — scale=${SCALE}, runs=${RUNS} (+1 warmup)`);
  out("-".repeat(68));
  out(pad("test", 13) + pad("area", 16) + lpad("median ms", 11) + lpad("score", 9) + "  check");
  out("-".repeat(68));
  const t0 = now();
  let logSum = 0, failed = 0;
  for (const t of TESTS) {
    const n = Math.max(1, Math.round(t.iters * SCALE));
    const warmN = Math.max(1, Math.round(n / 4));
    t.fn(warmN); // 워밍업 (JIT 티어업 유도)
    const times = [];
    let sum;
    for (let r = 0; r < RUNS; r++) {
      const s = now();
      sum = t.fn(n);
      times.push(now() - s);
    }
    const ms = median(times);
    const ok = SCALE !== 1 || t.expect === null ? "-" : sum === t.expect ? "OK" : "FAIL";
    if (ok === "FAIL") failed++;
    const score = t.ref > 0 ? (t.ref * SCALE / ms) * 1000 : NaN;
    if (t.ref > 0) logSum += Math.log(score);
    out(pad(t.name, 13) + pad(t.desc, 16) + lpad(ms.toFixed(1), 11) + lpad(isNaN(score) ? "-" : score.toFixed(0), 9) +
        "  " + ok + (ok === "-" ? `  (sum=${sum})` : ""));
  }
  out("-".repeat(68));
  const total = (now() - t0) / 1000;
  const scored = TESTS.filter((t) => t.ref > 0).length;
  if (scored) out(`TOTAL SCORE (기하평균, 기준=1000, 높을수록 빠름): ${Math.exp(logSum / scored).toFixed(0)}`);
  out(`전체 실행 시간: ${total.toFixed(1)}s` + (failed ? `   ⚠ 체크섬 불일치 ${failed}건 — 결과 무효` : ""));
}

main();
