#!/usr/bin/env node
/**
 * 调度闸「受管接管」路径的语义测试（`lane.mjs gate reclaim` / `touch` / `release`）。
 *
 * 这套测试锁住的是 2026-10-30 一整天只交付 5 个模块的三个根因：
 *
 *   ① **孤儿续期守护把 TTL 自愈路径堵死**（round-130/131/132 连做三次取证却无人处置）。
 *      判据改成「持闸者自己续期的文件」后，守护碰闸目录**不再能掩盖持闸者的死亡**。
 *   ② **非原子的 `rm + mkdir` 接管** ⇒ 两个 tick 可同时判定「陈旧」⇒ 双向持闸
 *      （round-102 实测「🔴 双向持闸实证」）。改成「rename 到固定目标 + 改名后重新判定」后
 *      结构上不可能。
 *   ③ **没有受管的接管入口** ⇒ 每个 tick 自己写临时接管脚本，或只取证不动手，
 *      把一个可自愈的故障固化成长期停机（round-133 的教训）。
 *
 * 用法：node tools/module-progress/lane-gate-reclaim.itest.mjs
 */

import { execFileSync, spawn } from 'node:child_process';
import {
  mkdirSync,
  rmSync,
  utimesSync,
  existsSync,
  readFileSync,
  writeFileSync,
  readdirSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const LANE = join(HERE, 'lane.mjs');
const SANDBOX = join(resolve(HERE, '..', '..'), '.tmp', `itest-gate-reclaim-${process.pid}`);

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

/** 跑 lane.mjs（沙箱内），返回 { code, out } */
function run(args) {
  try {
    const out = execFileSync('node', [LANE, ...args], {
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
const TOKEN_FILE = join(SANDBOX, '.dev-agent', 'dispatch.gate.token');
const LANES_FILE = join(SANDBOX, '.dev-agent', 'lanes.json');

/** 把闸老化 minutes 分钟（同时老化目录与里面的文件） */
function ageGate(minutes) {
  const t = (Date.now() - minutes * 60_000) / 1000;
  utimesSync(GATE_DIR, t, t);
  for (const f of readdirSync(GATE_DIR)) {
    if (!f.startsWith('keepalive') && f !== 'owner') continue;
    utimesSync(join(GATE_DIR, f), t, t);
  }
}

/** 只老化闸目录 mtime —— 模拟「孤儿守护在 touch 闸目录」 */
function touchGateDirOnly() {
  const now = new Date();
  utimesSync(GATE_DIR, now, now);
}

/** 伪造在途车道（持闸者还活着的证据之一） */
function setInFlightLanes(n) {
  const lanes = {};
  for (let i = 0; i < n; i += 1) {
    lanes[`9.9.${i}.1`] = { id: `9.9.${i}.1`, state: 'running' };
  }
  writeFileSync(LANES_FILE, JSON.stringify({ lanes }, null, 2));
}

function clearInFlightLanes() {
  writeFileSync(LANES_FILE, JSON.stringify({ lanes: {} }, null, 2));
}

/** 读当前闸的 owner 令牌 */
function currentOwnerToken() {
  try {
    return readFileSync(join(GATE_DIR, 'owner'), 'utf8').split('\n')[3] || null;
  } catch {
    return null;
  }
}

/* ── 准备 ─────────────────────────────────────────────────────────────── */

rmSync(SANDBOX, { recursive: true, force: true });
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
writeFileSync(LANES_FILE, JSON.stringify({ lanes: {} }, null, 2));
console.log(`\n调度闸受管接管语义测试 · 沙箱 ${SANDBOX}\n`);

/* ── 1. 闸龄判据不受「碰闸目录」影响（治孤儿守护死锁）────────────────── */

console.log('① 闸龄判据：守护碰闸目录不再能掩盖持闸者的死亡');
run(['gate', 'acquire']);
ok('已取到闸', existsSync(GATE_DIR));

// 先把闸老化成 80 分钟前（模拟持闸者已死很久），再让「守护」去碰闸目录
ageGate(80);
touchGateDirOnly();

let r = run(['gate', 'status']);
let s = json(r);
ok(
  '★只 touch 闸目录后，闸龄仍按 keepalive/owner 文件算（没被刷新成 0）',
  s.ageSeconds > 3600,
  `ageSeconds=${s.ageSeconds}`,
);
ok('闸仍判定为 stale（>TTL）', s.stale === true, JSON.stringify(s));
ok('reclaimable=true（持闸者无在途车道、无构建进程）', s.reclaimable === true, JSON.stringify(s));

/* ── 2. 并发接管：只有一个能成功（治双向持闸）────────────────────────── */

console.log('② 并发接管同一把陈旧闸');

// 同时起 8 个进程去接管同一把陈旧闸
const racers = await Promise.all(
  Array.from(
    { length: 8 },
    () =>
      new Promise((resolveRace) => {
        const child = spawn('node', [LANE, 'gate', 'acquire'], {
          cwd: SANDBOX,
          env: { ...process.env, LANE_ROOT: SANDBOX },
        });
        let out = '';
        child.stdout.on('data', (d) => (out += d));
        child.on('close', (code) => resolveRace({ code, out }));
      }),
  ),
);
const winners = racers.filter((x) => x.code === 0);
ok(`★8 个并发接管者里恰好 1 个成功`, winners.length === 1, `成功数=${winners.length}`);

const ownerRaw = existsSync(join(GATE_DIR, 'owner'))
  ? readFileSync(join(GATE_DIR, 'owner'), 'utf8')
  : '';
const tokenRaw = existsSync(TOKEN_FILE) ? readFileSync(TOKEN_FILE, 'utf8').trim() : '';
const ownerToken = ownerRaw.split('\n')[3] || '';
ok(
  '★接管后 owner 文件仍完整（没有被别人删掉证据）',
  ownerRaw.split('\n').length >= 4 && Boolean(ownerToken),
  JSON.stringify(ownerRaw),
);
ok(
  '★owner 令牌与本机令牌文件一致（胜者仍是持有者）',
  ownerToken === tokenRaw,
  `${ownerToken} vs ${tokenRaw}`,
);
ok('★没有留下偷取残留目录', !existsSync(`${GATE_DIR}.steal`));

/* ── 3. 持闸者还活着 ⇒ 不许接管 ─────────────────────────────────────── */

console.log('③ 持闸者还活着（在途车道 > 0）时，陈旧闸不得被接管');
ageGate(80);
setInFlightLanes(2);

r = run(['gate', 'status']);
s = json(r);
ok('status 报出在途车道数', s.inFlightLanes === 2, String(s.inFlightLanes));
ok('status 判定持闸者活着', s.holderAlive === true, JSON.stringify(s));
ok('★reclaimable=false（不许接管）', s.reclaimable === false, JSON.stringify(s));

r = run(['gate', 'reclaim', '--force', '--holder', 'itest']);
s = json(r);
ok('★reclaim --force 也拒绝接管活着的持闸者', s.ok === false, JSON.stringify(s));
ok('拒绝理由点名在途车道', String(s.reason).includes('在途车道'), String(s.reason));
ok('闸没有被抢走（owner 未变）', currentOwnerToken() === ownerToken, String(currentOwnerToken()));

/* ── 4. 持闸者已死 ⇒ 受管接管成功 ───────────────────────────────────── */

console.log('④ 持闸者已死时，reclaim 受管接管成功');
clearInFlightLanes();
r = run(['gate', 'reclaim', '--holder', 'itest']);
s = json(r);
ok('★reclaim 成功（退出码 0）', r.code === 0, `code=${r.code}`);
ok('★reclaim 标记 reclaimed=true', s.reclaimed === true, JSON.stringify(s));
ok('接管理由写明「持闸者已死」', String(s.reason).includes('已死'), String(s.reason));

/* ── 5. release 只删自己持有的闸 ────────────────────────────────────── */

console.log('⑤ release 只删自己持有的闸');
const myToken = currentOwnerToken();
// 把令牌文件改成别人的令牌 ⇒ 本机已不是持有者
writeFileSync(TOKEN_FILE, 'someone-else-token');
r = run(['gate', 'release']);
s = json(r);
ok('★release 拒绝删别人的闸（退出码 3）', r.code === 3, `code=${r.code}`);
ok('★闸还在', existsSync(GATE_DIR));

// 恢复自己的令牌 ⇒ 可以删
writeFileSync(TOKEN_FILE, myToken);
r = run(['gate', 'release']);
s = json(r);
ok('释放自己持有的闸（退出码 0）', r.code === 0, `code=${r.code}`);
ok('释放后闸目录已删', !existsSync(GATE_DIR));

/* ── 6. touch 只允许持有者续期 ──────────────────────────────────────── */

console.log('⑥ touch 的持有者校验');
r = run(['gate', 'acquire']);
ok('重新取到闸', r.code === 0, `code=${r.code}`);
r = run(['gate', 'touch']);
s = json(r);
ok('持有者 touch 成功（退出码 0）', r.code === 0, `code=${r.code}`);

writeFileSync(TOKEN_FILE, 'not-my-token');
r = run(['gate', 'touch']);
s = json(r);
ok('★非持有者 touch 被拒（退出码 3）', r.code === 3, `code=${r.code}`);

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

rmSync(SANDBOX, { recursive: true, force: true });
console.log(`\n结果：${passed} 通过，${failed} 失败`);
if (failed) {
  console.log('\n失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
