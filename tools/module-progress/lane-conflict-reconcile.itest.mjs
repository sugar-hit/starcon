#!/usr/bin/env node
/**
 * merge-ready「已解冲突但标记残留」的回归测试
 *
 * 背景：脚本只在「本次 merge 失败」时写 lane.state='conflict'，从不回头确认
 * 「后来是否被解掉并并进去了」。人工解完冲突提交之后，车道仍是 conflict，于是
 * gc 不清理它的 worktree，下一轮 plan/merge-ready 又把它当未解决冲突反复报。
 * 2026-10-07 一个 tick 内撞了三次（17.3.2.1 / 17.5.1.2 / 7.1.3.1），
 * 每次都得手工改 lanes.json。
 *
 * 这里起一个真的临时仓库：造出「车道是 conflict、分支其实已是集成分支祖先」
 * 的状态，跑真的 lane.mjs merge-ready，断言它自动补记为 merged；
 * 再造一条真的还没并进去的 conflict，断言它**不能**被误标成 merged。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-conflict-reconcile-itest');
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

// 集成分支
g(['branch', 'integration/module-dev'], SANDBOX);

// 车道分支：改一个文件，提交
g(['checkout', '-qb', 'feat/1-1-1'], SANDBOX);
writeFileSync(join(SANDBOX, 'a.txt'), 'from-lane\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'lane work'], SANDBOX);
const laneSha = g(['rev-parse', 'HEAD'], SANDBOX).trim();
g(['checkout', '-q', 'integration/module-dev'], SANDBOX);

// 第二条车道：分支上有一个**没有并进集成分支**的提交，
// 用来断言它不会被误判成「已并入」。若拿集成分支自己的 sha 当 headSha，
// 那它当然恒为祖先，测试就失去意义了。
g(['checkout', '-qb', 'feat/1-1-2'], SANDBOX);
writeFileSync(join(SANDBOX, 'b.txt'), 'unmerged-lane\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', 'lane b work'], SANDBOX);
const unmergedSha = g(['rev-parse', 'HEAD'], SANDBOX).trim();
g(['checkout', '-q', 'integration/module-dev'], SANDBOX);

// 把车道成果真正并进集成分支（模拟「人工解完冲突并提交」）
g(['merge', '--no-ff', '-m', 'merge(1.1.1)', 'feat/1-1-1'], SANDBOX);

// lanes.json：lane-a 是「冲突已解但标记残留」，lane-b 是「真的还没并进去」
const statePath = join(SANDBOX, '.dev-agent', 'lanes.json');
mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
writeFileSync(
  statePath,
  JSON.stringify(
    {
      integrationBranch: 'integration/module-dev',
      lanes: {
        '1.1.1': {
          id: '1.1.1',
          name: '已解冲突',
          branch: 'feat/1-1-1',
          state: 'conflict',
          headSha: laneSha,
          baseSha: g(['rev-parse', 'HEAD~1'], SANDBOX).trim(),
          conflicts: ['some/file.java'],
        },
        '1.1.2': {
          id: '1.1.2',
          name: '真未并入',
          branch: 'feat/1-1-2',
          state: 'conflict',
          headSha: unmergedSha,
          baseSha: g(['rev-parse', 'HEAD'], SANDBOX).trim(),
          conflicts: ['other/file.java'],
        },
      },
    },
    null,
    2,
  ),
);

// lane.mjs 用 join(__dirname,'..','..') 定位 ROOT，所以仓库必须放在
// <sandbox>/tools/module-progress/lane.mjs 才能在临时仓库里跑起来。
mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), readFileSync(LANE, 'utf8'));

// 没有 ready 车道：脚本会在 reconcile 之后提前 exit 0。
// 冲突告警走 stderr，而 execFileSync 只在**失败**时才把 stderr 放进 err ——
// 脚本退出码是 0，所以必须用 spawnSync 才能拿到那条告警。
const r = spawnSync('node', [join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), 'merge-ready'], {
  cwd: SANDBOX,
  encoding: 'utf8',
});
const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;

const state = JSON.parse(readFileSync(statePath, 'utf8'));

check(
  '已解冲突且分支已并入的车道被补记为 merged',
  state.lanes['1.1.1'].state === 'merged',
  `实际 state=${state.lanes['1.1.1'].state}`,
);
check(
  '补记后清掉残留的 conflicts 列表',
  state.lanes['1.1.1'].conflicts === undefined,
  `实际 conflicts=${JSON.stringify(state.lanes['1.1.1'].conflicts)}`,
);
check(
  '补记后写上 mergedAt',
  typeof state.lanes['1.1.1'].mergedAt === 'string' && state.lanes['1.1.1'].mergedAt.length > 0,
);
check(
  '真的还没并入的车道保持 conflict（不得误标）',
  state.lanes['1.1.2'].state === 'conflict',
  `实际 state=${state.lanes['1.1.2'].state}`,
);
check(
  '未解决的冲突在输出里被点名',
  out.includes('1.1.2'),
  `输出未点名 1.1.2：${out}`,
);

rmSync(SANDBOX, { recursive: true, force: true });

if (failures) {
  console.error(`\n${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n全部通过');
