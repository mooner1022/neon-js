// Property-access heavy micro benchmarks (named loads/stores, prototype method calls, polymorphic sites).
class Vec { constructor(x, y, z) { this.x = x; this.y = y; this.z = z; }
  dot(o) { return this.x * o.x + this.y * o.y + this.z * o.z; }
  scale(k) { this.x *= k; this.y *= k; this.z *= k; return this; } }
function ownLoads() { const o = { a: 1, b: 2, c: 3, d: 4 }; let s = 0; for (let i = 0; i < 2000000; i++) s += o.a + o.b + o.c + o.d; return s; }
function stores() { const o = { a: 0, b: 0 }; for (let i = 0; i < 2000000; i++) { o.a = i; o.b = o.a + 1; } return o.b; }
function methods() { const v = new Vec(1, 2, 3), w = new Vec(4, 5, 6); let s = 0; for (let i = 0; i < 1000000; i++) s += v.dot(w); return s; }
function poly() {
  const shapes = [{ a: 1 }, { b: 1, a: 2 }, { c: 1, b: 1, a: 3 }, { d: 1, a: 4 }];
  let s = 0; for (let i = 0; i < 2000000; i++) s += shapes[i & 3].a; return s;
}
function protoChain() {
  class A { m() { return 1; } } class B extends A {} class C extends B {}
  const c = new C(); let s = 0; for (let i = 0; i < 1000000; i++) s += c.m(); return s;
}
function builtinCalls() { const a = [1, 2, 3]; let s = 0; for (let i = 0; i < 1000000; i++) s += a.length + Math.abs(-i) % 3; return s; }
function construct() { let s = 0; for (let i = 0; i < 500000; i++) { const v = new Vec(i, 1, 2); s += v.scale(2).x; } return s; }
for (const [name, f] of [["ownLoads", ownLoads], ["stores", stores], ["methods", methods], ["poly", poly], ["protoChain", protoChain], ["builtinCalls", builtinCalls], ["construct", construct]]) {
  f(); f();
  const s = java_nanos();
  const r = f();
  print(name + ": " + ((java_nanos() - s) / 1e6).toFixed(1) + " ms (" + r + ")");
}
