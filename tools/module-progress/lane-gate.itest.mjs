#!/usr/bin/env node
/**
 * 调度闸（`lane.mjs gate`）的语义测试。
 *
 * 之前**闸是零测试覆盖**的，而它恰恰是会静默坏掉的那种组件：
 * 它的错误不报错，只是让两个 tick 同时派发（版本号撞号、集成头留残骸、L2 成片假红）。
 *
 * 本测试锁住三件事：
 *   1. `gate status` 必须输出 **ttlSeconds** 与 **stale** ——
 *      没有这两个判据，调度者只能猜「这闸还能不能抢」。
 *   2. **持有 30 分钟的闸不可被抢占**（回归测试：旧 TTL=600 会在这里失败）。
 *      一个 tick 持闸要跑完 merge + L2，实测单轮 40–60 分钟；
 *      TTL 比单轮短 ⇒ 下一轮 tick 会从活着的持闸者手里抢闸 ⇒ 双派发。
 *   3. 超过 TTL 的闸**可以**被接管（崩掉的 tick 不能永久挡住流水线）。
 *
 * 用法：node tools/module-progress/lane-gate.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, utimesSync, statSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const LANE = join(HERE, 'lane.mjs');
const SANDBOX = join(resolve(HERE, '..', '..'), '.tmp', `itest-gate-${process.pid}`);

let passed = 0;
let failed = 0;
const failures = [];

function ok(name, cond, detail = '') {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(`${name}${detail ? ` —— ${detail}` : ''}`);
    console.log(`  ✗ ${name}${detail ? ` —— ${detail}` : ''}`);
  }
}

/** 跑 gate 命令，返回 { code, out } */
function gate(action, opts = {}) {
  try {
    const out = execFileSync('node', [LANE, 'gate', action], {
      cwd: SANDBOX,
      encoding: 'utf8',
      env: { ...process.env, LANE_ROOT: SANDBOX },
    });
    return { code: 0, out };
  } catch (err) {
    return { code: err.status ?? 1, out: err.stdout ?? '' };
  }
}

const json = (r) => {
  try {
    return JSON.parse(r.out);
  } catch {
    return {};
  }
};

const GATE_DIR = join(SANDBOX, '.dev-agent', 'dispatch.gate');

/**
 * 把闸的 mtime 往前推 minutes 分钟（模拟「已经持有这么久」）。
 *
 * ⚠️ 必须同时老化**闸目录里的文件**（keepalive / owner）——
 * 2026-10-30 起闸龄判据改成「持闸者自己续期的文件」，不再看闸目录 mtime
 * （闸目录 mtime 会被续期守护刷新，与持闸者死活无关 ⇒ 孤儿守护能把 TTL 接管堵死）。
 * 只老化目录的话，闸龄仍是 0，测试会假绿。
 */
function ageGate(minutes) {
  const t = (Date.now() - minutes * 60_000) / 1000;
  utimesSync(GATE_DIR, t, t);
  for (const f of readdirSync(GATE_DIR)) {
    if (!f.startsWith('keepalive') && f !== 'owner') continue;
    utimesSync(join(GATE_DIR, f), t, t);
  }
}

/* ── 准备 ─────────────────────────────────────────────────────────────── */

rmSync(SANDBOX, { recursive: true, force: true });
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
console.log(`\n调度闸语义测试 · 沙箱 ${SANDBOX}\n`);

/* ── 1. 空闲状态 ──────────────────────────────────────────────────────── */

console.log('① 空闲时的 status');
let r = gate('status');
let s = json(r);
ok('gate=free', s.gate === 'free', JSON.stringify(s));
ok('输出 ttlSeconds', s.ttlSeconds === 4200, String(s.ttlSeconds));
ok('空闲时 stale=false', s.stale === false, String(s.stale));
ok('空闲时 ageSeconds=null', s.ageSeconds === null, String(s.ageSeconds));

/* ── 2. 取闸 ──────────────────────────────────────────────────────────── */

console.log('② 取闸');
r = gate('acquire');
s = json(r);
ok('acquire 退出码 0', r.code === 0, `code=${r.code}`);
ok('acquire ok=true', s.ok === true, JSON.stringify(s));
ok('闸目录已创建', existsSync(GATE_DIR));

/* ── 3. 持闸期间 ──────────────────────────────────────────────────────── */

console.log('③ 持闸期间');
r = gate('status');
s = json(r);
ok('gate=held', s.gate === 'held', JSON.stringify(s));
ok('stale=false（刚取）', s.stale === false, String(s.stale));

r = gate('acquire');
s = json(r);
ok('重复 acquire 被拒（退出码 3）', r.code === 3, `code=${r.code}`);
ok('重复 acquire ok=false', s.ok === false, JSON.stringify(s));

/* ── 4. 回归：持闸 30 分钟不得被抢（这是被修掉的那个 bug）───────────── */

console.log('④ 回归：持闸 30 分钟（tick 正在跑 L2）');
ageGate(30);
r = gate('status');
s = json(r);
ok('30 分钟后 stale=false（TTL 覆盖单轮时长）', s.stale === false, JSON.stringify(s));
ok('ageSeconds 反映真实年龄', s.ageSeconds >= 1790 && s.ageSeconds <= 1810, String(s.ageSeconds));

r = gate('acquire');
s = json(r);
ok('★30 分钟的活闸不被抢占（退出码 3）', r.code === 3, `code=${r.code}`);
ok('★30 分钟的活闸 ok=false', s.ok === false, JSON.stringify(s));

/* ── 5. 崩溃的闸：超过 TTL 必须可接管 ────────────────────────────────── */

console.log('⑤ 崩溃的闸（超过 TTL）可被接管');
ageGate(80);
r = gate('status');
s = json(r);
ok('80 分钟后 stale=true', s.stale === true, JSON.stringify(s));

r = gate('acquire');
s = json(r);
ok('陈旧闸可被接管（退出码 0）', r.code === 0, `code=${r.code}`);
ok('接管后 ok=true', s.ok === true, JSON.stringify(s));

/* ── 6. 释放 ──────────────────────────────────────────────────────────── */

console.log('⑥ 释放');
r = gate('release');
s = json(r);
ok('release ok=true', s.ok === true, JSON.stringify(s));
r = gate('status');
s = json(r);
ok('释放后 gate=free', s.gate === 'free', JSON.stringify(s));
ok('释放后 ageSeconds=null', s.ageSeconds === null, String(s.ageSeconds));

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

rmSync(SANDBOX, { recursive: true, force: true });
console.log(`\n结果：${passed} 通过，${failed} 失败`);
if (failed) {
  console.log('\n失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
