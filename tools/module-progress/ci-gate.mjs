#!/usr/bin/env node
/**
 * CI 闸：判断同步 PR 的 CI 是不是「不比 main 更差」
 *
 * 为什么不是「CI 全绿」：
 *   本仓库的 CI 在 main 上**长期是红的**（2026-10-14 之后每次都是）。
 *   实测基线：`Lint`（Prettier 格式债 337 个文件）、`Java 21 & PostgreSQL Integration`
 *   （identity 服务 1 例失败）、`Catalog Browser & Contract Tests`。
 *   把「全绿」当闸 = 这个任务永远合不进去，等于没有闸。
 *
 * 判据（保守）：
 *   1. PR 没有任何失败作业 ⇒ 通过。
 *   2. PR 的失败作业集合必须是 main 基线失败作业集合的**子集**。
 *      出现基线里没有的新失败作业 ⇒ 阻塞。
 *   3. 对「两边都失败」的作业，逐条比对**失败用例标识**：
 *      PR 侧出现基线里没有的新用例 ⇒ 阻塞。
 *      明细取不出来时按作业粒度放行，但必须在结论里写明「只比对了作业粒度」。
 *
 * 用法：
 *   node tools/module-progress/ci-gate.mjs --pr 12
 *   node tools/module-progress/ci-gate.mjs --pr 12 --json
 *
 * 退出码：0 = 可以合并；1 = 有新增失败（阻塞）；2 = 取不到 CI 状态（阻塞）。
 */

import { execFileSync } from 'node:child_process';

const ROOT = new URL('..', import.meta.url).pathname.replace(/\/$/, '');

/** 拉一条 gh 命令的 JSON 输出 */
function ghJson(args) {
  const out = execFileSync('gh', args, {
    cwd: ROOT,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  return JSON.parse(out);
}

/**
 * 从一段 CI 日志里抽出「失败用例标识」。
 *
 * 支持三类常见形态：
 *   - Maven/Surefire：`com.foo.SomeTest.用例名 -- Time elapsed ... <<< FAILURE!`
 *   - Vitest / Playwright：`FAIL  path/to/x.test.ts > 用例名`
 *   - 通用：`✕ 用例名` / `× 用例名`
 *
 * 取不到就返回空数组 —— 调用方据此判定「明细不可比」，并降级到作业粒度。
 */
export function extractFailureMarkers(log) {
  const markers = new Set();

  for (const line of log.split('\n')) {
    const surefire = line.match(
      /(?:\[ERROR\]\s*)?([\w.$]+\.[\w一-龥]+?)\s+--\s+Time elapsed.*<<< (FAILURE|ERROR)!/,
    );
    if (surefire) {
      markers.add(surefire[1].trim());
      continue;
    }
    const vitest = line.match(/^\s*(?:FAIL|✕|×)\s+(.+?)(?:\s+\d+ms)?\s*$/);
    if (vitest) {
      markers.add(vitest[1].trim());
      continue;
    }
    const playwright = line.match(/^\s*\d+\)\s+(.+?)\s*$/);
    if (playwright) markers.add(playwright[1].trim());
  }

  return [...markers].sort();
}

/** 取某个 run 里失败的作业名 */
export function failingJobs(jobs) {
  return jobs
    .filter((j) => j.conclusion === 'failure')
    .map((j) => j.name)
    .sort();
}

/**
 * 比对基线与 PR 的失败情况。
 *
 * @param baselineJobs [{name, conclusion, databaseId}]
 * @param prJobs      同上
 * @param markerOf    (job) => string[]  取该作业的失败用例标识
 */
export function compareRuns(baselineJobs, prJobs, markerOf) {
  const baseFail = new Set(failingJobs(baselineJobs));
  const prFail = new Set(failingJobs(prJobs));

  const newFailingJobs = [...prFail].filter((n) => !baseFail.has(n)).sort();

  const newFailingTests = [];
  const incomparableJobs = [];

  for (const name of prFail) {
    if (!baseFail.has(name)) continue;
    const baseMarkers = markerOf(baselineJobs.find((j) => j.name === name));
    const prMarkers = markerOf(prJobs.find((j) => j.name === name));
    if (baseMarkers.length === 0 || prMarkers.length === 0) {
      incomparableJobs.push(name);
      continue;
    }
    const baseSet = new Set(baseMarkers);
    for (const m of prMarkers) {
      if (!baseSet.has(m)) newFailingTests.push(`${name} :: ${m}`);
    }
  }

  const blocked = newFailingJobs.length > 0 || newFailingTests.length > 0;
  return {
    blocked,
    baselineFailingJobs: [...baseFail].sort(),
    prFailingJobs: [...prFail].sort(),
    newFailingJobs,
    newFailingTests,
    incomparableJobs: incomparableJobs.sort(),
  };
}

/** 取 main 最近一次 CI run 的作业列表 */
export function baselineRun() {
  const runs = ghJson([
    'run',
    'list',
    '--branch',
    'main',
    '--limit',
    '5',
    '--json',
    'databaseId,conclusion,createdAt',
  ]);
  if (!runs.length) throw new Error('main 上没有任何 CI run');
  // 取最近一次「跑完了」的 run（跳过还在排队/运行中的）
  const done = runs.find((r) => r.conclusion !== null) ?? runs[0];
  const detail = ghJson(['run', 'view', String(done.databaseId), '--json', 'jobs']);
  return { ...done, jobs: detail.jobs };
}

/** 取某个 PR 本次 head commit 的 CI run 与作业列表 */
export function prRun(prNumber) {
  const pr = ghJson(['pr', 'view', String(prNumber), '--json', 'headRefOid,number,title,state']);
  const runs = ghJson([
    'run',
    'list',
    '--commit',
    pr.headRefOid,
    '--limit',
    '10',
    '--json',
    'databaseId,event,status,conclusion',
  ]);
  const run = runs.find((r) => r.event === 'pull_request') ?? runs[0];
  if (!run) throw new Error(`PR #${prNumber} 的 head commit ${pr.headRefOid} 上没有 CI run`);
  const detail = ghJson(['run', 'view', String(run.databaseId), '--json', 'jobs']);
  return { ...pr, run, jobs: detail.jobs };
}

/** 取某个作业的日志文本 */
export function jobLog(job) {
  if (!job?.databaseId) return '';
  try {
    return execFileSync('gh', ['run', 'view', '--job', String(job.databaseId), '--log'], {
      cwd: ROOT,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch {
    return '';
  }
}

export function parseArgs(argv) {
  const out = { pr: null, json: false };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--pr') out.pr = argv[++i];
    else if (argv[i] === '--json') out.json = true;
  }
  return out;
}

export function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  if (!args.pr) {
    console.error('用法：node tools/module-progress/ci-gate.mjs --pr <PR号>');
    process.exit(2);
  }

  let baseline;
  let pr;
  try {
    baseline = baselineRun();
    pr = prRun(args.pr);
  } catch (err) {
    console.error(`取不到 CI 状态：${err.message}`);
    process.exit(2);
  }

  const result = compareRuns(baseline.jobs, pr.jobs, (job) => extractFailureMarkers(jobLog(job)));

  if (args.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(`基线（main 最近一次 run #${baseline.databaseId}）：`);
    for (const j of result.baselineFailingJobs) console.log(`  ✗ ${j}`);
    if (!result.baselineFailingJobs.length) console.log('  （无失败作业）');
    console.log(`PR #${pr.number}（run #${pr.run.databaseId}）：`);
    for (const j of result.prFailingJobs) console.log(`  ✗ ${j}`);
    if (!result.prFailingJobs.length) console.log('  （无失败作业）');
    console.log('');
    console.log(
      `新增失败作业：${result.newFailingJobs.length ? result.newFailingJobs.join('、') : '无'}`,
    );
    console.log(
      `新增失败用例：${result.newFailingTests.length ? result.newFailingTests.join('；') : '无'}`,
    );
    if (result.incomparableJobs.length) {
      console.log(`明细不可比的作业（只比对了作业粒度）：${result.incomparableJobs.join('、')}`);
    }
    console.log('');
    console.log(
      result.blocked
        ? '结论：阻塞 —— 出现了 main 基线里没有的失败，不合并。'
        : '结论：可以合并 —— 失败情况与 main 基线持平，未出现新增失败。',
    );
  }

  process.exit(result.blocked ? 1 : 0);
}

const invokedDirectly = process.argv[1] && new URL(import.meta.url).pathname === process.argv[1];
if (invokedDirectly) main();
