#!/usr/bin/env node
/**
 * 功能架构图页面生成器
 *
 * 做什么：
 *   1. 解析 docs/product/capability-blueprint/ 下 18 卷能力蓝图的「功能树」与
 *      「功能职责（L2）」表，抽出 L1 → L2 → L3 → L4 四级结构（职责 / 服务 / 路由 / 阶段）。
 *   2. 读台账 module-progress.md，取回每个 L4 的「状态」「证据」两列。
 *   3. 把结果写成单文件页面 docs/scratch/starcon-capability-map.html —— 零外部依赖，
 *      双击即可打开，四层可下钻，点模块出证据抽屉。
 *
 * 为什么要有这个脚本：
 *   页面里的 465 条模块记录原来是一次性手工誊抄的快照。台账每天都在动，
 *   手抄的页面必然漂移 —— 页面说 126 个已交付，台账说 210 个已交付时，
 *   看页面的人就被骗了。数据必须每次从蓝图 + 台账重新生成。
 *
 * 用法：
 *   node tools/module-progress/build-capability-map.mjs          # 生成页面
 *   node tools/module-progress/build-capability-map.mjs --check  # 只校验，不写文件
 *
 * 与 sync.mjs 的分工：
 *   sync.mjs 管台账（模块清单 + 状态 + 证据），本脚本只管页面（结构 + 渲染）。
 *   本脚本**不改台账**，只读；台账由 sync.mjs 维护。
 */

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const BLUEPRINT_DIR = join(ROOT, 'docs', 'product', 'capability-blueprint');
const LEDGER = join(BLUEPRINT_DIR, 'module-progress.md');
const PAGE = join(ROOT, 'docs', 'site', 'capability-map', 'index.html');

const STATUS = { DONE: '已交付', WIP: '进行中', TODO: '未开发' };
const VALID_STATUS = new Set(Object.values(STATUS));

/* ── 蓝图解析 ────────────────────────────────────────────────── */

/** 卷头：`# NN 系统名` + 定位 / 后端归属 / 前端入口 三行引言 */
function parseVolume(file) {
  const num = file.slice(0, 2);
  const raw = readFileSync(join(BLUEPRINT_DIR, file), 'utf8');
  const heading = raw.match(/^#\s+(\d{2})\s+(.+)$/m);
  const field = (label) => {
    const m = raw.match(new RegExp(`^>\\s+\\*\\*${label}\\*\\*：(.+)$`, 'm'));
    return m ? m[1].trim() : '';
  };
  return {
    file,
    num,
    system: heading ? heading[2].trim() : num,
    positioning: field('定位'),
    svc: field('后端归属'),
    fe: field('前端入口'),
    body: raw,
  };
}

/**
 * 抽出功能树里的 L2/L3/L4 节点。
 * 只在 ``` 代码块内匹配，避免正文里的交叉引用（如「下游 = 2.2.1.2」）被误当成模块。
 */
function extractTree(body) {
  const l2 = [];
  const l3 = [];
  const l4 = [];
  let curL2 = null;
  let curL3 = null;
  const line = /^[│\s]*[├└]──\s+(\d+(?:\.\d+){1,3})\s+(.+?)(?:\s+→\s+(\S+))?\s*$/;

  for (const raw of body.split('\n')) {
    const m = raw.match(line);
    if (!m) continue;
    const [, id, rest, route] = m;
    const depth = id.split('.').length;

    if (depth === 2) {
      curL2 = { id, name: rest.trim(), route: route ?? '', l3: [] };
      l2.push(curL2);
      curL3 = null;
    } else if (depth === 3) {
      curL3 = { id, name: rest.trim(), route: route ?? '', l4: [] };
      curL2?.l3.push(curL3);
    } else if (depth === 4) {
      curL3?.l4.push({
        id,
        name: rest.trim(),
        // L4 自带路由用自己的，否则继承所属 L3 的锚点路由
        route: route ?? curL3?.route ?? '',
        l3Id: curL3?.id ?? '',
      });
    }
  }
  return { l2, l3, l4 };
}

/**
 * 「功能职责（L2）」表 → L2 id ⇒ { duty, svc }。
 * 表格首列形如 `| **2.1 平台目录** | 描述… | channel（全局码表） |`。
 */
function parseL2Table(body) {
  const map = new Map();
  const row = /^\|\s*\*\*(\d+\.\d+)\s+(.+?)\*\*\s*\|([^|\n]*)\|([^|\n]*)\|/gm;
  let m;
  while ((m = row.exec(body)) !== null) {
    const [, id, name, duty, svc] = m;
    map.set(id, { name: name.trim(), duty: duty.trim(), svc: svc.trim() });
  }
  return map;
}

/* ── 台账解析 ────────────────────────────────────────────────── */

/**
 * 读台账，取回「状态」「证据」两列。
 *
 * ⚠️ 每一格必须是 `[^|\n]*` 而不是 `[^|]*`：`[^|]` 也匹配换行，
 * 所以某一行**漏掉结尾竖线**时，证据格会一路吃到下一行，把下一行整行吞进本行的捕获组 ——
 * 下一行于是拿不到自己的匹配，状态不被继承 → 被回落成「未开发」、证据被抹平成 `—`。
 */
function readLedger() {
  const map = new Map();
  let text;
  try {
    text = readFileSync(LEDGER, 'utf8');
  } catch {
    return map;
  }
  const row = /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|/gm;
  let m;
  while ((m = row.exec(text)) !== null) {
    const [, id, name, , phase, status, evidence] = m;
    const s = (status ?? '').trim();
    map.set(id.trim(), {
      name: name.trim(),
      phase: (phase ?? '').trim(),
      status: VALID_STATUS.has(s) ? s : STATUS.TODO,
      evidence: (evidence ?? '').trim(),
    });
  }
  return map;
}

/* ── 组装数据 ────────────────────────────────────────────────── */

/** 集成头 commit —— 页面上标出数据快照点，便于回答「这是什么时候的状态」 */
function integrationHead() {
  try {
    return execFileSync('git', ['rev-parse', '--short', 'integration/module-dev'], {
      cwd: ROOT,
      encoding: 'utf8',
    }).trim();
  } catch {
    return '';
  }
}

function build() {
  const files = readdirSync(BLUEPRINT_DIR)
    .filter((f) => /^\d{2}-.*\.md$/.test(f))
    .sort();
  const ledger = readLedger();

  const systems = [];
  const missing = [];

  for (const file of files) {
    const vol = parseVolume(file);
    const tree = extractTree(vol.body);
    const duties = parseL2Table(vol.body);

    const l2 = tree.l2.map((n) => {
      const duty = duties.get(n.id);
      const l4 = (tree.l3.find((x) => x.id.startsWith(`${n.id}.`))?.l4 ?? []).length;
      return {
        id: n.id,
        name: n.name,
        duty: duty?.duty ?? '',
        svc: duty?.svc ?? '',
        route: n.route,
        l3: n.l3.map((c) => ({
          id: c.id,
          name: c.name,
          route: c.route,
          l4: c.l4.map((m) => {
            const row = ledger.get(m.id);
            if (!row) missing.push(m.id);
            return {
              id: m.id,
              name: m.name,
              route: m.route,
              stage: row?.phase || '',
              state: row?.status ?? STATUS.TODO,
              evidence: row?.evidence && row.evidence !== '—' ? row.evidence : '—',
            };
          }),
        })),
        delivered: 0,
        total: l4,
      };
    });

    // 交付数从 L4 实际状态算，不单独维护第二份计数
    for (const f of l2) {
      f.delivered = f.l3.reduce(
        (a, c) => a + c.l4.filter((m) => m.state === STATUS.DONE).length,
        0,
      );
      f.total = f.l3.reduce((a, c) => a + c.l4.length, 0);
    }

    systems.push({
      id: vol.num,
      name: vol.system,
      title: `${vol.num} ${vol.system}`,
      positioning: vol.positioning,
      svc: vol.svc,
      fe: vol.fe,
      l2,
    });
  }

  const l2Count = systems.reduce((a, s) => a + s.l2.length, 0);
  const l3Count = systems.reduce((a, s) => a + s.l2.reduce((b, f) => b + f.l3.length, 0), 0);

  return { systems, l2Count, l3Count, missing };
}

/* ── 写页面 ──────────────────────────────────────────────────── */

/** JSON 里出现 U+2028 / U+2029 会让老 JS 解析器把整行截断 */
function jsSafe(json) {
  return json.replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}

function render(data) {
  const shell = readFileSync(PAGE, 'utf8');
  const tally = { [STATUS.DONE]: 0, [STATUS.WIP]: 0, [STATUS.TODO]: 0 };
  for (const s of data.systems)
    for (const f of s.l2)
      for (const c of f.l3)
        for (const m of c.l4) tally[m.state] = (tally[m.state] ?? 0) + 1;
  const total = Object.values(tally).reduce((a, b) => a + b, 0);

  let out = shell;

  // ① 数据块：整行替换
  //    ⚠️ 必须用**函数**形式做替换：证据列里含 `$`（SQL 里的 `$1` 之类），
  //    字符串形式的替换会把 `$&` / `$'` 当成特殊模式 —— `$'` 会把匹配点之后的整段
  //    原文再插一遍，页面直接被复制成好几份。用函数形式，替换串只当字面量。
  const payload = jsSafe(JSON.stringify(data));
  if (!/^const DATA = .*;$/m.test(out)) {
    throw new Error('页面里找不到 `const DATA = ...;` 数据行，页面结构变了，请手工对齐');
  }
  out = out.replace(/^const DATA = .*;$/m, () => `const DATA = ${payload};`);

  // ② 无 JS 时的兜底数字（脚本启动后会用真实值覆盖）
  const subs = [
    [/id="sTotal">\d+</, `id="sTotal">${total}<`],
    [/id="sOk">\d+</, `id="sOk">${tally[STATUS.DONE]}<`],
    [/id="sProg">\d+</, `id="sProg">${tally[STATUS.WIP]}<`],
    [/id="sTodo">\d+</, `id="sTodo">${tally[STATUS.TODO]}<`],
  ];
  for (const [re, to] of subs) out = out.replace(re, () => to);

  // ③ 正文里的固定数字（模块总数 / 功能域数 / 系统数）
  const prose = [
    [/\d+ 个 L4 模块按依赖深度/, `${total} 个 L4 模块按依赖深度`],
    [/\d+ 个最小单元/, `${total} 个最小单元`],
    [/\d+ 个业务能力域/, `${data.l2Count} 个业务能力域`],
    [/\d+ 个业务系统/, `${data.systems.length} 个业务系统`],
  ];
  for (const [re, to] of prose) out = out.replace(re, () => to);

  return out;
}

const check = process.argv.includes('--check');
const data = {
  generatedAt: new Date().toISOString(),
  integrationHead: integrationHead(),
  ...build(),
};

const next = render(data);

if (check) {
  const current = existsSync(PAGE) ? readFileSync(PAGE, 'utf8') : '';
  // --check 判的是「页面内容与蓝图/台账是否同步」，不是「生成时间是否相同」。
  // 生成时间每次跑都变，把它算进判据的话 --check 永远是红的；
  // 集成头同理 —— 集成头一动就要求重新生成页面，那是无意义的噪音。
  const normalize = (html) =>
    html
      .replace(/"generatedAt":"[^"]*"/, '"generatedAt":"X"')
      .replace(/"integrationHead":"[^"]*"/, '"integrationHead":"X"');
  if (normalize(current) === normalize(next)) {
    console.log('✓ 页面与蓝图/台账一致');
    process.exit(0);
  }
  console.error('✗ 页面与蓝图/台账不一致，运行：node tools/module-progress/build-capability-map.mjs');
  process.exit(1);
}

mkdirSync(dirname(PAGE), { recursive: true });
writeFileSync(PAGE, next);

const tally = { [STATUS.DONE]: 0, [STATUS.WIP]: 0, [STATUS.TODO]: 0 };
for (const s of data.systems)
  for (const f of s.l2)
    for (const c of f.l3)
      for (const m of c.l4) tally[m.state] = (tally[m.state] ?? 0) + 1;
console.log(
  `✓ 生成 ${data.systems.length} 系统 / ${data.l2Count} 功能域 / ${data.l3Count} 子功能\n` +
    `  已交付 ${tally[STATUS.DONE]} · 进行中 ${tally[STATUS.WIP]} · 未开发 ${tally[STATUS.TODO]}\n` +
    `  → ${PAGE.replace(ROOT + '/', '')}`,
);
if (data.missing.length) {
  console.log(`  ⚠ 台账里没有的模块 ${data.missing.length} 个：${data.missing.slice(0, 8).join(', ')}`);
}
