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

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [, , basePath, oursPath, theirsPath] = process.argv;
if (!basePath || !oursPath || !theirsPath) {
  process.stderr.write('usage: merge-union.mjs <base> <ours> <theirs>\n');
  process.exit(1);
}

/**
 * JSON 快照的序列化器 —— 与 codegen 共用同一个 json.dumps 路径（见 json-format.py）。
 * 用 import.meta.url 定位，不依赖进程 cwd（git 跑 merge driver 时 cwd 是仓库根，
 * 但集成脚本可能从别处调用它）。
 */
const JSON_FORMATTER = join(dirname(fileURLToPath(import.meta.url)), 'json-format.py');

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

/*
 * 注意：**没有**「两边完全一样就直接落盘」的全局短路。
 * 契约分支与 JSON 分支都必须走一遍，好把输出**规范化**成 codegen 的形状 ——
 * 否则一份「两边一样但排版是坏的」的契约文件会原样留在仓库里，
 * CI 的「重新生成后无差异」检查照样红。短路只留在最下面的兜底分支里。
 */

const conflictKeys = [];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * 用 codegen 同款的 json.dumps 序列化路径把合并结果写成字节（见 json-format.py）。
 *
 * 为什么不在 JS 里手搓：Python 的 repr 与 JS 的 Number.toString 在指数边界上
 * 不同（1e-6：Python 写 1e-06，JS 写 0.000001；1e-8：Python 写 1e-08，JS 写 1e-8），
 * 键序规则与末尾换行也不同。手搓 = 两份真相 = 迟早对不上。
 *
 * python3 不可用 / 脚本执行失败时返回 null —— 调用方必须**保留原文件内容不动**
 * 并大声报出来：宁可这次合并不生效，也不要写出一个与生成器不一致的坏文件。
 */
function formatJsonSnapshot(value) {
  const result = spawnSync('python3', [JSON_FORMATTER], {
    input: JSON.stringify(value),
    encoding: 'utf8',
    // 默认 1MB 装不下 trade/channel 的快照（1.4MB~1.6MB），必须放开。
    maxBuffer: 256 * 1024 * 1024,
  });
  if (result.error) {
    process.stderr.write(`merge-union: 调用 python3 失败：${result.error.message}\n`);
    return null;
  }
  if (result.status !== 0) {
    process.stderr.write(`merge-union: json-format.py 退出码 ${result.status}\n`);
    if (result.stderr) process.stderr.write(result.stderr);
    return null;
  }
  return result.stdout;
}

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
/*
 * 这里**故意不加 `m` 标志**：台账首行是 `# 功能蓝图交付进度台账`，
 * 没有 `m` 时 LEDGER_KEY.test(整文件) 恒为 false，于是台账一律落到下面的兜底，
 * 而兜底里恰好有一套更经得起考验的台账求并（betterRow：证据优先 → 状态优先 → 证据长度）。
 * 2026-10-07 那 40 分钟里台账被损坏三次，都是因为按整行去重；
 * 宁可让台账走那条久经考验的路，也不要为了「统一」把它换到没验证过的路径上。
 */
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
/*
 * ⚠ 这里**必须有 `m` 标志**（2026-10-13 修复）。
 *
 * 契约是 codegen 产物，首行固定是
 *   `// Generated by tools/codegen/catalog-contracts.py ... DO NOT EDIT.`
 * 而 `^` 在没有 `m` 时只锚定**整段字符串的第 0 个字符**，
 * 于是 `TS_KEY.test(整文件)` 恒为 false —— 语义分支**从来没被走到过**，
 * 每一次契约并入都静默落到下面的「行级去重并集」兜底。
 *
 * 后果不是「合并失败」而是「合并成功但文件是坏的」：两条车道各自产出一个
 * `export type ScopeOption = ...`（形状还不一样），行级去重认为它们是两行、
 * 两条都留下 ⇒ TypeScript `Duplicate identifier` ⇒ typecheck 红。
 * 而 vitest 不做类型检查，所以 `pnpm test` 全绿、只有 typecheck 炸 ——
 * 这种「测不出」的损坏正是组批模式要抓的东西。
 */
const TS_KEY = /^export\s+(?:type|interface|const|enum)\s+([A-Za-z0-9_$]+)/m;

/*
 * ── 输出形状必须与 codegen 的 render_types() **逐字节一致** ─────────────────
 * CI 的契约闸是「重新生成后 git diff 必须为空」，所以驱动写出的字节必须等于
 * 生成器写出的字节。生成器那边（tools/codegen/catalog-contracts.py:render_types）是：
 *   第 1 行 = 横幅，然后按类型名字典序的 export 行，每行一个 \n，**一个空行都没有**。
 * 这里曾经有三处偏差，每一处都会让契约闸变红：
 *   1. 横幅被挪到**末行**（head 被拼在导出之后）—— 现在横幅回到首行；
 *   2. 类型顺序 = 三方求并的**插入序**，不是字典序 —— 现在输出层按类型名排序；
 *   3. 空行被原样保留 —— 现在空行直接丢（生成器不产出空行）。
 *
 * 排序键用**默认的 Array.prototype.sort()**（UTF-16 码元序，等价 Python 的 sorted()），
 * 禁止 localeCompare：本地化序对大小写 / 下划线 / 数字的先后与码元序不同，
 * 同一份内容会排出两种顺序 ⇒ 字节不一致。
 *
 * mergeEntries() 的返回值顺序（ours 插入序 + theirs 独有键）**不能改** ——
 * 它被台账与 JSON 路径共用；排序只加在这一层的**输出**上。
 */
if (TS_KEY.test(ours) && TS_KEY.test(theirs)) {
  const toMap = (text) => {
    const map = new Map();
    const banner = [];
    for (const line of text.split('\n')) {
      const m = TS_KEY.exec(line);
      if (m) map.set(m[1], line);
      // 非 export 行分两类：横幅等**前导块**要保留（放到首行，保持相对顺序），
      // 空行**不要输出**（生成器一个空行都不产生）。
      else if (line.trim() !== '') banner.push(line);
    }
    return { map, banner };
  };

  const b = base === null ? { map: new Map(), banner: [] } : toMap(base);
  const o = toMap(ours);
  const t = toMap(theirs);
  const merged = mergeEntries(b.map, o.map, t.map, '契约');

  const byKey = new Map(merged);
  const keys = [...byKey.keys()].sort(); // 默认排序 = UTF-16 码元序 = Python sorted()
  const body = keys.map((k) => byKey.get(k));
  const head = o.banner.length ? o.banner : t.banner.length ? t.banner : b.banner;
  writeFileSync(oursPath, `${[...head, ...body].join('\n')}\n`);
  if (conflictKeys.length) process.stderr.write(`CONFLICT_KEYS ${conflictKeys.join(' ')}\n`);
  process.exit(0);
}

/* ── 3. OpenAPI json：paths 与 components.schemas 等按对象键三方求并 ───── */
let specs = null;
try {
  specs = {
    a: JSON.parse(ours),
    b: JSON.parse(theirs),
    g: base === null ? {} : JSON.parse(base),
  };
} catch {
  specs = null; /* 不是 JSON，落到下面的兜底 */
}

if (specs) {
  const { a, b, g } = specs;

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

  /*
   * 落盘必须与 codegen 写快照的路径**逐字节相同**，否则每并入一次 .openapi.json
   * 就让 CI 的「重新生成后无差异」检查红一次。JS 的 JSON.stringify 做不到：
   *   数字：1e-6 → 0.000001（Python 是 1e-06）、1e-8 → 1e-8（Python 是 1e-08）
   *   键序：插入序（codegen 那边是 sort_keys=True 的字典序）
   *   末尾：不补换行（codegen 补）
   * 所以把合并结果交给 python3 tools/module-progress/json-format.py ——
   * 同一个 json.dumps 序列化路径，只有一份真相。
   */
  const text = formatJsonSnapshot(a);
  if (text === null) {
    // python3 不可用 / 格式化失败：**保留原文件内容不动**。
    // 宁可这次合并不生效（git 报冲突，人来看），也不要写出一个与生成器不一致的坏文件。
    process.stderr.write(
      'merge-union: python3 不可用或 json-format.py 执行失败 —— 已保留原文件内容不动，本次合并不生效\n',
    );
    process.exit(1);
  }
  writeFileSync(oursPath, text);
  if (conflictKeys.length) process.stderr.write(`CONFLICT_KEYS ${conflictKeys.join(' ')}\n`);
  process.exit(0);
}

/* ── 兜底：行级去重并集。解析不了语义时至少不丢内容，但要报出来 ───────── */

/*
 * 两边完全一样 → 没什么可合并的，原样落盘。
 * 短路只放在这里：契约分支与 JSON 分支必须走一遍，好把输出规范化成 codegen 的形状。
 */
if (ours === theirs) {
  writeFileSync(oursPath, ours);
  process.exit(0);
}

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
