#!/usr/bin/env node
/**
 * git merge driver：按「键」做**三方**求并，而不是按行求并
 *
 * 为什么需要：并行车道各自在独立 worktree 里开发，会同时改到同一批文件。
 * 其中三类文件是纯追加式生成物 —— 车道 A 加自己的类型/端点，车道 B 加自己的，
 * 行号完全对不上，行级三方合并必然冲突，冲突了就得人工去解，流水线就停了。
 *
 * 这三类文件各自都有稳定的「键」，按键求并就是无损合并：
 *   packages/api-contracts/src/*.ts            键 = 导出的类型名
 *   packages/api-contracts/src/*.openapi.json  键 = paths 的路径 / components.schemas 的名字
 *   docs/.../module-progress.md                键 = 行首的模块 ID
 *
 * ── 踩过的坑：这里必须是三方比较，不能是「theirs 优先」 ──────────────────
 * 第一版写的是「键撞车时一律以 theirs 为准」。台账合并时这直接把车道 A 的交付
 * 记录**静默改回未开发** —— 因为车道 B 的台账副本里，3.5.2.2 那一行仍是它没动过时
 * 从基线继承下来的「未开发」，union 把它当成「B 也改了 3.5.2.2」，于是覆盖掉了 A。
 * 静默丢交付记录比报冲突严重得多。
 *
 * 正确做法是逐键做三方比较（base 才是「谁真的改了它」的裁判）：
 *   只有一边相对 base 变了 → 取变的那边（这是绝大多数情况，也是并行的价值所在）
 *   两边都变了同���个键   → 取 theirs，并把键名记进 CONFLICT_KEYS，让 git 报出来
 *
 * 用法（由 .gitattributes + merge.<name>.driver 配置自动调用）：
 *   node merge-union.mjs %O %A %B
 * 输出写回 %A。撞车时打印 CONFLICT_KEYS 并让 git 知道，人能看见而不是被静默吃掉。
 */

import { readFileSync, writeFileSync } from 'node:fs';

const [, , basePath, oursPath, theirsPath] = process.argv;
if (!basePath || !oursPath || !theirsPath) {
  process.stderr.write('usage: merge-union.mjs <base> <ours> <theirs>\n');
  process.exit(1);
}

const read = (p) => {
  try {
    return readFileSync(p, 'utf8');
  } catch {
    return null;
  }
};

const ours = read(oursPath);
const theirs = read(theirsPath);
const base = read(basePath);

if (ours === null || theirs === null) {
  process.stderr.write('merge-union: 读不到待合并文件\n');
  process.exit(1);
}

/** 两边完全一样，没有可合并的东西 */
if (ours === theirs) {
  writeFileSync(oursPath, ours);
  process.exit(0);
}

const conflictKeys = [];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * 三方按键合并。base 为 null 表示「这个键在基线里不存在」。
 * 返回合并后的条目，顺序 = 先 ours 的顺序，再补 theirs 独有的。
 */
function mergeEntries(baseMap, oursMap, theirsMap, label) {
  const out = new Map();
  const order = [...oursMap.keys()];
  for (const k of theirsMap.keys()) if (!order.includes(k)) order.push(k);

  for (const k of order) {
    const o = oursMap.get(k);
    const t = theirsMap.get(k);
    const b = baseMap.get(k);

    if (!theirsMap.has(k)) {
      out.set(k, o); // 只有我们这边有（对方删了的不自动跟随，删行交给人工）
      continue;
    }
    if (!oursMap.has(k)) {
      out.set(k, t);
      continue;
    }
    if (same(t, o)) {
      out.set(k, t);
      continue;
    }
    if (baseMap.has(k)) {
      if (same(o, b)) {
        out.set(k, t); // 只有 theirs 相对基线变了
        continue;
      }
      if (same(t, b)) {
        out.set(k, o); // 只有 ours 相对基线变了（并行交付的常见情况）
        continue;
      }
    }
    // 两边都动了同一个键：取 theirs，但必须报出来，不能静默
    conflictKeys.push(`${label}:${k}`);
    out.set(k, t);
  }
  return order.map((k) => [k, out.get(k)]);
}

/* ── 1. 台账 markdown：一行一个模块，键 = 行首的模块 ID ───────────────── */
const LEDGER_KEY = /^\|\s*(\d+(?:\.\d+)+)\s*\|/;

if (LEDGER_KEY.test(ours) && LEDGER_KEY.test(theirs)) {
  const toMap = (text) => {
    const map = new Map();
    let anon = 0;
    for (const line of text.split('\n')) {
      const m = LEDGER_KEY.exec(line);
      if (m) {
        map.set(m[1], line);
      } else {
        map.set(`\u0000anon${anon++}`, line); // 表头/空行等非模块行，按出现顺序对齐
      }
    }
    return map;
  };

  const merged = mergeEntries(
    base === null ? new Map() : toMap(base),
    toMap(ours),
    toMap(theirs),
    '台账',
  );
  writeFileSync(oursPath, `${merged.map(([, v]) => v).join('\n')}`);
  if (conflictKeys.length) process.stderr.write(`CONFLICT_KEYS ${conflictKeys.join(' ')}\n`);
  process.exit(0);
}

/* ── 2. 契约 .ts：一行一个导出，键 = 导出的类型名 ─────────────────────── */
const TS_KEY = /^export\s+(?:type|interface|const|enum)\s+([A-Za-z0-9_$]+)/;

if (TS_KEY.test(ours) && TS_KEY.test(theirs)) {
  const toMap = (text) => {
    const map = new Map();
    const head = [];
    for (const line of text.split('\n')) {
      const m = TS_KEY.exec(line);
      if (m) map.set(m[1], line);
      else head.push(line);
    }
    return { map, head };
  };

  const b = base === null ? { map: new Map(), head: [] } : toMap(base);
  const o = toMap(ours);
  const t = toMap(theirs);
  const merged = mergeEntries(b.map, o.map, t.map, '契约');
  writeFileSync(oursPath, `${merged.map(([, v]) => v).join('\n')}\n${o.head.join('\n')}`);
  if (conflictKeys.length) process.stderr.write(`CONFLICT_KEYS ${conflictKeys.join(' ')}\n`);
  process.exit(0);
}

/* ── 3. OpenAPI json：paths 与 components.schemas 等按对象键三方求并 ───── */
try {
  const a = JSON.parse(ours);
  const b = JSON.parse(theirs);
  const g = base === null ? {} : JSON.parse(base);

  const mergeObj = (o, t, baseObj, label) => {
    const asMap = (v) =>
      v && typeof v === 'object' && !Array.isArray(v) ? new Map(Object.entries(v)) : new Map();
    const merged = mergeEntries(asMap(baseObj), asMap(o), asMap(t), label);
    return Object.fromEntries(merged.map(([k, v]) => [k, v]));
  };

  if (a.paths || b.paths) a.paths = mergeObj(a.paths, b.paths, g.paths, 'paths');
  if (a.components || b.components) {
    // 注意：components 本身要浅合并，但 components.schemas 要深合并。
    // 早期版本先做了 {...a.components, ...b.components} 把 a.schemas 直接顶掉，
    // 导致车道 A 的 schema 静默丢失 —— 深合并必须在覆盖之前取到两边的原值。
    const ac = a.components ?? {};
    const bc = b.components ?? {};
    const gc = g.components ?? {};
    const out = { ...ac };
    for (const k of new Set([...Object.keys(ac), ...Object.keys(bc)])) {
      if (['schemas', 'responses', 'parameters', 'examples', 'requestBodies'].includes(k)) {
        out[k] = mergeObj(ac[k], bc[k], gc[k], `components.${k}`);
      } else {
        out[k] = bc[k] ?? ac[k];
      }
    }
    a.components = out;
  }

  writeFileSync(oursPath, JSON.stringify(a, null, 2));
  if (conflictKeys.length) process.stderr.write(`CONFLICT_KEYS ${conflictKeys.join(' ')}\n`);
  process.exit(0);
} catch {
  /* 不是 JSON，落到下面的兜底 */
}

/* ── 兜底：行级去重并集。解析不了语义时至少不丢内容，但要报出来 ───────── */

/*
 * 台账（module-progress.md）不能按整行去重，必须按**模块 ID** 求并集。
 *
 * 为什么：台账里同一模块的两行只在状态与证据上不同，整行文本不一样，
 * 按整行去重会把两行都留下 → 台账出现重复行 → sync.mjs 的 map.set 是
 * 后写覆盖，车道带来的占位行（未开发 | —）排在后面就赢 → 已交付模块被
 * 读成未开发 → plan 重派 → 撞已占用的 Flyway 版本号。
 *
 * 2026-10-07 在 40 分钟内因此损坏三次（17.5.1.1、17.5.3.2 两次并入）。
 * 胜负规则与人工修复时一致，也与 sync.mjs 的读取口径一致：
 *   1. 证据非 `—` 优先（占位行证据是 `—`，天然垫底）
 *   2. 再比状态：已交付 > 进行中 > 未开发
 *   3. 再比证据长度（同状态时取信息更全的那条）
 */
const LEDGER_ROW = /^\| (\d+\.\d+\.\d+\.\d+) \|/;
const STATUS_RANK = { 已交付: 3, 进行中: 2, 未开发: 1 };
const cell = (line, i) => (line.split('|')[i] ?? '').trim();

const betterRow = (a, b) => {
  const ea = cell(a, 6);
  const eb = cell(b, 6);
  const ha = ea && ea !== '—' ? 1 : 0;
  const hb = eb && eb !== '—' ? 1 : 0;
  if (ha !== hb) return ha > hb;
  const ra = STATUS_RANK[cell(a, 5)] ?? 0;
  const rb = STATUS_RANK[cell(b, 5)] ?? 0;
  if (ra !== rb) return ra > rb;
  return ea.length > eb.length;
};

const seen = new Set(); // 非台账行仍按整行去重
const rowAt = new Map(); // 模块 ID -> 在 outLines 中的下标
const outLines = [];
let ledgerRowsMerged = 0;

for (const line of `${ours}\n${theirs}`.split('\n')) {
  const m = LEDGER_ROW.exec(line);
  if (!m) {
    if (seen.has(line)) continue;
    seen.add(line);
    outLines.push(line);
    continue;
  }
  const id = m[1];
  if (!rowAt.has(id)) {
    rowAt.set(id, outLines.length);
    outLines.push(line);
    continue;
  }
  ledgerRowsMerged += 1;
  const i = rowAt.get(id);
  if (betterRow(line, outLines[i])) outLines[i] = line;
}
writeFileSync(oursPath, outLines.join('\n'));
process.stderr.write('merge-union: 走了行级兜底并集，请人工确认这个文件改对了\n');
if (ledgerRowsMerged > 0) {
  process.stderr.write(
    `merge-union: 台账按模块 ID 求并集，折叠了 ${ledgerRowsMerged} 行同 ID 记录\n`,
  );
}
process.exit(0);
