#!/usr/bin/env node
/**
 * plan「把活着的车道当成孤儿」的回归测试
 *
 * 背景：原来的孤儿判据是 `state === 'running' && (!l.sessionId || laneIsStale(l))`。
 * 但 cron 派发写的是 `plan --lanes 4`，**不带 --session**，于是每条车道的
 * sessionId 恒为 null。于是每一条**正在跑、心跳新鲜**的车道都被当成孤儿：
 *
 *   - reDispatched 每轮 +1，`toDispatch` 里永远是同 4 条；
 *     调度者照单全派就直接开双 writer（两个 worker 写同一个 worktree）。
 *   - `pickModules(count - orphaned.length)` 被这 4 条「假孤儿」吃光名额，
 *     newlyPlanned 恒为 [] —— 一条新车道都排不出来，流水线静默卡死在 4 条。
 *
 * 2026-10-07 20:22 实测：4 条车道 hbAge=0min（刚续过心跳）却被 reDispatched=2，
 * plan 返回 newlyPlanned=[]。这就是「假重派」判据失效后的稳定形态。
 *
 * 这里起一个真的临时仓库，造四种车道状态，跑真的 lane.mjs plan，
 * 断言「心跳新鲜的即��没有 sessionId」不再被当成孤儿，而真正失联的照旧被回收。
 *
 * 用 `plan --lanes 0`：孤儿判定发生在建车道之前，count=0 时 pickModules 立即返回空，
 * 于是既能拿到 toDispatch（= 孤儿集合），又不会真的 git worktree add / pnpm install。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-plan-orphan-itest');
const LANE = join(ROOT, 'tools', 'module-progress', 'lane.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

const ago = (minutes) => new Date(Date.now() - minutes * 60_000).toISOString();

if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
mkdirSync(SANDBOX, { recursive: true });

g(['init', '-q', '-b', 'main'], SANDBOX);
g(['config', 'user.email', 'test@starcon.local'], SANDBOX);
g(['config', 'user.name', 'test'], SANDBOX);
writeFileSync(join(SANDBOX, 'README.md'), 'base\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'base'], SANDBOX);
// 微服务闸的前置件：plan 会问「卷的主责服务在不在集成头里」，
// 数据读蓝图 README 的「后端主责服务」列，存在性读集成分支的已提交树。
// 两样都得先提交再建集成分支，否则闸门 fail-loud，本用例一条断言都跑不到。
// 用例里的模块都在卷 1 与卷 9，统一映射到 warehouse。
const svcBp = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
mkdirSync(svcBp, { recursive: true });
writeFileSync(
  join(svcBp, 'README.md'),
  [
    '| # | 系统 | 层级归类 | 一句话职责 | 后端主责服务 | 卷 |',
    '| --- | --- | --- | --- | --- | --- |',
    ...['01', '09'].map((n) => `| ${n}  | 卷${n} | 核心业务域 | 测试 | warehouse | [${n}](x.md) |`),
    '',
  ].join('\n'),
);
const svcDir = join(SANDBOX, 'backend', 'services', 'warehouse');
mkdirSync(svcDir, { recursive: true });
writeFileSync(join(svcDir, 'pom.xml'), '<project/>\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'blueprint readme + warehouse service'], SANDBOX);
g(['branch', 'integration/module-dev'], SANDBOX);

// 蓝图目录：plan 会扫它建上游依赖图，空目录即可
mkdirSync(join(SANDBOX, 'docs', 'product', 'capability-blueprint'), { recursive: true });
writeFileSync(
  join(SANDBOX, 'docs', 'product', 'capability-blueprint', 'module-progress.md'),
  ['| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |', '| --- | --- | --- | --- | --- | --- |', '| 9.9.9.9 | 占位 | /x | D3 | 未开发 | — |', ''].join('\n'),
);

// 四种车道状态：
//   1.1.1 sessionId=null + 心跳新鲜  → 活着的（cron 不传 --session 的常态），**不得**当孤儿
//   1.1.2 sessionId=null + 心跳 120 分钟前 → 真失联，**必须**回收
//   1.1.3 sessionId 有值 + 心跳新鲜   → 活着的，不得当孤儿
//   1.1.4 sessionId 有值 + 心跳 120 分钟前 → 超 TTL，**必须**回收
const statePath = join(SANDBOX, '.dev-agent', 'lanes.json');
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
writeFileSync(
  statePath,
  JSON.stringify(
    {
      integrationBranch: 'integration/module-dev',
      lanes: {
        '1.1.1': { id: '1.1.1', name: '活但无session', state: 'running', sessionId: null, heartbeatAt: ago(1), reDispatched: 0 },
        '1.1.2': { id: '1.1.2', name: '真失联', state: 'running', sessionId: null, heartbeatAt: ago(120), reDispatched: 0 },
        '1.1.3': { id: '1.1.3', name: '活且有session', state: 'running', sessionId: 'mvs_alive', heartbeatAt: ago(1), reDispatched: 0 },
        '1.1.4': { id: '1.1.4', name: '超TTL', state: 'running', sessionId: 'mvs_dead', heartbeatAt: ago(120), reDispatched: 0 },
      },
    },
    null,
    2,
  ),
);

// lane.mjs 用 join(__dirname,'..','..') 定位 ROOT，必须放在 <sandbox>/tools/module-progress/
mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), readFileSync(LANE, 'utf8'));

// 跑之前先把心跳时间取样下来（plan 会刷新它，见下方注释）
const heartbeatBefore = Object.fromEntries(
  Object.entries(JSON.parse(readFileSync(statePath, 'utf8')).lanes).map(([id, l]) => [
    id,
    l.heartbeatAt,
  ]),
);

const r = spawnSync('node', [join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), 'plan', '--lanes', '0'], {
  cwd: SANDBOX,
  encoding: 'utf8',
});
const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;

if (r.status !== 0) {
  console.error(out);
  process.exit(1);
}

// plan 在认领孤儿时会**顺手把 heartbeatAt 刷成当前时间**（这是正确行为：
// 调度者接手了这条车道就该续上心跳）。所以「派发前是否新鲜」必须在跑之前取样，
// 跑完再读 state 只会看到全部都是新鲜的 —— 那样这条断言恒为真，等于没断言。
const dispatched = new Set();
try {
  const parsed = JSON.parse(r.stdout);
  for (const l of parsed.toDispatch ?? []) dispatched.add(l.id);
} catch {
  console.error(`无法解析 plan 输出：\n${out}`);
  process.exit(1);
}

const state = JSON.parse(readFileSync(statePath, 'utf8'));
const rd = (id) => state.lanes[id].reDispatched ?? 0;

check('心跳新鲜且无 sessionId 的车道不再被当成孤儿', !dispatched.has('1.1.1'), `被派发了：${out}`);
check('  且它的 reDispatched 没有被自增', rd('1.1.1') === 0, `实际 reDispatched=${rd('1.1.1')}`);
check('真失联（无 sessionId 且心跳 120 分钟）仍被回收', dispatched.has('1.1.2'), `未回收：${out}`);
check('  且 reDispatched 自增到 1', rd('1.1.2') === 1, `实际 reDispatched=${rd('1.1.2')}`);
check('心跳新鲜且有 sessionId 的车道不被当成孤儿', !dispatched.has('1.1.3'), `被派发了：${out}`);
check('超 TTL（心跳 120 分钟）即使有 sessionId 也回收', dispatched.has('1.1.4'), `未回收：${out}`);

// 反向断言：待派发里出现的，必须全是跑 plan **之前**就已经心跳超时的车道。
// 任何心跳新鲜的车道混进来，调度者照单全派就是双 writer。
const wasFresh = (id) => Date.now() - new Date(heartbeatBefore[id]).getTime() <= 90 * 60_000;
check(
  '待派发集合里不含任何心跳新鲜的车道',
  [...dispatched].every((id) => !wasFresh(id)),
  `待派发=${[...dispatched].join(',')}，其中新鲜的=${[...dispatched].filter(wasFresh).join(',')}`,
);

rmSync(SANDBOX, { recursive: true, force: true });

if (failures) {
  console.error(`\n${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n全部通过');