#!/usr/bin/env node
/**
 * plan 写盘不再吞掉「执行窗口期内」其它车道写入的回归测试
 *
 * 背景（2026-10-20 13:00 tick 实测，见
 * docs/backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md）：
 *
 * `plan` 的语义是「读整份状态 → 改 → 写整份状态」，而 readState() 与 writeState()
 * 之间隔着**整个 plan 的执行时间** —— 每条车道要 `git worktree add` +
 * `pnpm install --prefer-offline`（实测 4~5 秒/条）。
 * 期间 worker 的 `land` / `heartbeat` 写的是**同一份** lanes.json，
 * 而 plan 最后把**窗口开始时的陈旧快照**整份写回去 ⇒ 那些新写入被静默吞掉。
 *
 * 实测后果：10.6.1.1 的 `land` 标记被还原成 `running`，
 * 成果（已提交、工作区干净）被 merge-ready 漏并。
 * 而 `land` 自己返回 `ok: true`、退出码 0，**没有任何报错**。
 *
 * 这里起一个真的临时仓库，把 plan 的执行窗口拉长（把 `pnpm install` 换成 sleep），
 * 在窗口期内**并发跑真的** `land` 与 `heartbeat`，然后断言 plan 收尾时：
 *   - 那条刚 land 的车道仍然是 ready（带 landedAt / headSha），没被还原成 running；
 *   - 那条刚续过心跳的车道心跳时间仍然是新的，没被还原成旧值。
 *
 * 另附一条断言：`land` 的「没有任何新提交」闸门不是死的。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-plan-state-itest');
const LANE = join(ROOT, 'tools', 'module-progress', 'lane.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'}${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));
const ago = (minutes) => new Date(Date.now() - minutes * 60_000).toISOString();

if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
mkdirSync(SANDBOX, { recursive: true });

g(['init', '-q', '-b', 'main'], SANDBOX);
g(['config', 'user.email', 'test@starcon.local'], SANDBOX);
g(['config', 'user.name', 'test'], SANDBOX);
writeFileSync(join(SANDBOX, 'README.md'), 'base\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'base'], SANDBOX);
const BASE_SHA = g(['rev-parse', 'HEAD'], SANDBOX).trim();

/* ── 微服务闸前置件（与 lane-critical-path.itest.mjs 同款）──────────────── */
const svcBp = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
mkdirSync(svcBp, { recursive: true });
writeFileSync(
  join(svcBp, 'README.md'),
  [
    '| # | 系统 | 层级归类 | 一句话职责 | 后端主责服务 | 卷 |',
    '| --- | --- | --- | --- | --- | --- |',
    '| 01  | 卷01 | 核心业务域 | 测试 | warehouse | [01](x.md) |',
    '',
  ].join('\n'),
);
const svcDir = join(SANDBOX, 'backend', 'services', 'warehouse');
mkdirSync(svcDir, { recursive: true });
writeFileSync(join(svcDir, 'pom.xml'), '<project/>\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'blueprint readme + warehouse service'], SANDBOX);
g(['branch', 'integration/module-dev'], SANDBOX);

/* ── 蓝图：一个可排模块 1.9.1.1（卷 01，孤立无上游）───────────────────── */
const bpDir = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
mkdirSync(bpDir, { recursive: true });
writeFileSync(
  join(bpDir, '01-test.md'),
  ['### 1.9.1.1 待排模块', '', '- **关系**：上游=无', '- **职责**：测试用', ''].join('\n'),
);
writeFileSync(
  join(bpDir, 'module-progress.md'),
  [
    '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |',
    '| --- | --- | --- | --- | --- | --- |',
    '| 1.9.1.1 | 待排模块 | /1/9/1/1 | D3 | 未开发 | — |',
    '',
  ].join('\n'),
);

/* ── 两条「在途车道」：一条会被 land，一条会被 heartbeat ────────────────── */
const WT = join(SANDBOX, '.worktrees');
mkdirSync(WT, { recursive: true });
// 1.1.1.1：将要 land 的那条 —— 给它一个真的 worktree + 一次新提交，land 才过得去
g(['branch', 'feat/1-1-1-1', 'integration/module-dev'], SANDBOX);
g(['worktree', 'add', join(WT, '1.1.1.1'), 'feat/1-1-1-1'], SANDBOX);
writeFileSync(join(WT, '1.1.1.1', 'work.txt'), 'done\n');
g(['add', '-A'], join(WT, '1.1.1.1'));
g(['commit', '-qm', 'feat: 1.1.1.1 的实现'], join(WT, '1.1.1.1'));
// 1.1.2.2：只会被 heartbeat 的那条（心跳旧一点，但没到孤儿门槛）
//   注意它**不能**有 worktree —— heartbeat 不碰 worktree。

const statePath = join(SANDBOX, '.dev-agent', 'lanes.json');
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
writeFileSync(
  statePath,
  JSON.stringify(
    {
      integrationBranch: 'integration/module-dev',
      lanes: {
        '1.1.1.1': {
          id: '1.1.1.1',
          name: '待落地',
          stage: 'D3',
          l3: '1.1.1',
          blueprint: join(bpDir, '01-test.md'),
          branch: 'feat/1-1-1-1',
          worktree: join(WT, '1.1.1.1'),
          migrationVersion: 'V900',
          state: 'running',
          sessionId: 'mvs_alive',
          baseSha: BASE_SHA.slice(0, 7),
          createdAt: ago(30),
          heartbeatAt: ago(2),
          note: null,
        },
        '1.1.2.2': {
          id: '1.1.2.2',
          name: '待续心跳',
          stage: 'D3',
          l3: '1.1.2',
          blueprint: join(bpDir, '01-test.md'),
          branch: 'feat/1-1-2-2',
          worktree: join(WT, '1.1.2.2'),
          migrationVersion: 'V901',
          state: 'running',
          sessionId: 'mvs_alive',
          baseSha: BASE_SHA.slice(0, 7),
          createdAt: ago(30),
          heartbeatAt: ago(5),
          note: null,
        },
      },
    },
    null,
    2,
  ),
);

/* ── 沙箱里的 lane.mjs：把 pnpm install 换成 sleep，把执行窗口拉长 ─────── */
mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
const src = readFileSync(LANE, 'utf8');
const patched = src.replace(
  "sh('pnpm install --prefer-offline', wt);",
  "sh('sleep 4'); // itest: 把 plan 的执行窗口拉长，制造可复现的并发窗口",
);
check(
  '沙箱补丁命中 pnpm install 那行',
  patched !== src,
  "没找到 `sh('pnpm install --prefer-offline', wt);` —— lane.mjs 结构变了，需要同步更新本测试",
);
writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), patched);

const laneScript = join(SANDBOX, 'tools', 'module-progress', 'lane.mjs');
const stateOf = (id) => JSON.parse(readFileSync(statePath, 'utf8')).lanes[id];

/* ── 场景：plan 在窗口期内，worker 落一条 + 续一条心跳 ─────────────────── */
const planProc = spawn('node', [laneScript, 'plan', '--lanes', '1'], {
  cwd: SANDBOX,
  encoding: 'utf8',
});
let planOut = '';
let planErr = '';
planProc.stdout.on('data', (d) => (planOut += d));
planProc.stderr.on('data', (d) => (planErr += d));
// ⚠️ close 监听必须**先挂上**：plan 要是提前退出了，事后再挂就永远等不到这个事件
// （Node 不会补发），await 会以「unsettled top-level await」挂死。
const planDone = new Promise((resolve) => planProc.on('close', resolve));

// 等 plan 走到「建 worktree → 进 sleep」那一步，窗口就开了
let windowOpen = false;
for (let i = 0; i < 150; i += 1) {
  if (existsSync(join(WT, '1.9.1.1'))) {
    windowOpen = true;
    break;
  }
  await sleepMs(100);
}
check('plan 进入了执行窗口（worktree 已建）', windowOpen, `plan stderr: ${planErr}`);

// 窗口期内：worker 先 land，再续另一条的心跳（两次都是真的 lane.mjs 命令）
const landRes = spawnSync('node', [laneScript, 'land', '--lane', '1.1.1.1'], {
  cwd: SANDBOX,
  encoding: 'utf8',
});
const hbRes = spawnSync(
  'node',
  [laneScript, 'heartbeat', '--lane', '1.1.2.2', '--note', '窗口期内续的心跳'],
  { cwd: SANDBOX, encoding: 'utf8' },
);
check('窗口期内的 land 本身成功', landRes.status === 0, landRes.stdout + landRes.stderr);
check('窗口期内的 heartbeat 本身成功', hbRes.status === 0, hbRes.stdout + hbRes.stderr);

const landed = stateOf('1.1.1.1');
const beatAfter = stateOf('1.1.2.2').heartbeatAt;
check(
  'land 之后立刻读状态确实是 ready',
  landed.state === 'ready' && Boolean(landed.landedAt),
  `state=${landed.state} landedAt=${landed.landedAt}`,
);

const planExitCode = await planDone;
check('plan 正常退出', planExitCode === 0, `退出码 ${planExitCode}\n${planOut}\n${planErr}`);

/* ── 断言：plan 收尾时不能吞掉窗口期内的写入 ───────────────────────────── */
const after = JSON.parse(readFileSync(statePath, 'utf8'));
const afterLanded = after.lanes['1.1.1.1'];
const afterBeat = after.lanes['1.1.2.2'];

check(
  '① 窗口期内 land 的车道，plan 收尾后仍是 ready（没被还原成 running）',
  afterLanded && afterLanded.state === 'ready',
  `state=${afterLanded && afterLanded.state}`,
);
check(
  '  且 landedAt / headSha 都还在',
  afterLanded && Boolean(afterLanded.landedAt) && Boolean(afterLanded.headSha),
  `landedAt=${afterLanded && afterLanded.landedAt} headSha=${afterLanded && afterLanded.headSha}`,
);
check(
  '② 窗口期内 heartbeat 的车道，心跳时间仍是新的（没被还原成旧快照值）',
  afterBeat && afterBeat.heartbeatAt === beatAfter,
  `plan 后=${afterBeat && afterBeat.heartbeatAt} 期望=${beatAfter}`,
);
check(
  '  且它的心跳备注也还在',
  afterBeat && afterBeat.note === '窗口期内续的心跳',
  `note=${afterBeat && afterBeat.note}`,
);
check(
  '③ plan 自己新建的车道确实写进去了（修法没把 plan 的本职工作也弄丢）',
  Boolean(after.lanes['1.9.1.1']),
  `lanes=${Object.keys(after.lanes).join(',')}`,
);

/* ── 断言：land 的「没有任何新提交」闸门不是死的 ───────────────────────── */
const noCommitLane = '1.1.2.2';
const emptyRes = spawnSync('node', [laneScript, 'land', '--lane', noCommitLane], {
  cwd: SANDBOX,
  encoding: 'utf8',
});
check(
  '④ land 拒绝「一条新提交都没有」的车道（HEAD 仍是基线时退出码非 0）',
  emptyRes.status !== 0,
  `退出码 ${emptyRes.status} —— baseSha 比较写错了的话这条会恒为 0`,
);

rmSync(SANDBOX, { recursive: true, force: true });

if (failures) {
  console.error(`\n${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n全部通过');
