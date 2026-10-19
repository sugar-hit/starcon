#!/usr/bin/env node
/**
 * 跨机车道（Mac A 调度 + Mac B 执行）的端到端集成测试。
 *
 * 它在**完全隔离的沙箱**里跑一遍真实流程：
 *   裸仓库当远端 → 两个克隆分别扮演 Mac A / Mac B →
 *   签发票据 → 领取 → 读票 → 心跳 → 交付 → 收割 → 并入。
 *
 * 断言的是**协议的不变式**，不是脚本的输出格式：
 *   1. 票据在远端分支上可读，且带机器可读头部
 *   2. 交付后票据从远端分支消失（= 完成信号）
 *   3. 交付前收割**不得**收编（防止把半成品并进集成头）
 *   4. 收割后车道状态变成 ready，headSha 指向远端 tip
 *   5. 并入后集成头**不含**票据文件（票据不进最终代码）
 *   6. 心跳 tag 能被 A 侧读到，且年龄可算
 *
 * 用法：node tools/module-progress/remote-lane.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const SANDBOX = join(REPO, '.tmp', `itest-remote-${process.pid}`);

// 沙箱必须**无论成败都清掉**：崩在中间会留下一个几百 MB 的假仓库，
// 下次跑又换一个 pid 目录，越攒越多。exit 事件在正常退出和未捕获异常时都会触发。
const cleanupSandbox = () => {
  try {
    rmSync(SANDBOX, { recursive: true, force: true });
  } catch {
    /* 清理失败不该盖掉真正的测试结论 */
  }
};
process.on('exit', cleanupSandbox);
process.on('uncaughtException', (err) => {
  cleanupSandbox();
  console.error(err);
  process.exit(1);
});

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

function run(cmd, args, cwd) {
  return execFileSync(cmd, args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).trim();
}

function git(args, cwd, allowFail = false) {
  try {
    return run('git', args, cwd);
  } catch (err) {
    if (allowFail) return null;
    throw err;
  }
}

function node(script, args, cwd) {
  return run('node', [script, ...args], cwd);
}

/* ── 沙箱搭建 ─────────────────────────────────────────────────────────── */

const REMOTE = join(SANDBOX, 'remote.git');
const A = join(SANDBOX, 'mac-a');
const B = join(SANDBOX, 'mac-b');
const LANE_ID = '11.2.3.2';
const BRANCH = 'feat/11-2-3-2';
const BASE_SHA = 'deadbeef';

function setup() {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(SANDBOX, { recursive: true });

  // 裸仓库当远端
  run('git', ['init', '--bare', '-b', 'integration/module-dev', REMOTE], SANDBOX);

  // 种子仓库：放到远端上，作为集成头的初始内容
  const seed = join(SANDBOX, 'seed');
  mkdirSync(seed, { recursive: true });
  git(['init', '-b', 'integration/module-dev'], seed);
  git(['config', 'user.email', 'itest@example.com'], seed);
  git(['config', 'user.name', 'itest'], seed);
  mkdirSync(join(seed, 'tools', 'module-progress'), { recursive: true });
  mkdirSync(join(seed, 'src'), { recursive: true });
  writeFileSync(join(seed, 'src', 'app.txt'), 'baseline\n');
  writeFileSync(join(seed, '.gitignore'), '.dev-agent/\n.worktrees/\n');
  writeFileSync(
    join(seed, 'tools', 'module-progress', 'remote-lane.mjs'),
    readFileSync(join(HERE, 'remote-lane.mjs'), 'utf8'),
  );
  writeFileSync(
    join(seed, 'tools', 'module-progress', 'remote-worker.mjs'),
    readFileSync(join(HERE, 'remote-worker.mjs'), 'utf8'),
  );
  git(['add', '-A'], seed);
  git(['commit', '-m', 'seed'], seed);
  git(['remote', 'add', 'upstream', REMOTE], seed);
  git(['push', '-u', 'upstream', 'integration/module-dev'], seed);

  // Mac A / Mac B
  for (const [dir, who] of [
    [A, 'A'],
    [B, 'B'],
  ]) {
    run('git', ['clone', REMOTE, dir], SANDBOX);
    // 脚本默认远端名是 upstream（与 starcon 仓库一致），克隆默认叫 origin → 改名
    git(['remote', 'rename', 'origin', 'upstream'], dir);
    git(['config', 'user.email', `mac-${who.toLowerCase()}@example.com`], dir);
    git(['config', 'user.name', `Mac ${who}`], dir);
    git(['checkout', 'integration/module-dev'], dir);
  }

  // Mac A：伪造一条已 plan 好的车道
  const baseSha = git(['rev-parse', 'HEAD'], A);
  git(['branch', BRANCH, baseSha], A);
  const wt = join(A, '.worktrees', LANE_ID);
  mkdirSync(join(A, '.worktrees'), { recursive: true });
  git(['worktree', 'add', wt, BRANCH], A);
  mkdirSync(join(A, '.dev-agent'), { recursive: true });
  writeFileSync(
    join(A, '.dev-agent', 'lanes.json'),
    `${JSON.stringify(
      {
        integrationBranch: 'integration/module-dev',
        lanes: {
          [LANE_ID]: {
            id: LANE_ID,
            name: '跨机测试模块',
            stage: 'D4',
            l3: '11.2.3',
            blueprint: 'docs/product/capability-blueprint/11-aftersales-and-returns.md',
            branch: BRANCH,
            worktree: wt,
            migrationVersion: 'V9001',
            state: 'running',
            baseSha: baseSha.slice(0, 7),
            createdAt: new Date().toISOString(),
            heartbeatAt: new Date().toISOString(),
          },
        },
      },
      null,
      2,
    )}\n`,
  );

  // 任务书
  const brief = join(SANDBOX, 'brief.md');
  writeFileSync(brief, '# 任务书\n\n实现模块 11.2.3.2 的全部能力。\n');

  return { brief, baseSha, wt };
}

/* ── 测试流程 ─────────────────────────────────────────────────────────── */

console.log(`\n跨机车道集成测试 · 沙箱 ${SANDBOX}\n`);

const { brief } = setup();

// ── 1. Mac A 签发票据 ────────────────────────────────────────────────
console.log('① Mac A 签发票据');
const issueOut = node(
  join(A, 'tools/module-progress/remote-lane.mjs'),
  ['issue', '--lane', LANE_ID, '--brief', brief],
  A,
);
const issue = JSON.parse(issueOut);
ok('issue 返回 ok', issue.ok === true, issueOut);
ok('票据路径正确', issue.ticket === `.lane-tickets/${LANE_ID}.md`, issue.ticket);

const remoteBranch = git(['ls-remote', '--heads', 'upstream', `refs/heads/${BRANCH}`], A);
ok('远端已存在车道分支', remoteBranch.includes(BRANCH), remoteBranch);

const ticketOnRemote = git(
  ['show', `upstream/${BRANCH}:.lane-tickets/${LANE_ID}.md`],
  A,
);
ok('远端分支上能读到票据', ticketOnRemote.includes('实现模块 11.2.3.2'), ticketOnRemote.slice(0, 80));
ok('票据带机器可读头部', /lane:\s*11\.2\.3\.2/.test(ticketOnRemote), ticketOnRemote.slice(0, 120));
ok('票据带预分配迁移版本号', /migrationVersion:\s*V9001/.test(ticketOnRemote));

// ── 2. Mac B 轮询到票 ────────────────────────────────────────────────
console.log('② Mac B 轮询到票');
const poll = JSON.parse(node(join(B, 'tools/module-progress/remote-worker.mjs'), ['poll'], B));
ok('轮询发现 1 条未领取车道', poll.unclaimed.length === 1, JSON.stringify(poll.unclaimed));
ok('轮询出的模块 ID 正确', poll.unclaimed[0]?.lane === LANE_ID, poll.unclaimed[0]?.lane);
ok('轮询出的迁移版本号正确', poll.unclaimed[0]?.migrationVersion === 'V9001');

// ── 3. 交付前不得收编 ────────────────────────────────────────────────
console.log('③ 交付前 A 收割必须跳过');
const early = JSON.parse(node(join(A, 'tools/module-progress/remote-lane.mjs'), ['harvest'], A));
ok('交付前不收编', early.adopted.length === 0, JSON.stringify(early.adopted));
ok('交付前给出跳过原因', (early.skipped[0]?.why ?? '').includes('票据仍在'), early.skipped[0]?.why);

// ── 4. Mac B 领票 + 读票 ─────────────────────────────────────────────
console.log('④ Mac B 领票并读任务书');
node(join(B, 'tools/module-progress/remote-worker.mjs'), ['claim', '--lane', LANE_ID], B);
const read = node(join(B, 'tools/module-progress/remote-worker.mjs'), ['read', '--lane', LANE_ID], B);
ok('读到任务书正文', read.includes('实现模块 11.2.3.2'), read.slice(0, 80));
ok('读到的任务书带头部', /lane-ticket/.test(read));

// ── 5. 心跳 ───────────────────────────────────────────────────────────
console.log('⑤ Mac B 打心跳');
node(join(B, 'tools/module-progress/remote-worker.mjs'), ['heartbeat', '--lane', LANE_ID], B);
const tagOut = git(['ls-remote', '--tags', 'upstream', `refs/tags/hb/${LANE_ID}`], A);
ok('远端存在心跳 tag', tagOut.includes('hb/'), tagOut);
const st = JSON.parse(node(join(A, 'tools/module-progress/remote-lane.mjs'), ['status'], A));
const row = st.lanes.find((l) => l.lane === LANE_ID);
ok('A 侧能读到心跳', Boolean(row?.heartbeat), JSON.stringify(row?.heartbeat));
ok('心跳年龄可算且合理', row?.heartbeat?.ageMinutes >= 0 && row?.heartbeat?.ageMinutes < 5, JSON.stringify(row?.heartbeat));

// ── 6. Mac B 干活并交付 ──────────────────────────────────────────────
console.log('⑥ Mac B 干活并交付');
const bwt = join(B, '.worktrees', LANE_ID);
writeFileSync(join(bwt, 'src', 'app.txt'), 'baseline\n+ 本机交付的内容\n');
git(['add', '-A'], bwt);
git(['commit', '-m', 'feat(11.2.3.2): 跨机实现'], bwt);
const report = join(SANDBOX, 'report.md');
writeFileSync(report, '模块 11.2.3.2 已交付，验证通过。\n');
const fin = JSON.parse(
  node(join(B, 'tools/module-progress/remote-worker.mjs'), ['finish', '--lane', LANE_ID, '--report', report], B),
);
ok('交付成功', fin.ok === true, JSON.stringify(fin));

git(['fetch', 'upstream', `+${BRANCH}:refs/remotes/upstream/${BRANCH}`], A);
const ticketGone = git(['show', `refs/remotes/upstream/${BRANCH}:.lane-tickets/${LANE_ID}.md`], A, true);
ok('交付后远端票据消失（= 完成信号）', ticketGone === null, String(ticketGone).slice(0, 80));

// ── 7. Mac A 收割 ────────────────────────────────────────────────────
console.log('⑦ Mac A 收割远端车道');
const harvest = JSON.parse(node(join(A, 'tools/module-progress/remote-lane.mjs'), ['harvest'], A));
ok('收割收编了 1 条', harvest.adopted.length === 1, JSON.stringify(harvest.adopted));
const lanes = JSON.parse(readFileSync(join(A, '.dev-agent', 'lanes.json'), 'utf8'));
ok('车道状态变为 ready', lanes.lanes[LANE_ID].state === 'ready', lanes.lanes[LANE_ID].state);
ok('headSha 已记录', Boolean(lanes.lanes[LANE_ID].headSha), lanes.lanes[LANE_ID].headSha);

// ── 8. 并入后集成头不含票据 ──────────────────────────────────────────
console.log('⑧ 并入集成头');
git(['merge', '--no-ff', '-m', `merge(${LANE_ID})`, BRANCH], A);
const mergedTree = git(['ls-tree', '-r', '--name-only', 'HEAD'], A);
ok('集成头不含票据文件', !mergedTree.includes(`.lane-tickets/${LANE_ID}.md`), mergedTree);
ok('集成头含交付的代码改动', readFileSync(join(A, 'src', 'app.txt'), 'utf8').includes('本机交付的内容'));
ok(
  '交付报告进了交付 commit 的 message',
  git(['log', '-1', '--format=%B', BRANCH], A).includes('已交付'),
  git(['log', '-1', '--format=%B', BRANCH], A).slice(0, 120),
);

/* ── 收尾 ───────────────────────────────────────────────────────────── */

rmSync(SANDBOX, { recursive: true, force: true });

console.log(`\n结果：${passed} 通过，${failed} 失败`);
if (failed) {
  console.log('\n失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
