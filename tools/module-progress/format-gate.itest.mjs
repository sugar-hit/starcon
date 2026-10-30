#!/usr/bin/env node
/**
 * 增量格式闸（`format-gate.mjs`）的语义测试。
 *
 * 这个闸的核心保证有两条，缺一不可：
 *
 *   1. **只查本批** —— 存量格式债（当前 589 个文件）**不得**让闸打红。
 *      否则 L2 批次闸从第一次运行起就是红的，而 L2 红的协议是「不 push、
 *      二分定位、连续 3 轮红则零派发」⇒ 流水线会被与本批无关的历史债彻底堵死。
 *      这与「全仓 spotless:apply 重排 1360 个 Java 文件」是同构的污染形状。
 *
 *   2. **本批新增必须拦** —— 本批带进来的不合规文件要能被抓出来，
 *      否则闸形同虚设。
 *
 * 另外锁住两件容易静默坏掉的事：
 *   3. **批次边界识别** —— merge commit 的标题格式是 `merge(<模块ID>): <名称>`，
 *      识别错了就会取到错误的文件集合。
 *   4. **.prettierignore 过滤** —— 被排除的路径（codegen 产物、会话流水账）
 *      不该进检查集合。
 *
 * 用法：node tools/module-progress/format-gate.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const GATE = join(HERE, 'format-gate.mjs');
const SANDBOX = join(resolve(HERE, '..', '..'), '.tmp', `itest-format-gate-${process.pid}`);

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

function sh(cmd, args, cwd) {
  try {
    return { code: 0, out: execFileSync(cmd, args, { cwd, encoding: 'utf8' }) };
  } catch (err) {
    return { code: err.status ?? 1, out: err.stdout ?? '', err: err.stderr ?? '' };
  }
}

/** 跑格式闸，返回 { code, json }。 */
function gate(args = []) {
  // FORMAT_GATE_ROOT 把闸指向沙箱仓库 —— 不设的话闸会去查真实仓库，
  // 断言就会拿真实仓库的结果来比（itest 会「通过」但毫无意义）。
  const r = (() => {
    try {
      const out = execFileSync('node', [GATE, ...args], {
        cwd: SANDBOX,
        encoding: 'utf8',
        env: { ...process.env, FORMAT_GATE_ROOT: SANDBOX },
      });
      return { code: 0, out };
    } catch (err) {
      return { code: err.status ?? 1, out: err.stdout ?? '', err: err.stderr ?? '' };
    }
  })();
  let json = null;
  try {
    json = JSON.parse(r.out);
  } catch {
    /* 保持 null */
  }
  return { code: r.code, json, raw: r.out };
}
// ── 搭建沙箱仓库 ──────────────────────────────────────────────────────────

rmSync(SANDBOX, { recursive: true, force: true });
mkdirSync(SANDBOX, { recursive: true });

const git = (args) => sh('git', args, SANDBOX);
git(['init', '-q', '-b', 'integration/module-dev']);
git(['config', 'user.email', 'itest@example.com']);
git(['config', 'user.name', 'itest']);

// 最小 prettier 配置 —— 与仓库一致的关键项：100 列宽、单引号。
writeFileSync(
  join(SANDBOX, '.prettierrc'),
  JSON.stringify(
    {
      printWidth: 100,
      tabWidth: 2,
      useTabs: false,
      semi: true,
      singleQuote: true,
      trailingComma: 'all',
      endOfLine: 'lf',
    },
    null,
    2,
  ),
);

// 排除规则：模拟仓库对 codegen 产物与会话流水账的处理。
writeFileSync(
  join(SANDBOX, '.prettierignore'),
  ['packages/api-contracts/', 'docs/sessions/'].join('\n') + '\n',
);

mkdirSync(join(SANDBOX, 'apps/web/src'), { recursive: true });
mkdirSync(join(SANDBOX, 'packages/api-contracts'), { recursive: true });
mkdirSync(join(SANDBOX, 'docs/sessions'), { recursive: true });

const CLEAN_SRC = `export const ok = { a: 1, b: 2 };\n`;
const DIRTY_SRC = `export const wide = { alpha: 'aaaaaaaaaaaaaaaaaaaaaaa', beta: 'bbbbbbbbbbbbbbbbbbbbbb', gamma: 'cccccccccccccccccccc' };\n`;

// 基线提交：存量债文件（不合规，但不属于本批）。
writeFileSync(join(SANDBOX, 'apps/web/src/legacy-debt.ts'), DIRTY_SRC);
writeFileSync(join(SANDBOX, 'packages/api-contracts/gen.ts'), DIRTY_SRC);
writeFileSync(join(SANDBOX, 'docs/sessions/log.md'), DIRTY_SRC);
git(['add', '-A']);
git(['commit', '-qm', 'base']);

// 模拟一条车道：分支上带**干净**代码。
git(['checkout', '-qb', 'feat/1-1-1-1']);
writeFileSync(join(SANDBOX, 'apps/web/src/fresh-clean.ts'), CLEAN_SRC);
git(['add', '-A']);
git(['commit', '-qm', 'feat: 干净代码']);

// 模拟另一条车道：分支上带**不合规**代码 + 一份会被忽略的会话日志。
git(['checkout', '-qb', 'feat/2-2-2-2']);
writeFileSync(join(SANDBOX, 'apps/web/src/fresh-dirty.ts'), DIRTY_SRC);
writeFileSync(join(SANDBOX, 'docs/sessions/round-log.md'), DIRTY_SRC);
writeFileSync(join(SANDBOX, 'packages/api-contracts/gen2.ts'), DIRTY_SRC);
git(['add', '-A']);
git(['commit', '-qm', 'feat: 带格式债的代码']);

// 并入两条车道 —— merge commit 标题形如 merge(<模块ID>): <名称>。
git(['checkout', '-q', 'integration/module-dev']);
git(['merge', '--no-ff', '-q', '-m', 'merge(1.1.1.1): 干净车道', 'feat/1-1-1-1']);
git(['merge', '--no-ff', '-q', '-m', 'merge(2.2.2.2): 带债车道', 'feat/2-2-2-2']);

console.log('\n增量格式闸 itest\n');

// ── 断言 ──────────────────────────────────────────────────────────────────

// 1. 本批带进来的不合规文件必须被抓出来。
const r1 = gate();
ok('本批有不合规文件时退出码为 1', r1.code === 1, `实际 code=${r1.code}`);
ok(
  '抓出本批的不合规源码文件',
  Array.isArray(r1.json?.violations) && r1.json.violations.includes('apps/web/src/fresh-dirty.ts'),
  JSON.stringify(r1.json?.violations),
);

// 2. 存量债不得让闸打红 —— 这是闸的核心保证。
ok(
  '存量债文件不在 violations 里',
  Array.isArray(r1.json?.violations) && !r1.json.violations.includes('apps/web/src/legacy-debt.ts'),
  JSON.stringify(r1.json?.violations),
);

// 3. .prettierignore 排除的路径不进检查集合。
ok(
  '被忽略的 codegen 产物不在 violations 里',
  Array.isArray(r1.json?.violations) &&
    !r1.json.violations.includes('packages/api-contracts/gen2.ts'),
  JSON.stringify(r1.json?.violations),
);
ok(
  '被忽略的会话日志不在 violations 里',
  Array.isArray(r1.json?.violations) && !r1.json.violations.includes('docs/sessions/round-log.md'),
  JSON.stringify(r1.json?.violations),
);

// 4. 批次边界：mergeCount 应等于本批 merge commit 数（2）。
ok('批次 merge commit 数为 2', r1.json?.batch?.mergeCount === 2, JSON.stringify(r1.json?.batch));

// 5. 本批干净的文件不该被列为违规。
ok(
  '本批干净文件不在 violations 里',
  Array.isArray(r1.json?.violations) && !r1.json.violations.includes('apps/web/src/fresh-clean.ts'),
  JSON.stringify(r1.json?.violations),
);

// 6. 显式 --files 时按指定文件检查。
const r2 = gate(['--files', 'apps/web/src/fresh-clean.ts']);
ok(
  '显式指定干净文件时退出码为 0',
  r2.code === 0,
  `实际 code=${r2.code} out=${r2.raw?.slice(0, 200)}`,
);

// 7. 没有批次 merge commit 时应跳过而不是报错。
// 从**基线提交**建分支（不是从当前头），否则分支里仍带着那两个 merge commit。
const baseSha = git(['rev-parse', 'HEAD~2']).out.trim();
git(['checkout', '-q', '-b', 'integration/module-dev-no-merge', baseSha]);
const r3 = gate(['--branch', 'integration/module-dev-no-merge']);
ok(
  '无批次 merge commit 时退出码为 0 且 skipped',
  r3.code === 0 && r3.json?.skipped === true,
  JSON.stringify(r3.json),
);

// 8. 分支不存在时必须报错退出，而不是误报「无需检查」。
const r4 = gate(['--branch', 'no-such-branch-xyz']);
ok('分支不存在时退出码为 2', r4.code === 2, `实际 code=${r4.code} out=${r4.raw?.slice(0, 160)}`);

// ── 清理 ──────────────────────────────────────────────────────────────────
rmSync(SANDBOX, { recursive: true, force: true });

console.log(`\n通过 ${passed} / 失败 ${failed}`);
if (failures.length) {
  console.log('\n失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
