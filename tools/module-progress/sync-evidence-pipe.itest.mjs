#!/usr/bin/env node
/**
 * 台账同步器（`sync.mjs`）的**证据格竖线**语义测试。
 *
 * 背景（2026-10-30 实测事故）：13.6.1.2 那行的**证据格里有一根字面竖线**
 * （作者写成了「`PUT|DELETE …`」，本意是分隔两个 HTTP 动词）。
 *
 * 旧版 `readExisting()` 的证据格捕获组是 `([^|\n]*)` —— 表达不了「证据格里可以有竖线」，
 * 于是它只截到第一根竖线就收工 ⇒ **每次 sync 写盘都把证据格静默截短**：
 *
 *   6111 字 → 2100 字（09:15 那轮），**丢掉了端点后半段 + 前端 + 测试 + 缺口四段**。
 *
 * 最阴的地方：截断后的文件再跑 `sync.mjs --check` 是**绿的** ⇒ 丢失不可见、CI 也看不见。
 * （竖线还多切出一列，markdown 表格渲染同样错位。）
 *
 * 本测试锁住三件事：
 *   1. 证据格含字面竖线时，**写盘不得截断**（round-trip 必须原样保留）。
 *   2. `sync.mjs --check` 必须**判红并点名那个模块 ID**（不许静默）。
 *   3. 证据格不含竖线时，`--check` 绿、写盘是幂等的（no-op）。
 *
 * 用法：node tools/module-progress/sync-evidence-pipe.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const SYNC = join(HERE, 'sync.mjs');
const SANDBOX = join(resolve(HERE, '..', '..'), '.tmp', `itest-sync-pipe-${process.pid}`);
const BP = join(SANDBOX, 'docs', 'product', 'capability-blueprint');

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

/** 造一份最小蓝图（1 卷 / 2 个 L4 模块） + 一份台账 */
function seed(evidence) {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(BP, { recursive: true });
  writeFileSync(
    join(BP, '01-demo.md'),
    [
      '# 01 演示系统',
      '',
      '```',
      '└── 1.1 能力',
      '    ├── 1.1.1 子能力',
      '    │   ├── 1.1.1.1 甲模块',
      '    │   └── 1.1.1.2 乙模块',
      '```',
      '',
    ].join('\n'),
    'utf8',
  );
  writeFileSync(
    join(BP, 'module-progress.md'),
    [
      '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |',
      '| --- | --- | --- | --- | --- | --- |',
      `| 1.1.1.1 | 甲模块 | — | D7 | 已交付 | ${evidence} |`,
      '| 1.1.1.2 | 乙模块 | — | D7 | 未开发 | — |',
      '',
    ].join('\n'),
    'utf8',
  );
  // 先跑一遍写模式，让台账变成**规范形态**（含汇总块）——
  // 否则 --check 报的是「与蓝图不一致」，而不是我们真正要测的那件事。
  runSync([]);
}

/** 往 1.1.1.1 那行的证据格里塞一段带竖线的文本 */
function injectPipe(text) {
  const p = join(BP, 'module-progress.md');
  const lines = readFileSync(p, 'utf8').split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('| 1.1.1.1 ')) {
      const parts = lines[i].split('|');
      parts[6] = ` ${text} `;
      lines[i] = parts.join('|');
      break;
    }
  }
  writeFileSync(p, lines.join('\n'), 'utf8');
}

function runSync(args = []) {
  try {
    const out = execFileSync('node', [SYNC, ...args], {
      env: { ...process.env, LANE_ROOT: SANDBOX },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { code: 0, out };
  } catch (e) {
    return {
      code: e.status ?? 1,
      out: `${e.stdout ?? ''}${e.stderr ?? ''}`,
    };
  }
}

function evidenceCell() {
  const text = readFileSync(join(BP, 'module-progress.md'), 'utf8');
  const line = text.split('\n').find((l) => l.startsWith('| 1.1.1.1 '));
  return line ?? '';
}

console.log('▶ 台账证据格竖线语义');

// ── 1. 含竖线的证据格：写盘不得截断 ──────────────────────────────────────────
const PIPE_EVIDENCE = '**迁移** `V1__x.sql`（三张表）／ `PUT|DELETE /x/{id}`（改删）／ 端点 3 条';
seed('**迁移** `V1__x.sql`（三张表）');
injectPipe(PIPE_EVIDENCE);
runSync([]);
const cellAfter = evidenceCell();
ok(
  '写盘后证据格未被截断（含竖线的整段都在）',
  cellAfter.includes('`PUT|DELETE /x/{id}`') && cellAfter.includes('端点 3 条'),
  `实际证据格：${cellAfter.slice(0, 200)}`,
);
ok('写盘后模块状态仍是「已交付」（没被回落成未开发）', cellAfter.includes('| 已交付 |'), cellAfter.slice(0, 60));

// ── 2. --check 必须判红并点名模块 ID ─────────────────────────────────────────
const checkRes = runSync(['--check']);
ok('--check 判红（不是绿的）', checkRes.code !== 0, `exit=${checkRes.code}`);
ok('--check 点名了出问题模块 ID', checkRes.out.includes('1.1.1.1'), checkRes.out.slice(0, 200));

// ── 3. 不含竖线的证据格：--check 绿 + 写盘幂等 ──────────────────────────────
const CLEAN_EVIDENCE = '**迁移** `V1__x.sql`（三张表）／端点 3 条／测试 12 例';
seed(CLEAN_EVIDENCE);
injectPipe(CLEAN_EVIDENCE);
runSync([]);
const cleanCheck = runSync(['--check']);
ok('干净证据格下 --check 绿', cleanCheck.code === 0, `exit=${cleanCheck.code} ${cleanCheck.out.slice(0, 160)}`);
const cleanCell = evidenceCell();
ok(
  '干净证据格写盘后原样保留',
  cleanCell.includes(CLEAN_EVIDENCE) && cleanCell.trim().endsWith('|'),
  cleanCell.slice(0, 200),
);
ok(
  '干净证据格写盘后仍是 6 列（表格没被切列）',
  cleanCell.trim().split('|').length === 8,
  `parts=${cleanCell.trim().split('|').length}`,
);

// ── 4. 幂等：连写两次结果一致 ───────────────────────────────────────────────
const once = readFileSync(join(BP, 'module-progress.md'), 'utf8');
runSync([]);
const twice = readFileSync(join(BP, 'module-progress.md'), 'utf8');
ok('连续两次写盘结果一致（幂等）', once === twice);

rmSync(SANDBOX, { recursive: true, force: true });

console.log(`\n${failed === 0 ? '✓ 全部通过' : '✗ 有失败'}：${passed} 通过 / ${failed} 失败`);
if (failed) {
  for (const f of failures) console.error(`  · ${f}`);
  process.exit(1);
}
