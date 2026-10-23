#!/usr/bin/env node
/**
 * ci-gate 闸的集成测试
 *
 * 核心要验的是判据本身：
 *   - 「PR 的失败作业是基线的子集」才算持平
 *   - 同名作业里出现**基线没有的新失败用例**也算新增失败
 *   - 明细取不出来时降级到作业粒度，并如实标记「不可比」
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { extractFailureMarkers, failingJobs, compareRuns, runIsSettled } from './ci-gate.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

// ── 1. 日志里抽失败用例标识 ───────────────────────────────────
{
  const log = [
    '[ERROR] com.starcon.identity.ApprovalRecordIntegrationTest.驳回后重新发起 -- Time elapsed 0.4 s <<< FAILURE!',
    '[INFO] Tests run: 270, Failures: 1, Errors: 0',
    ' FAIL  apps/web/src/app/api/[...path]/route.test.ts > 白名单放行',
    '  ✕ 映射缺失队列应当入库',
  ].join('\n');
  const markers = extractFailureMarkers(log);
  check(
    '抽出 surefire 失败用例',
    markers.some((m) => m.includes('ApprovalRecordIntegrationTest')),
    JSON.stringify(markers),
  );
  check(
    '抽出 vitest 失败用例',
    markers.some((m) => m.includes('route.test.ts')),
    JSON.stringify(markers),
  );
  check(
    '抽出 ✕ 失败用例',
    markers.some((m) => m.includes('映射缺失队列')),
    JSON.stringify(markers),
  );
  check(
    '成功日志不产生标识',
    extractFailureMarkers('[INFO] Tests run: 270, Failures: 0, Errors: 0').length === 0,
  );
}

// ── 2. 失败作业集合 ───────────────────────────────────────────
{
  const jobs = [
    { name: 'Lint', conclusion: 'failure' },
    { name: 'Typecheck', conclusion: 'success' },
    { name: 'Java 21 & PostgreSQL Integration', conclusion: 'failure' },
    { name: 'Build', conclusion: 'skipped' },
  ];
  check('只数 failure', failingJobs(jobs).length === 2);
  check('skipped 不算失败', !failingJobs(jobs).includes('Build'));
}

// ── 3. 持平 ⇒ 放行 ────────────────────────────────────────────
{
  const baseline = [
    { name: 'Lint', conclusion: 'failure' },
    { name: 'Typecheck', conclusion: 'success' },
  ];
  const pr = [
    { name: 'Lint', conclusion: 'failure' },
    { name: 'Typecheck', conclusion: 'success' },
  ];
  const r = compareRuns(baseline, pr, () => ['some.marker']);
  check('同样失败作业不阻塞', !r.blocked);
  check('没有新增失败作业', r.newFailingJobs.length === 0);
  check('没有新增失败用例', r.newFailingTests.length === 0);
}

// ── 4. 新增失败作业 ⇒ 阻塞 ────────────────────────────────────
{
  const baseline = [{ name: 'Lint', conclusion: 'failure' }];
  const pr = [
    { name: 'Lint', conclusion: 'failure' },
    { name: 'Java 21 & PostgreSQL Integration', conclusion: 'failure' },
  ];
  const r = compareRuns(baseline, pr, () => ['x']);
  check('出现基线没有的失败作业则阻塞', r.blocked);
  check('点名新增失败作业', r.newFailingJobs.includes('Java 21 & PostgreSQL Integration'));
}

// ── 5. 同名作业里出现新的失败用例 ⇒ 阻塞 ──────────────────────
{
  const baseline = [{ name: 'Java', conclusion: 'failure' }];
  const pr = [{ name: 'Java', conclusion: 'failure' }];
  const markers = {
    baseline: ['com.foo.OldTest.老用例'],
    pr: ['com.foo.OldTest.老用例', 'com.foo.NewTest.新用例'],
  };
  const r = compareRuns(baseline, pr, (job) =>
    job === baseline[0] ? markers.baseline : markers.pr,
  );
  check('新失败用例阻塞', r.blocked);
  check(
    '点名新失败用例',
    r.newFailingTests.some((t) => t.includes('NewTest')),
  );
}

// ── 6. 明细取不出来 ⇒ 降级到作业粒度并标记不可比 ──────────────
{
  const baseline = [{ name: 'Lint', conclusion: 'failure' }];
  const pr = [{ name: 'Lint', conclusion: 'failure' }];
  const r = compareRuns(baseline, pr, () => []);
  check('明细为空不阻塞', !r.blocked);
  check('标记不可比作业', r.incomparableJobs.includes('Lint'));
}

// ── 7. PR 比基线更干净（基线红、PR 绿）⇒ 放行 ─────────────────
{
  const baseline = [
    { name: 'Lint', conclusion: 'failure' },
    { name: 'Java', conclusion: 'failure' },
  ];
  const pr = [{ name: 'Lint', conclusion: 'success' }];
  const r = compareRuns(baseline, pr, () => []);
  check('PR 比基线更绿不阻塞', !r.blocked);
}

// ── 8. PR 侧作业「没跑到出结果那一步」⇒ 不得按持平放行 ────────
//
// 2026-10-24 实测：PR 侧 Java 作业挂在 spotless（构建期），
// reactor 跳过 identity，测试一条都没跑 ⇒ 失败明细天然为空。
// 旧判据把「明细取不到」一律归入「明细不可比 ⇒ 按作业粒度放行」，
// 于是「PR 引入了新的失败原因」被误判成「没有新增失败」⇒ 假绿灯。
{
  const baseline = [{ name: 'Java 21 & PostgreSQL Integration', conclusion: 'failure' }];
  const pr = [{ name: 'Java 21 & PostgreSQL Integration', conclusion: 'failure' }];
  const markers = {
    baseline: ['com.starcon.identity.ApprovalRecordIntegrationTest.驳回后重新发起'],
    pr: [], // PR 侧挂在 spotless ⇒ 测试没跑 ⇒ 取不到任何用例标识
  };
  const r = compareRuns(baseline, pr, (job) => (job === baseline[0] ? markers.baseline : markers.pr));
  check('PR 侧无明细 + 基线有明细 ⇒ 阻塞', r.blocked);
  check('点名未跑到结果那一步的作业', r.unreachedJobs.includes('Java 21 & PostgreSQL Integration'));
  check('同时仍标记为明细不可比', r.incomparableJobs.includes('Java 21 & PostgreSQL Integration'));

  // 反向对照：两侧都取不到明细 ⇒ 确实是「不可比」，按作业粒度放行（不是新增失败）。
  const both = compareRuns(baseline, pr, () => []);
  check('两侧都无明细 ⇒ 不算新增失败（保持原语义）', !both.blocked);
  check('两侧都无明细 ⇒ 不进 unreachedJobs', both.unreachedJobs.length === 0);
}

// ── 9. run 没跑完 ⇒ 不得判为「可以合并」（假绿灯回归） ────────
//
// 2026-10-24 实测：同一个 run，作业全部在飞时闸门输出「可以合并」，
// 收尾后同一个 run 判「阻塞」。在飞作业的 conclusion 是 null，
// 若默认归入「非失败」，「PR 失败 ⊆ 基线失败」这条判据会空洞地成立。
{
  check('在飞 run 不算收尾', !runIsSettled({ status: 'in_progress' }));
  check('排队中的 run 不算收尾', !runIsSettled({ status: 'queued' }));
  check('收尾的 run 才算收尾', runIsSettled({ status: 'completed' }));
  check('状态缺失时不算收尾', !runIsSettled({}));

  // 在飞作业被当成「非失败」时的比较结果 —— 这就是假绿灯的形态。
  const inFlight = [
    { name: 'Lint', status: 'in_progress', conclusion: null },
    { name: 'Java', status: 'in_progress', conclusion: null },
  ];
  const settledFailure = [{ name: 'Lint', status: 'completed', conclusion: 'failure' }];
  const r = compareRuns(settledFailure, inFlight, () => []);
  check('在飞作业被误判成通过（假绿灯的形态）', !r.blocked);
  // ⇒ 正因为它会给出「不阻塞」，取 run 的那一步必须先卡住「run 没收尾」。
  check('因此必须在取 run 处拦住（runIsSettled 存在且拒绝在飞）', !runIsSettled(inFlight[0]));

  // 被取消的 run：大量作业停在 cancelled/skipped，同样没有可用结论。
  const cancelled = [
    { name: 'Lint', status: 'completed', conclusion: 'cancelled' },
    { name: 'Java', status: 'completed', conclusion: 'skipped' },
  ];
  const rc = compareRuns(settledFailure, cancelled, () => []);
  check('取消的 run 会被误判成通过（同类假绿灯）', !rc.blocked);
  // ⇒ 取 run 处必须另外拦住 conclusion === 'cancelled'。
  check('取消的 run 不得当作有效判据', cancelled[0].conclusion === 'cancelled');
}

// ── 10. 真实仓库：能取到 main 基线的失败作业 ────────────────────
{
  try {
    const out = execFileSync(
      'node',
      [join(ROOT, 'tools', 'module-progress', 'ci-gate.mjs'), '--pr', '11'],
      {
        cwd: ROOT,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
      },
    );
    check('真实运行退出码为 0（与基线持平）', true);
    check('输出含结论', out.includes('结论：'), out.slice(0, 300));
  } catch (err) {
    // 退出码 1 = 判为阻塞（这在基线漂移时是合法结果），退出码 2 = 取不到状态
    const code = err.status;
    const stdout = String(err.stdout ?? '');
    check(
      '真实运行产出可比对的结论',
      code === 1 && stdout.includes('结论：'),
      `exit=${code}\n${stdout.slice(0, 400)}`,
    );
  }
}

if (failures > 0) {
  console.log(`\n✗ ${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n✓ 全部通过');
