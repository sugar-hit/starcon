#!/usr/bin/env node
/**
 * 集成分支 → main 的「功能增量」摘要器
 *
 * 做什么：
 *   比对两个 ref 上的功能进度台账，输出**新交付 / 新推进**的模块，并按业务系统分组，
 *   附一条可直接用作 Pull Request 标题的候选。
 *
 * 为什么需要它：
 *   集成分支一次可能带来几十上百个提交，提交信息是工程视角的
 *   （`docs(batch-28): 11.2.3.3 / 7.8.2.1 ...`）。直接读提交信息写 PR 描述，
 *   必然写成「合了 N 个提交 / 改了 M 个文件」这种读者不关心的工程流水账。
 *   台账记的是**功能**（模块 ID + 功能名 + 交付状态），它是业务视角的真相源，
 *   所以「本次合入了什么功能」应该从台账增量里出，而不是从提交信息里出。
 *
 * 用法：
 *   node tools/module-progress/merge-summary.mjs --base upstream/main --head upstream/integration/module-dev
 *   node tools/module-progress/merge-summary.mjs --base upstream/main --head upstream/integration/module-dev --quiet
 *
 * 输出是给人读的文本，同时被解析成结构化片段供 PR 描述直接引用。
 * `--quiet` 只输出候选标题（一行），便于脚本化取用。
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const BLUEPRINT_DIR = join(ROOT, 'docs', 'product', 'capability-blueprint');
const LEDGER_PATH = 'docs/product/capability-blueprint/module-progress.md';

/** 交付状态词表 —— 与 sync.mjs 保持一致，不要新增 */
export const DONE = '已交付';
export const WIP = '进行中';
export const TODO = '未开发';

/**
 * 台账行解析。
 *
 * ⚠️ 每一格必须用 `[^|\n]*` 而不是 `[^|]*`：字符类取反同样匹配换行，
 * 所以某一行漏掉结尾竖线时，它的最后一格会一路吃到下一行，把邻行整行吞进捕获组，
 * 邻行于是拿不到自己的匹配 —— 那个模块会被误判成「没交付」。
 * 不跨行之后，畸形行只会干净地匹配失败（自己回落），不再伤及邻行。
 */
export function parseLedger(text) {
  const map = new Map();
  const row =
    /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|/gm;
  let m;
  while ((m = row.exec(text)) !== null) {
    map.set(m[1], {
      name: m[2].trim(),
      route: m[3].trim(),
      phase: m[4].trim(),
      status: m[5].trim(),
      evidence: m[6].trim(),
    });
  }
  return map;
}

/**
 * 台账增量：谁从「未开发 / 进行中」变成了「已交付 / 进行中」。
 *
 * 只认**状态跃迁**，不认模块新增 —— 新增但仍未开发的模块没有功能可写。
 */
export function diffLedger(base, head) {
  const delivered = [];
  const started = [];
  for (const [id, h] of head) {
    const b = base.get(id);
    const before = b ? b.status : TODO;
    if (h.status === DONE && before !== DONE) {
      delivered.push({ id, name: h.name, route: h.route, phase: h.phase, from: before });
    } else if (h.status === WIP && before === TODO) {
      started.push({ id, name: h.name, route: h.route, phase: h.phase, from: before });
    }
  }
  return { delivered, started };
}

/**
 * 按卷号分组，卷名来自蓝图卷标题。
 *
 * ⚠️ 卷号是模块 ID 的**第一段**（`9.2.1.1` → `9`），**不是前两位字符** ——
 * 台账里的 ID 不补零，所以 `slice(0, 2)` 会切出 `'9.'`。卷文件是补零的（`09-*.md`），
 * 所以查卷名时按两位补零回查。
 */
export function groupByVolume(items, volumeNames) {
  const groups = new Map();
  for (const item of items) {
    const vol = item.id.split('.')[0];
    if (!groups.has(vol)) groups.set(vol, []);
    groups.get(vol).push(item);
  }
  return [...groups.entries()]
    .sort((a, b) => Number(a[0]) - Number(b[0]))
    .map(([vol, list]) => ({
      volume: vol,
      name: volumeNames.get(vol.padStart(2, '0')) ?? volumeNames.get(vol) ?? `第 ${vol} 卷`,
      list,
    }));
}

/**
 * 候选标题。
 *
 * 规则：只讲功能，不讲工程 —— 不出现提交数、文件数、分支名、脚本名。
 * 一项交付时直接用功能名；多项时取前两项加「等 N 项能力交付」。
 */
export function suggestTitle({ delivered, started }) {
  if (delivered.length === 1) return `feat: ${delivered[0].name}交付`;
  if (delivered.length === 2) return `feat: ${delivered[0].name}与${delivered[1].name}交付`;
  if (delivered.length > 2) {
    return `feat: ${delivered[0].name}、${delivered[1].name}等 ${delivered.length} 项能力交付`;
  }
  if (started.length === 1) return `feat: ${started[0].name}能力推进`;
  if (started.length > 1) return `feat: ${started[0].name}等 ${started.length} 项能力推进`;
  return 'chore: 同步集成分支最新进度';
}

/** 蓝图卷号 → 系统名，例如 09 → 订单与履约系统 */
export function readVolumeNames(dir = BLUEPRINT_DIR) {
  const map = new Map();
  for (const file of readdirSync(dir)) {
    if (!/^\d{2}-.*\.md$/.test(file)) continue;
    const num = file.slice(0, 2);
    const heading = readFileSync(join(dir, file), 'utf8').match(/^#\s+(\d{2})\s+(.+)$/m);
    if (heading) map.set(num, heading[2].trim());
  }
  return map;
}

/**
 * 读某个 ref 上的台账。
 *
 * ⚠️ 两个坑：
 *   1. **maxBuffer 必须显式放大**。台账含「证据」列，实测单文件已到 **1.2 MB**
 *      （`upstream/main~3` 是 1,034,003 字节 —— 刚好卡在 1 MB 默认值下面；
 *      `upstream/integration/module-dev` 是 1,276,189 字节 —— 越界）。
 *      越界时 `execFileSync` 抛 **ENOBUFS**，而且**抛在 stdout 管道上**，
 *      看起来像「读不出来」，实际上只是超了默认缓冲。
 *   2. **读失败必须响亮失败，不能静默返回空表**。静默返回空表会让
 *      「读不到 head」退化成「head 里没有新交付」—— 摘要说「本次没有新的功能交付」，
 *      而真相是「压根没读到 head」。**不报错的口径错误比崩溃更危险。**
 */
export function readLedgerAt(ref, cwd = ROOT) {
  let text;
  try {
    text = execFileSync('git', ['show', `${ref}:${LEDGER_PATH}`], {
      cwd,
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (err) {
    throw new Error(
      `读不到 ${ref} 上的台账：${err.message?.split('\n')[0] ?? err}\n` +
        `（若报 ENOBUFS，说明该 ref 的台账超过 32MB；若报 unknown revision，说明 ref 不存在，\n` +
        `  先跑 git fetch upstream --prune 再重试）`,
    );
  }
  const map = parseLedger(text);
  if (map.size === 0) {
    throw new Error(`${ref} 上的台账解析出 0 行 —— 台账格式可能已变，拒绝给出「无增量」的结论`);
  }
  return map;
}

/** 解析命令行参数 */
export function parseArgs(argv) {
  const out = { base: 'upstream/main', head: 'upstream/integration/module-dev', quiet: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--base') out.base = argv[++i];
    else if (a === '--head') out.head = argv[++i];
    else if (a === '--quiet') out.quiet = true;
  }
  return out;
}

/** 组装最终输出文本 */
export function renderSummary({ base, head, delivered, started, volumeNames }) {
  const lines = [];
  lines.push(`=== 功能增量（${base} → ${head}）===`);
  lines.push('');

  if (delivered.length === 0 && started.length === 0) {
    lines.push('本次没有新的功能交付或推进。');
  } else {
    if (delivered.length > 0) {
      lines.push(`新增交付 ${delivered.length} 项：`);
      for (const g of groupByVolume(delivered, volumeNames)) {
        lines.push(`  [${g.volume} ${g.name}]`);
        for (const item of g.list) lines.push(`    - ${item.name}（${item.id}）`);
      }
      lines.push('');
    }
    if (started.length > 0) {
      lines.push(`新增进行中 ${started.length} 项：`);
      for (const g of groupByVolume(started, volumeNames)) {
        lines.push(`  [${g.volume} ${g.name}]`);
        for (const item of g.list) lines.push(`    - ${item.name}（${item.id}）`);
      }
      lines.push('');
    }
  }

  lines.push(`建议标题：${suggestTitle({ delivered, started })}`);
  return lines.join('\n');
}

export function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  const base = readLedgerAt(args.base);
  const head = readLedgerAt(args.head);
  const { delivered, started } = diffLedger(base, head);
  const volumeNames = readVolumeNames();

  if (args.quiet) {
    console.log(suggestTitle({ delivered, started }));
    return { delivered, started, title: suggestTitle({ delivered, started }) };
  }

  console.log(renderSummary({ ...args, delivered, started, volumeNames }));
  return { delivered, started, title: suggestTitle({ delivered, started }) };
}

const invokedDirectly =
  process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1].replace(/\\/g, '/');
if (invokedDirectly) main();
