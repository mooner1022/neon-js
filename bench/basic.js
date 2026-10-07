function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
function loop() { let s = 0; for (let i = 0; i < 3000000; i++) { s += i % 7; } return s; }
function objs() {
  let arr = [];
  for (let i = 0; i < 200000; i++) arr.push({ x: i, y: i * 2, sum() { return this.x + this.y; } });
  let t = 0;
  for (const o of arr) t += o.sum();
  return t;
}
function strings() { let s = ''; for (let i = 0; i < 100000; i++) s += String.fromCharCode(65 + i % 26); return s.length; }
function classes() {
  class P { constructor(x, y) { this.x = x; this.y = y; } add(o) { return new P(this.x + o.x, this.y + o.y); } }
  let p = new P(0, 0);
  for (let i = 0; i < 300000; i++) p = p.add(new P(1, 2));
  return p.x + p.y;
}
function closures() { let fs = []; for (let i = 0; i < 100000; i++) fs.push(() => i); let s = 0; for (const f of fs) s += f(); return s; }
for (const [name, f] of [["fib", () => fib(27)], ["loop", loop], ["objs", objs], ["strings", strings], ["classes", classes], ["closures", closures]]) {
  f();
  const s = java_nanos();
  const r = f();
  print(name + ": " + ((java_nanos() - s) / 1e6).toFixed(1) + " ms (" + r + ")");
}
