// Ten workloads aimed at different parts of an engine, in a dialect every compared engine parses (Rhino has no block
// scoping in loops and no classes): `var`, constructor functions, no destructuring. The same file runs on NeonJS, V8
// (node) and Rhino. The algorithms are those of jsbench.js.
//
//   node bench/compare.js [scale [workload]]
//   neonjs bench/compare.js                     (the CLI passes arguments to --node scripts only)
//   java -jar rhino-all.jar -version 200 bench/compare.js [scale [workload]]
//
// Each workload runs once to warm up, then RUNS times; the median is printed with the workload's checksum, which must
// be the same on every engine (a result computed differently would make the time meaningless).
"use strict";

var out = typeof print === "function" ? print : function (s) { console.log(s); };
var now = typeof performance !== "undefined" && performance.now ? function () { return performance.now(); }
    : function () { return Date.now(); };
var args = typeof process !== "undefined" ? process.argv.slice(2)
    : typeof scriptArgs !== "undefined" ? scriptArgs.slice(1)
    : typeof arguments !== "undefined" ? Array.prototype.slice.call(arguments) : [];
var SCALE = Number(args[0]) > 0 ? Number(args[0]) : 1;
var RUNS = 3;

// deterministic PRNG (xorshift32): no Math.random differences between engines
function makeRng(seed) {
  var s = seed | 0 || 1;
  return function () { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return (s >>> 0) / 4294967296; };
}

// 1. integers and array indexing: sieve of Eratosthenes
function sieve(n) {
  var N = 2000000, total = 0;
  for (var r = 0; r < n; r++) {
    var flags = new Uint8Array(N + 1), count = 0;
    for (var i = 2; i <= N; i++) {
      if (flags[i] === 0) {
        count++;
        for (var j = i * i; j <= N; j += i) flags[j] = 1;
      }
    }
    total += count;
  }
  return total;
}

// 2. floating point: n-body
function Body(x, y, z, vx, vy, vz, m) {
  this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.mass = m;
}
function nbody(n) {
  var PI = Math.PI, SOLAR_MASS = 4 * PI * PI, DPY = 365.24;
  var bodies = [
    new Body(0, 0, 0, 0, 0, 0, SOLAR_MASS),
    new Body(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01,
      1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY, 9.54791938424326609e-04 * SOLAR_MASS),
    new Body(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01,
      -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY, 2.85885980666130812e-04 * SOLAR_MASS),
    new Body(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01,
      2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY, 4.36624404335156298e-05 * SOLAR_MASS),
    new Body(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01,
      2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY, 5.15138902046611451e-05 * SOLAR_MASS)
  ];
  var px = 0, py = 0, pz = 0, len = bodies.length, dt = 0.01, i, j, b, bi, bj, dx, dy, dz, d2, mag;
  for (i = 0; i < len; i++) { b = bodies[i]; px += b.vx * b.mass; py += b.vy * b.mass; pz += b.vz * b.mass; }
  bodies[0].vx = -px / SOLAR_MASS; bodies[0].vy = -py / SOLAR_MASS; bodies[0].vz = -pz / SOLAR_MASS;
  for (var s = 0; s < n; s++) {
    for (i = 0; i < len; i++) {
      bi = bodies[i];
      for (j = i + 1; j < len; j++) {
        bj = bodies[j];
        dx = bi.x - bj.x; dy = bi.y - bj.y; dz = bi.z - bj.z;
        d2 = dx * dx + dy * dy + dz * dz;
        mag = dt / (d2 * Math.sqrt(d2));
        bi.vx -= dx * bj.mass * mag; bi.vy -= dy * bj.mass * mag; bi.vz -= dz * bj.mass * mag;
        bj.vx += dx * bi.mass * mag; bj.vy += dy * bi.mass * mag; bj.vz += dz * bi.mass * mag;
      }
    }
    for (i = 0; i < len; i++) {
      b = bodies[i];
      b.x += dt * b.vx; b.y += dt * b.vy; b.z += dt * b.vz;
    }
  }
  var e = 0;
  for (i = 0; i < len; i++) {
    bi = bodies[i];
    e += 0.5 * bi.mass * (bi.vx * bi.vx + bi.vy * bi.vy + bi.vz * bi.vz);
    for (j = i + 1; j < len; j++) {
      bj = bodies[j];
      dx = bi.x - bj.x; dy = bi.y - bj.y; dz = bi.z - bj.z;
      e -= (bi.mass * bj.mass) / Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
  }
  return Math.round(e * 1e9);
}

// 3. allocation and GC: binary trees
function make(d) { return d === 0 ? { l: null, r: null } : { l: make(d - 1), r: make(d - 1) }; }
function check(t) { return t.l === null ? 1 : 1 + check(t.l) + check(t.r); }
function binaryTrees(n) {
  var maxDepth = 16, longLived = make(maxDepth), total = 0;
  for (var r = 0; r < n; r++) {
    for (var d = 4; d <= maxDepth; d += 2) {
      var iters = 1 << (maxDepth - d + 4);
      for (var i = 0; i < iters; i++) total += check(make(d));
    }
  }
  return total + check(longLived);
}

// 4. inline caches and polymorphism: a method of six shapes called at one site
function Circle(r) { this.r = r; }
Circle.prototype.area = function () { return 3.14159 * this.r * this.r; };
function Square(s) { this.s = s; }
Square.prototype.area = function () { return this.s * this.s; };
function Rect(w, h) { this.w = w; this.h = h; }
Rect.prototype.area = function () { return this.w * this.h; };
function Tri(b, h) { this.b = b; this.h = h; }
Tri.prototype.area = function () { return 0.5 * this.b * this.h; };
function Ellipse(a, b) { this.a = a; this.b = b; }
Ellipse.prototype.area = function () { return 3.14159 * this.a * this.b; };
function Trap(a, b, h) { this.a = a; this.b = b; this.h = h; }
Trap.prototype.area = function () { return 0.5 * (this.a + this.b) * this.h; };
function polymorphic(n) {
  var rnd = makeRng(42), shapes = [];
  for (var i = 0; i < 4096; i++) {
    var k = (rnd() * 6) | 0, v = 1 + rnd() * 10;
    shapes.push(k === 0 ? new Circle(v) : k === 1 ? new Square(v) : k === 2 ? new Rect(v, v + 1)
      : k === 3 ? new Tri(v, v + 2) : k === 4 ? new Ellipse(v, v / 2) : new Trap(v, v + 1, v / 3));
  }
  var sum = 0;
  for (var r = 0; r < n; r++)
    for (var j = 0; j < shapes.length; j++) sum += shapes[j].area();
  return Math.round(sum);
}

// 5. strings: building, joining, searching, case mapping, splitting
function strings(n) {
  var acc = 0;
  for (var r = 0; r < n; r++) {
    var parts = [];
    for (var i = 0; i < 20000; i++) parts.push("item" + i + ":" + (i * 7919 % 10007).toString(36));
    var big = parts.join(",");
    var pos = 0, hits = 0;
    while ((pos = big.indexOf("z", pos)) !== -1) { hits++; pos++; }
    var up = big.slice(0, 50000).toUpperCase();
    var h = 0;
    for (var j = 0; j < up.length; j++) h = (h * 31 + up.charCodeAt(j)) | 0;
    acc = (acc + hits + h + big.split(",").length) | 0;
  }
  return acc;
}

// 6. regular expressions
function regex(n) {
  var rnd = makeRng(7);
  var words = ["alpha", "beta", "gamma", "delta", "user", "admin", "mail", "host", "data", "node"];
  function w() { return words[(rnd() * words.length) | 0]; }
  var lines = [];
  for (var i = 0; i < 2000; i++) {
    var line = i + " " + w() + "." + w() + "@" + w() + ".com 192.168." + ((rnd() * 255) | 0) + "." + ((rnd() * 255) | 0) + " ";
    line += "2026-" + String(1 + ((rnd() * 12) | 0)).padStart(2, "0") + "-" + String(1 + ((rnd() * 28) | 0)).padStart(2, "0") +
      " " + w() + ((rnd() * 1000) | 0);
    lines.push(line);
  }
  var text = lines.join("\n");
  var reEmail = /[a-z]+\.[a-z]+@[a-z]+\.com/g;
  var reIp = /\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/g;
  var reDate = /(\d{4})-(\d{2})-(\d{2})/g;
  var acc = 0, m;
  for (var r = 0; r < n; r++) {
    reEmail.lastIndex = 0; while ((m = reEmail.exec(text)) !== null) acc += m[0].length;
    reIp.lastIndex = 0; while ((m = reIp.exec(text)) !== null) acc += +m[4];
    acc += text.replace(reDate, "$3/$2/$1").length;
    acc = acc | 0;
  }
  return acc;
}

// 7. closures, higher-order functions and array built-ins
function functional(n) {
  var base = [];
  for (var i = 0; i < 10000; i++) base.push(i);
  var acc = 0;
  for (var r = 0; r < n; r++) {
    var k = r % 7 + 1;
    var res = base
      .map(function (x) { return x * k; })
      .filter(function (x) { return (x & 3) !== 0; })
      .map(function (x) { return { v: x, sq: x * x }; })
      .reduce(function (a, o) { return (a + (o.sq % 1000) + o.v) | 0; }, 0);
    var sorted = base.slice(0, 2000).sort(function (a, b) { return ((b * k) % 101) - ((a * k) % 101) || a - b; });
    acc = (acc + res + sorted[0] + sorted[1999]) | 0;
  }
  return acc;
}

// 8. hash collections: Map and Set
function collections(n) {
  var acc = 0;
  for (var r = 0; r < n; r++) {
    var m = new Map(), s = new Set(), i, key;
    for (i = 0; i < 100000; i++) {
      key = (i * 2654435761) >>> 12;
      m.set(key, i);
      s.add("k" + (key & 0xffff));
    }
    var sum = 0;
    for (i = 0; i < 100000; i++) {
      var v = m.get((i * 2654435761) >>> 12);
      if (v !== undefined) sum += v;
      if (s.has("k" + (i & 0xffff))) sum++;
    }
    var it = m.keys();
    for (var e = it.next(); !e.done; e = it.next()) if ((e.value & 7) === 0) m["delete"](e.value);
    acc = (acc + sum + m.size + s.size) | 0;
  }
  return acc;
}

// 9. typed-array numerics: matrix product
function matmul(n) {
  var N = 160, rnd = makeRng(99);
  var A = new Float64Array(N * N), B = new Float64Array(N * N), C = new Float64Array(N * N), i, j, k;
  for (i = 0; i < N * N; i++) { A[i] = rnd(); B[i] = rnd(); }
  var tr = 0;
  for (var r = 0; r < n; r++) {
    C.fill(0);
    for (i = 0; i < N; i++)
      for (k = 0; k < N; k++) {
        var a = A[i * N + k];
        for (j = 0; j < N; j++) C[i * N + j] += a * B[k * N + j];
      }
    for (i = 0; i < N; i++) tr += C[i * N + i];
    A[r % (N * N)] += 1e-3;
  }
  return Math.round(tr * 1000);
}

// 10. JSON
function json(n) {
  var rnd = makeRng(2026), doc = [];
  for (var i = 0; i < 2000; i++)
    doc.push({ id: i, name: "user_" + i, score: Math.round(rnd() * 1e6) / 100, active: (i & 1) === 0,
      tags: ["a" + (i % 5), "b" + (i % 7)], meta: { created: 1700000000 + i, nested: { x: i, y: [i, i + 1] } } });
  var acc = 0;
  for (var r = 0; r < n; r++) {
    var s = JSON.stringify(doc);
    var back = JSON.parse(s);
    acc = (acc + s.length + back[r % back.length].meta.nested.y[1]) | 0;
  }
  return acc;
}

// iters: the size of one run (about 400 ms on V8 on the reference machine); expect: the checksum at scale 1
var TESTS = [
  { name: "sieve", fn: sieve, iters: 36, expect: 5361588 },
  { name: "nbody", fn: nbody, iters: 4000000, expect: -169049263 },
  { name: "binaryTrees", fn: binaryTrees, iters: 1, expect: 14723759 },
  { name: "polymorphic", fn: polymorphic, iters: 3000, expect: 723410827 },
  { name: "strings", fn: strings, iters: 120, expect: -1696680200 },
  { name: "regex", fn: regex, iters: 360, expect: 148918680 },
  { name: "functional", fn: functional, iters: 500, expect: -1868202749 },
  { name: "collections", fn: collections, iters: 8, expect: 1346317912 },
  { name: "matmul", fn: matmul, iters: 50, expect: 320270878 },
  { name: "json", fn: json, iters: 120, expect: 34442940 }
];

function median(a) { var s = a.slice().sort(function (x, y) { return x - y; }); return s[s.length >> 1]; }

var only = args[1];
for (var t = 0; t < TESTS.length; t++) {
  var test = TESTS[t];
  if (only && only !== test.name) continue;
  var n = Math.max(1, Math.round(test.iters * SCALE));
  var w0 = now();
  test.fn(Math.max(1, Math.round(n / 4)));
  var warm = now() - w0;
  var times = [], sum;
  for (var run = 0; run < RUNS; run++) {
    var s0 = now();
    sum = test.fn(n);
    times.push(now() - s0);
  }
  var status = SCALE !== 1 ? "-" : sum === test.expect ? "ok" : "MISMATCH(" + sum + ")";
  // name, median ms, first (warm-up, a quarter of a run) ms, checksum
  out(test.name + " " + median(times).toFixed(1) + " " + warm.toFixed(1) + " " + status);
}
