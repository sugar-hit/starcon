#!/usr/bin/env node
/**
 * 分支纪律工具（`new-branch.mjs`）的语义测试。
 *
 * 这个工具的价值全在「拒绝」与「不覆盖」两件事上，所以测试也只盯这两件：
 *
 *   1. **非法命名必须被挡住** —— type 不在允许值里、短名是纯数字、
 *      短名是无语义词、短名不是 kebab-case，一律拒绝并给出可执行的提示。
 *   2. **重名绝不覆盖** —— 既有分支一个都不能动，新分支自动追加时间戳；
 *      远端已有的同名分支同样要算数。
 *   3. **不能产生副作用** —— `--dry-run` 不建分支，`--no-switch` 不改当前分支。
 *
 * 用法：node tools/scripts/new-branch.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(HERE, 'new-branch.mjs');
const ROOT = resolve(HERE, '..', '..');
const SANDBOX = join(ROOT, '.tmp', `itest-new-branch-${process.pid}`);

let passed = 0;
let failed = 0;
const failures = [];

function ok(name, cond, detail = '') {
  if (cond) {
    passed += 1;
    console.log(`  ✓ ${name}`);
  } else {
    failed += 1;
    failures.push(`${name}${detail ? ` —— ${detail}` : ''}`);
    console.log(`  ✗ ${name}${detail ? ` —— ${detail}` : ''}`);
  }
}

/** 在沙箱里跑 git。 */
function git(args, cwd = SANDBOX) {
  try {
    return {
      code: 0,
      out: execFileSync('git', args, { cwd, encoding: 'utf8' }),
      err: '',
    };
  } catch (err) {
    return { code: err.status ?? 1, out: err.stdout ?? '', err: err.stderr ?? '' };
  }
}

/** 跑被测脚本。 */
function run(args, cwd = SANDBOX) {
  try {
    return {
      code: 0,
      out: execFileSync('node', [SCRIPT, ...args], { cwd, encoding: 'utf8' }),
      err: '',
    };
  } catch (err) {
    return { code: err.status ?? 1, out: err.stdout ?? '', err: err.stderr ?? '' };
  }
}

/** 本地分支短名列表。 */
function branchNames(cwd = SANDBOX) {
  return git(['for-each-ref', '--format=%(refname:short)', 'refs/heads'], cwd)
    .out.split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
}

/** 当前分支名。 */
function currentBranch(cwd = SANDBOX) {
  return git(['rev-parse', '--abbrev-ref', 'HEAD'], cwd).out.trim();
}

// ── 搭建沙箱仓库 ──────────────────────────────────────────────────────────

rmSync(SANDBOX, { recursive: true, force: true });
mkdirSync(SANDBOX, { recursive: true });

const REMOTE = join(SANDBOX, 'remote.git');
mkdirSync(REMOTE, { recursive: true });
git(['init', '-q', '--bare', REMOTE], SANDBOX);

git(['init', '-q', '-b', 'main'], SANDBOX);
git(['config', 'user.email', 'itest@example.com'], SANDBOX);
git(['config', 'user.name', 'itest'], SANDBOX);
writeFileSync(join(SANDBOX, 'README.md'), '# sandbox\n');
git(['add', '-A'], SANDBOX);
git(['commit', '-qm', 'base'], SANDBOX);
git(['remote', 'add', 'origin', REMOTE], SANDBOX);
git(['push', '-q', '-u', 'origin', 'main'], SANDBOX);

const ORIGIN_MAIN_SHA = git(['rev-parse', 'origin/main']).out.trim();

console.log('\n分支纪律工具 itest\n');

// ── 断言 ──────────────────────────────────────────────────────────────────

// 1. 合法命名应当建出分支，并切过去。
{
  const r = run(['feat', 'invoice-export']);
  ok('合法 type + 语义短名时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok(
    '分支 feat/invoice-export 被创建',
    branchNames().includes('feat/invoice-export'),
    branchNames().join(', '),
  );
  ok('创建后切到新分支', currentBranch() === 'feat/invoice-export', currentBranch());
}

// 2. 起点必须是远端主分支，而不是本地当前分支。
{
  const sha = git(['rev-parse', 'feat/invoice-export']).out.trim();
  ok('新分支起点等于 origin/main', sha === ORIGIN_MAIN_SHA, `${sha} vs ${ORIGIN_MAIN_SHA}`);
}

// 3. type 不在允许值里必须被拒绝。
{
  const r = run(['feature', 'invoice-export']);
  ok('非法 type 时退出码为 1', r.code === 1, `实际 code=${r.code}`);
  ok('非法 type 的报错列出允许值', r.err.includes('feat') && r.err.includes('perf'), r.err);
  ok('非法 type 时不产生分支', !branchNames().includes('feature/invoice-export'));
}

// 4. 纯数字短名必须被拒绝。
{
  const r = run(['feat', '123']);
  ok('纯数字短名时退出码为 1', r.code === 1, `实际 code=${r.code}`);
  ok('纯数字短名的报错说明原因', r.err.includes('纯数字'), r.err);
  ok('纯数字短名时不产生分支', !branchNames().includes('feat/123'));
}

// 5. 无语义短名必须被拒绝。
{
  const r = run(['feat', 'wip']);
  ok('无语义短名时退出码为 1', r.code === 1, `实际 code=${r.code}`);
  ok('无语义短名的报错说明原因', r.err.includes('没有语义'), r.err);
  ok('无语义短名时不产生分支', !branchNames().includes('feat/wip'));
}

// 6. 非 kebab-case 短名必须被拒绝。
{
  const r = run(['feat', 'Invoice_Export']);
  ok('非 kebab-case 短名时退出码为 1', r.code === 1, `实际 code=${r.code}`);
  ok('非 kebab-case 的报错说明原因', r.err.includes('kebab-case'), r.err);
}

// 7. 同名必须自动追加时间戳，且既有分支一个都不能动。
{
  const before = git(['rev-parse', 'feat/invoice-export']).out.trim();
  const r = run(['feat', 'invoice-export']);
  ok('重名时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok('重名时输出说明已追加时间戳', r.out.includes('时间戳'), r.out);

  const names = branchNames();
  const deduped = names.filter((n) => /^feat\/invoice-export-\d{8}-\d{4}/.test(n));
  ok('重名时新建了带时间戳的分支', deduped.length === 1, names.join(', '));
  ok('既有分支仍然存在且提交未变', git(['rev-parse', 'feat/invoice-export']).out.trim() === before);
  ok('既有分支没有被删除', names.includes('feat/invoice-export'));
}

// 8. 远端已存在的同名分支同样要算重名。
{
  git(['checkout', '-q', '-b', 'feat/remote-existing'], SANDBOX);
  git(['push', '-q', 'origin', 'feat/remote-existing'], SANDBOX);
  git(['checkout', '-q', 'main'], SANDBOX);
  git(['branch', '-q', '-D', 'feat/remote-existing'], SANDBOX);

  const r = run(['feat', 'remote-existing']);
  ok('远端同名时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok(
    '远端同名时追加时间戳而不是直接复用',
    branchNames().some((n) => /^feat\/remote-existing-\d{8}-\d{4}/.test(n)),
    branchNames().join(', '),
  );
}

// 9. --dry-run 不得产生任何写操作。
{
  const before = branchNames().sort().join(',');
  const r = run(['feat', 'dry-run-probe', '--dry-run']);
  ok('--dry-run 时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok('--dry-run 时打印了将要使用的分支名', r.out.includes('feat/dry-run-probe'), r.out);
  ok('--dry-run 时分支列表未变', branchNames().sort().join(',') === before);
}

// 10. --no-switch 不得改变当前分支。
{
  const before = currentBranch();
  const r = run(['feat', 'no-switch-probe', '--no-switch']);
  ok('--no-switch 时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok('--no-switch 时分支仍被创建', branchNames().includes('feat/no-switch-probe'));
  ok('--no-switch 时当前分支未变', currentBranch() === before, currentBranch());
}

// 11. <type>/<short-desc> 合并写法与分开写法等价。
{
  const r = run(['docs/session-log-archive']);
  ok('合并写法时退出码为 0', r.code === 0, `实际 code=${r.code} err=${r.err}`);
  ok(
    '合并写法建出同样的分支名',
    branchNames().includes('docs/session-log-archive'),
    branchNames().join(', '),
  );
}

// 12. 缺参数必须报错而不是瞎猜。
{
  const r = run([]);
  ok('缺参数时退出码为 1', r.code === 1, `实际 code=${r.code}`);
}

// ── 清理 ──────────────────────────────────────────────────────────────────

rmSync(SANDBOX, { recursive: true, force: true });

console.log(`\n通过 ${passed} / 失败 ${failed}`);
if (failures.length) {
  console.log('\n失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
