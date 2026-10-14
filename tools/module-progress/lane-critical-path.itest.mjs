#!/usr/bin/env node
/**
 * pickModules「关键路径优先」的回归测试
 *
 * 背景（2026-10-07 22:04 改）：plan 原来按 STAGE_ORDER 走，同阶段内按模块 ID 升序。
 * 而 D7 的 ID 天然比 D4 大得多，于是「阶段靠后 + ID 靠后」的模块永远排在最后 ——
 * 可它们恰恰是下游最多、解锁面最大的那批。实测 465 个模块的真实数据，同样 6 个名额：
 *
 *   旧（阶段顺序）  平均依赖深度 1.2，且 6 个里 4 个 depth=0（蓝图没写上游声明）
 *   新（深度降序）  平均依赖深度 4.2
 *
 * 改完之后的排序键是：`深度降序 → 阶段顺序 → 模块 ID 升序`。
 *
 * 这里起一个临时仓库，造一条 4 层依赖链 + 一个孤立模块，跑**真的 lane.mjs plan**，
 * 断言：
 *   1. 新车道的顺序是深度降序（而不是 ID 升序）—— 这是本次改动的核心行为；
 *   2. 阶段顺序只在**同深度**时生效（所以阶段靠后但深度高者仍要优先）；
 *   3. 同一个 L3 锚点只会出一条车道（老约束没被改坏）；
 *   4. 蓝图里写了环（A→B→A）时不死循环 —— 深度计算是递归的，环必须有保护。
 *
 * 沙箱里那份 lane.mjs 会被改掉一行：把 `sh('pnpm install ...')` 置空。
 * 排序逻辑（pickModules / depthOf）一个字节都没动，只是屏蔽了环境依赖，
 * 否则每个用例都要真跑一次 pnpm install。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import {
  mkdirSync,
  writeFileSync,
  readFileSync,
  readdirSync,
  rmSync,
  existsSync,
} from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-critical-path-itest');
const LANE = join(ROOT, 'tools', 'module-progress', 'lane.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
mkdirSync(SANDBOX, { recursive: true });

g(['init', '-q', '-b', 'main'], SANDBOX);
g(['config', 'user.email', 'test@starcon.local'], SANDBOX);
g(['config', 'user.name', 'test'], SANDBOX);
writeFileSync(join(SANDBOX, 'README.md'), 'base\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'base'], SANDBOX);

/* ── 微服务闸的前置件（2026-10-07 23:xx 新增）───────────────────────────
   `plan` 现在会先问「这个模块该落的微服务在不在集成头里」，
   数据来自蓝图 README 的「后端主责服务」列，存在性来自
   `git ls-tree <integrationBranch> backend/services/`。

   所以沙箱必须**提交**这两样东西，否则闸门会 fail-loud 直接抛错，
   排序逻辑一条断言都跑不到。真实仓库里 warehouse/catalog/identity/channel 都在，
   这里统一用一个 warehouse 服务覆盖全部测试卷。 */
const svcBp = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
mkdirSync(svcBp, { recursive: true });
const volRow = (n, svc) => `| ${n}  | 卷${n} | 核心业务域 | 测试 | ${svc} | [${n}](x.md) |`;
writeFileSync(
  join(svcBp, 'README.md'),
  [
    '| # | 系统 | 层级归类 | 一句话职责 | 后端主责服务 | 卷 |',
    '| --- | --- | --- | --- | --- | --- |',
    ...['01', '02', '03', '04', '07', '08', '09'].map((n) => volRow(n, 'warehouse')),
    '',
  ].join('\n'),
);
const svcDir = join(SANDBOX, 'backend', 'services', 'warehouse');
mkdirSync(svcDir, { recursive: true });
writeFileSync(join(svcDir, 'pom.xml'), '<project/>\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'blueprint readme + warehouse service'], SANDBOX);

// 必须在服务目录与 README 提交**之后**再建集成分支：
// serviceInHead 读的是集成分支的已提交树，文件只躺在工作区会被判成「服务不存在」。
g(['branch', 'integration/module-dev'], SANDBOX);

/* ── 蓝图 ──────────────────────────────────────────────────────────
   ⚠️ 用例设计的关键约束：`upstreamReady` 要求**上游全部已交付**。
   所以「深度高」≠「能排」—— 一个模块若深度 3，它的直接上游就必须已交付，
   那条前置链得先躺在「已交付」里，末端才可能进候选池。
   （第一版我把 3.3.1.1 设成未开发却断言 4.4.1.1 可选，结果被 upstreamReady 过滤，
     一度误判成「深度降序没生效」。是断言写错，不是代码写错。）

   已交付前置链：1.1.1.1(d0) ← 2.2.1.1(d1) ← 3.3.1.1(d2)
   未开发、可排：  4.4.1.1 / 4.4.1.2（D7，d=3，同 L3 互斥）
                   9.9.1.1（D3，d=0，孤立）—— 阶段靠前，旧的「阶段顺序」会先挑它
                   8.8.1.1（D3，d=2）—— 上游 7.7.1.1 已交付，而 7.7.1.1 的上游正是它，
                                  构成环，用来验证环保护与深度计算不死循环
   ────────────────────────────────────────────────────────────────── */
const bpDir = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
mkdirSync(bpDir, { recursive: true });

const section = (id, name, up) =>
  [
    `### ${id} ${name}`,
    '',
    `- **关系**：上游=${up || '无'}`,
    '- **职责**：测试用',
    '',
  ].join('\n');

writeFileSync(
  join(bpDir, '03-test-chain.md'),
  [
    section('1.1.1.1', '链起点', ''),
    section('2.2.1.1', '深度一', '1.1.1.1'),
    section('3.3.1.1', '深度二', '2.2.1.1'),
    section('4.4.1.1', '末端甲', '3.3.1.1'),
    section('4.4.1.2', '末端乙同L3', '3.3.1.1'),
    section('9.9.1.1', '孤立靠前阶段', ''),
    section('7.7.1.1', '环A已交付', '8.8.1.1'),
    section('8.8.1.1', '环B', '7.7.1.1'),
  ].join('\n'),
);

const row = (id, name, stage, status) =>
  `| ${id} | ${name} | /${id.replace(/\./g, '/')} | ${stage} | ${status} | — |`;

writeFileSync(
  join(bpDir, 'module-progress.md'),
  [
    '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |',
    '| --- | --- | --- | --- | --- | --- |',
    row('1.1.1.1', '链起点', 'D3', '已交付'),
    row('2.2.1.1', '深度一', 'D3', '已交付'),
    row('3.3.1.1', '深度二', 'D3', '已交付'),
    row('4.4.1.1', '末端甲', 'D7', '未开发'),
    row('4.4.1.2', '末端乙同L3', 'D7', '未开发'),
    row('9.9.1.1', '孤立靠前阶段', 'D3', '未开发'),
    row('7.7.1.1', '环A已交付', 'D3', '已交付'),
    row('8.8.1.1', '环B', 'D3', '未开发'),
    '',
  ].join('\n'),
);

// 没有在途车道，避免孤儿重派干扰 toDispatch
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
writeFileSync(
  join(SANDBOX, '.dev-agent', 'lanes.json'),
  JSON.stringify({ integrationBranch: 'integration/module-dev', lanes: {} }, null, 2),
);

// lane.mjs 用 join(__dirname,'..','..') 定位 ROOT，必须放在 <sandbox>/tools/module-progress/
mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
const src = readFileSync(LANE, 'utf8');
const patched = src.replace(
  "sh('pnpm install --prefer-offline', wt);",
  'void wt; // itest: 屏蔽 pnpm install（排序逻辑未改动）',
);
check(
  '沙箱补丁命中 pnpm install 那行',
  patched !== src,
  '没找到 `sh(\'pnpm install --prefer-offline\', wt);` —— lane.mjs 结构变了，需要同步更新本测试',
);
writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), patched);

/* ── 跑真的 plan ─────────────────────────────────────────────────── */
const runPlan = (laneCount) => {
  // 每轮 plan 都会留下 worktree 与 feat/* 分支，先清干净，保证每轮从同一状态出发
  const wtRoot = join(SANDBOX, '.worktrees');
  if (existsSync(wtRoot)) {
    for (const d of readdirSync(wtRoot, { withFileTypes: true })) {
      spawnSync('git', ['worktree', 'remove', '--force', join(wtRoot, d.name)], {
        cwd: SANDBOX,
        encoding: 'utf8',
      });
    }
    rmSync(wtRoot, { recursive: true, force: true });
  }
  for (const br of g(['branch', '--format=%(refname:short)'], SANDBOX).split('\n')) {
    if (br.startsWith('feat/')) g(['branch', '-D', br], SANDBOX);
  }
  spawnSync('git', ['worktree', 'prune'], { cwd: SANDBOX, encoding: 'utf8' });
  writeFileSync(
    join(SANDBOX, '.dev-agent', 'lanes.json'),
    JSON.stringify({ integrationBranch: 'integration/module-dev', lanes: {} }, null, 2),
  );

  const r = spawnSync(
    'node',
    [
      join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'),
      'plan',
      '--lanes',
      String(laneCount),
    ],
    { cwd: SANDBOX, encoding: 'utf8', timeout: 60_000 },
  );
  if (r.status !== 0) {
    console.error(r.stdout || '', r.stderr || '');
    throw new Error(`plan 退出码 ${r.status}（环保护可能失效导致崩溃？）`);
  }
  return JSON.parse(r.stdout);
};

const out = runPlan(2);
const got = out.newlyPlanned;

console.log('\n实际挑选顺序:', got.join(' → '));
console.log('');

check(
  '① 深度降序：D7 但深度 3 的 4.4.1.1 必须排第一，压过阶段靠前但 d=0 的 9.9.1.1',
  got[0] === '4.4.1.1',
  `got[0]=${got[0]}（旧的「阶段顺序」会挑 9.9.1.1，因为它在 D3 而 4.4.1.1 在 D7）`,
);
check(
  '① 深度降序：4.4.1.1 占掉 L3 4.4.1 后，同为 d=3 的 4.4.1.2 必须让位，' +
    '第二条应是 d=2 的 8.8.1.1（而不是 d=0 的 9.9.1.1）',
  got[1] === '8.8.1.1',
  `got[1]=${got[1]}`,
);
check(
  '② L3 唯一性：4.4.1.1 与 4.4.1.2 同锚点 4.4.1，两个名额里只能出一个',
  !(got.includes('4.4.1.1') && got.includes('4.4.1.2')),
  `got=${got.join(',')}`,
);
// 环保护：runPlan 在 plan 崩溃（死循环/栈溢出）时会直接 throw，跑到这里就说明没崩
check(
  '③ 环保护：7.7.1.1 ↔ 8.8.1.1 互指上游，plan 仍正常返回（depthOf 没死循环）',
  Array.isArray(got),
);
check(
  '③ 环的末端 8.8.1.1 深度为 2，应排在 d=0 的 9.9.1.1 之前',
  !got.includes('9.9.1.1') ||
    !got.includes('8.8.1.1') ||
    got.indexOf('8.8.1.1') < got.indexOf('9.9.1.1'),
  `got=${got.join(',')}`,
);

console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 条失败`}`);
process.exit(failures === 0 ? 0 : 1);