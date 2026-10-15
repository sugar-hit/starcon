#!/usr/bin/env node
/**
 * pickModules「微服务盲区闸」的回归测试
 *
 * 背景（2026-10-07 22:52 / 23:03 实测事故）：
 * `plan` 原来只检查「上游已交付 + L3 锚点不同」，**不检查模块该落到哪个微服务**。
 * 集成头 backend/services 当时只有 catalog / channel / identity / warehouse ——
 * 没有 trade。于是卷 09（订单履约，主责 trade）与卷 11（售后，主责 trade）的车道
 * **注定 BLOCK**：一条车道 40 分钟，烧掉一个 worktree 和一个 Flyway 版本号，产出为零。
 * 一晚白烧了 9.3.2.2(V84)、11.4.2.2(V85)、7.8.1.1 三条。
 *
 * 修法：读蓝图 README 里本来就有的「后端主责服务」列（不在代码里另抄一张表，
 * 否则必然与蓝图漂移），要求**卷的主责服务（列表第一项）已存在于集成头**。
 *
 * 这里起临时仓库，跑**真的 lane.mjs plan**，断言 5 件事：
 *   1. 主责服务不存在的卷，模块不会被排出来（这正是要修的 bug）
 *   2. 主责服务存在的卷，模块照排 —— 闸不能把能干活的活一起挡掉
 *   3. 「任一服务存在即可」是错的：「trade + channel」里 channel 存在，
 *      但履约归 trade，所以卷 09 必须被挡住（否则 worker 会把履约写进 channel）
 *   4. 被挡的卷会出现在 plan 输出的 serviceBlocked 里，带原因与样本模块 ——
 *      否则 plan 返回空数组时，调度器只会以为「没活了」，看不出是服务缺口
 *   5. 读不到 README 时 **fail-loud**，绝不静默放行 ——
 *      静默放行正是这道闸要消灭的那种失败
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-service-gate-itest');
const LANE = join(ROOT, 'tools', 'module-progress', 'lane.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

/**
 * 造一个沙箱仓库。
 * @param services 集成头里**实际存在**的服务目录名
 * @param owners   卷号 → 主责服务字符串（与真实 README 同格式）
 * @param withReadme 是否写 README.md（false = 测 fail-loud）
 */
function makeSandbox(services, owners, withReadme = true) {
  if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
  mkdirSync(SANDBOX, { recursive: true });

  g(['init', '-q', '-b', 'main'], SANDBOX);
  g(['config', 'user.email', 'test@starcon.local'], SANDBOX);
  g(['config', 'user.name', 'test'], SANDBOX);

  const bp = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
  mkdirSync(bp, { recursive: true });

  if (withReadme) {
    const rows = Object.entries(owners).map(
      ([v, svc]) =>
        `| ${v.padStart(2, '0')}  | 卷${v} | 核心业务域 | 测试 | ${svc} | [${v}](x.md) |`,
    );
    writeFileSync(
      join(bp, 'README.md'),
      [
        '| # | 系统 | 层级归类 | 一句话职责 | 后端主责服务 | 卷 |',
        '| --- | --- | --- | --- | --- | --- |',
        ...rows,
        '',
      ].join('\n'),
    );
  }

  for (const s of services) {
    const d = join(SANDBOX, 'backend', 'services', s);
    mkdirSync(d, { recursive: true });
    writeFileSync(join(d, 'pom.xml'), '<project/>\n');
  }

  // 蓝图：模块都在不同卷，彼此无上游依赖，所以「上游已交付」不会把它们过滤掉
  const section = (id, name) =>
    [`### ${id} ${name}`, '', '- **职责**：测试用', '- **关系**：上游=无', ''].join('\n');
  writeFileSync(
    join(bp, '01-test.md'),
    ['1.1.1.1 卷一可用', '3.3.1.1 卷三可用', '9.9.1.1 卷九缺服务', '11.9.1.1 卷十一缺服务', '']
      .map((t) => {
        const [id, name] = t.split(' ');
        return section(id, name);
      })
      .join('\n'),
  );

  const row = (id, name, stage) =>
    `| ${id} | ${name} | /${id.replace(/\./g, '/')} | ${stage} | 未开发 | — |`;
  writeFileSync(
    join(bp, 'module-progress.md'),
    [
      '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |',
      '| --- | --- | --- | --- | --- | --- |',
      row('1.1.1.1', '卷一可用', 'D3'),
      row('3.3.1.1', '卷三可用', 'D3'),
      row('9.9.1.1', '卷九缺服务', 'D3'),
      row('11.9.1.1', '卷十一缺服务', 'D3'),
      '',
    ].join('\n'),
  );

  g(['add', '-A'], SANDBOX);
  g(['commit', '-qm', 'fixtures'], SANDBOX);
  g(['branch', 'integration/module-dev'], SANDBOX);

  mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
  writeFileSync(
    join(SANDBOX, '.dev-agent', 'lanes.json'),
    JSON.stringify({ integrationBranch: 'integration/module-dev', lanes: {} }, null, 2),
  );

  // 与 lane-critical-path.itest.mjs 同样屏蔽 pnpm install；闸门逻辑一个字节没动
  mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
  const src = readFileSync(LANE, 'utf8');
  const patched = src.replace(
    "sh('pnpm install --frozen-lockfile --prefer-offline', wt);",
    'void wt; // itest: 屏蔽 pnpm install（闸门逻辑未改动）',
  );
  check('沙箱补丁命中 pnpm install 那行', patched !== src, 'lane.mjs 结构变了，需要同步更新本测试');
  writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), patched);
}

function runPlan(laneCount = 6) {
  const r = spawnSync(
    'node',
    [
      join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'),
      'plan',
      '--lanes',
      String(laneCount),
      '--dry-run',
    ],
    { cwd: SANDBOX, encoding: 'utf8', timeout: 60_000 },
  );
  return { status: r.status, out: r.stdout, err: r.stderr };
}

/* ── 场景 1：只有 warehouse 落地，trade 缺失 ─────────────────────────── */

console.log('场景 1：集成头只有 warehouse，卷 1/3 可落地，卷 9/11 主责 trade 缺失\n');

makeSandbox(['warehouse'], {
  '01': 'warehouse',
  '03': 'catalog + warehouse', // 主责 catalog 缺，但 warehouse 在 —— 仍应被挡住
  '09': 'trade + channel', // channel 也在？不在。主责 trade 缺
  11: 'trade + finance + warehouse',
});

const r1 = runPlan();
check('plan 正常退出', r1.status === 0, r1.err.slice(0, 300));
const j1 = JSON.parse(r1.out);

console.log('  排出来:', j1.newlyPlanned.join(' ') || '(空)');
console.log('');

check(
  '① 主责服务缺失的卷不会被排出来：9.9.1.1（主责 trade）必须缺席',
  !j1.newlyPlanned.includes('9.9.1.1'),
  `got=${j1.newlyPlanned.join(',')}`,
);
check(
  '① 同理 11.9.1.1（主责 trade）必须缺席',
  !j1.newlyPlanned.includes('11.9.1.1'),
  `got=${j1.newlyPlanned.join(',')}`,
);
check(
  '② 主责服务存在的卷照排：1.1.1.1（warehouse）必须出现',
  j1.newlyPlanned.includes('1.1.1.1'),
  `got=${j1.newlyPlanned.join(',')} —— 闸门把能干的活也挡掉了`,
);
check(
  '③ 「任一服务存在即可」是错的：卷 3 主责 catalog 缺失，' +
    '即便列表里第二个 warehouse 存在也必须挡住（否则 worker 会把履约/刊登写进错的服务）',
  !j1.newlyPlanned.includes('3.3.1.1'),
  `got=${j1.newlyPlanned.join(',')}`,
);

const blockedIds = j1.serviceBlocked.map((b) => b.volume);
check(
  '④ 被挡的卷会出现在 serviceBlocked 里，且带原因与样本模块',
  blockedIds.includes('9') &&
    blockedIds.includes('11') &&
    j1.serviceBlocked.find((b) => b.volume === '9').reason.includes('trade') &&
    j1.serviceBlocked.find((b) => b.volume === '9').sample.length > 0,
  `blocked=${JSON.stringify(j1.serviceBlocked)}`,
);
check(
  '④ 可落地的卷不会被误报进 serviceBlocked',
  !blockedIds.includes('1'),
  `blocked=${blockedIds.join(',')}`,
);

/* ── 场景 2：trade 落地后，卷 9 必须解锁 ─────────────────────────────── */

console.log('\n场景 2：把 trade 补进集成头，卷 9 应当解锁\n');

makeSandbox(['warehouse', 'trade'], {
  '01': 'warehouse',
  '03': 'catalog',
  '09': 'trade + channel',
  11: 'trade',
});
const r2 = runPlan();
check('plan 正常退出', r2.status === 0, r2.err.slice(0, 300));
const j2 = JSON.parse(r2.out);
console.log('  排出来:', j2.newlyPlanned.join(' ') || '(空)', '\n');
check(
  '⑤ trade 落地后 9.9.1.1 立刻可排 —— 闸是动态的，不是永久黑名单',
  j2.newlyPlanned.includes('9.9.1.1'),
  `got=${j2.newlyPlanned.join(',')}`,
);

/* ── 场景 3：README 缺失必须 fail-loud ───────────────────────────────── */

console.log('\n场景 3：蓝图 README 缺失，闸门必须硬失败\n');

makeSandbox(['warehouse'], { '01': 'warehouse' }, /* withReadme */ false);
const r3 = runPlan();
check(
  '⑥ 读不到 README 时 plan 退出码非 0（绝不静默放行）',
  r3.status !== 0,
  `status=${r3.status} —— 静默放行正是这道闸要消灭的失败`,
);
check(
  '⑥ 报错信息说清是「无法判定落地服务」而不是别的',
  /主责服务/.test(r3.err),
  r3.err.slice(0, 200),
);

console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 条失败`}`);
process.exit(failures === 0 ? 0 : 1);
