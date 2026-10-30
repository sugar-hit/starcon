#!/usr/bin/env node
/**
 * 增量格式闸：只检查**本批新增/改动**的文件是否 prettier 合规。
 *
 * ## 为什么不是全仓 `pnpm format:check`
 *
 * 全仓检查有一个致命前提问题：**存量格式债会让闸永久打红**。
 * 2026-10-30 实测：全仓有 589 个文件不合规（apps/web/src 582 + packages/ui 3 +
 * tools/module-progress 4）。若把它直接挂进 L2 批次闸，L2 从第一次运行起就是红的，
 * 而 L2 红的处置协议是「不 push、二分定位、连续 3 轮红则本轮零派发」
 * ⇒ 流水线会被一个**与本批代码无关的历史债**彻底堵死。
 *
 * 这正是「全仓 spotless:apply 重排 1360 个 Java 文件」那类 P1 污染事故的同构形状：
 * 用存量债的体量去压垮增量流程。
 *
 * ## 增量判据：按批次边界取文件
 *
 * 批次边界 = 集成头上的 **merge commit**（`merge(<模块ID>): <名称>`）。
 * `lane.mjs merge-ready` 每次并入一条车道就产生一个这样的 commit，
 * 所以「本批」= 最近一批 merge commit 触及的文件集合。
 *
 * 取文件的方式是 `git diff <base>...<head> --name-only`：
 *   - base = 本批第一个 merge commit 的**第一父**（即并入前的集成头）
 *   - head = 本批最后一个 merge commit
 * 三点式 diff 只取本分支侧的改动，不会把并入分支的旧历史算进来。
 *
 * ## 为什么不看「工作区未提交改动」
 *
 * L2 跑在集成头上，工作区此时应当是干净的（merge-ready 保证这一点）。
 * 未提交改动属于「还没进批次的东西」，由 worker 自己的 L0/L1 负责。
 *
 * 用法：
 *   node tools/module-progress/format-gate.mjs                    # 自动识别本批
 *   node tools/module-progress/format-gate.mjs --base <sha> --head <sha>
 *   node tools/module-progress/format-gate.mjs --files a.ts,b.ts
 *
 * 退出码：0 = 本批合规；1 = 本批有不合规文件；2 = 用法/环境错误。
 */

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
// FORMAT_GATE_ROOT 仅供 format-gate.itest.mjs 把闸指向一次性仓库来跑端到端断言。
// 不设时行为完全不变（与 lane.mjs 的 LANE_ROOT 同一套约定）。
const ROOT = process.env.FORMAT_GATE_ROOT
  ? resolve(process.env.FORMAT_GATE_ROOT)
  : resolve(HERE, '..', '..');

// prettier 实际会管的扩展名 —— 与 package.json 的 format:check 保持一致。
const PRETTIER_GLOBS = ['ts', 'tsx', 'js', 'jsx', 'json', 'md', 'yaml', 'yml'];

/** 解析 --key value / --key=value 两种写法。 */
function flag(name) {
  const args = process.argv.slice(2);
  const i = args.indexOf(`--${name}`);
  if (i >= 0 && i + 1 < args.length && !args[i + 1].startsWith('--')) return args[i + 1];
  const eq = args.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(name.length + 3) : undefined;
}

function gitRaw(args, cwd = ROOT) {
  try {
    return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
}

/**
 * 找出集成头上最近一批 merge commit。
 *
 * 判据：commit message 的标题行匹配 `merge(<模块ID>): <名称>`。
 * 这是 `lane.mjs merge-ready` 生成 merge commit 时的固定格式
 * （`git merge --no-ff -m "merge(${lane.id}): ${lane.name}"`）。
 */
function recentBatchMerges(branch) {
  // 取最近 40 个提交足够覆盖一批车道（一批通常 1–6 条）。
  const raw = gitRaw(['log', '-40', '--format=%H%x09%P%x09%s', branch]);
  if (!raw) return null;
  const rows = raw
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [sha, parents, subject] = line.split('\t');
      return { sha, parents: (parents || '').split(' ').filter(Boolean), subject: subject || '' };
    })
    // 只认双父（真合并）且标题形如 merge(<ID>): <名称> 的提交。
    .filter((c) => c.parents.length === 2 && /^merge\([^)]+\):/.test(c.subject));
  if (!rows.length) return null;

  // git log 是新→旧，所以 rows[0] 是本批最后一个 merge commit。
  // base 取本批**最早**那个 merge commit 的第一父 = 并入前的集成头。
  const first = rows[rows.length - 1];
  const base = first.parents[0];
  const head = rows[0].sha;
  return { base, head, merges: rows };
}

function filesBetween(base, head) {
  const out = gitRaw(['diff', '--name-only', `${base}...${head}`]);
  return out ? out.split('\n').filter(Boolean) : [];
}

/**
 * 判断文件是否被 .prettierignore 排除。
 *
 * ⚠️ 不能用 `gitRaw(['check-ignore', '-q', file])` 的返回值判断：
 * `check-ignore` 在**文件未被忽略**时退出码是 1，且 `-q` 模式不输出任何内容
 * ⇒ 空串既可能是「没被忽略」也可能是「命令失败」，无法区分。
 * 必须看退出码。
 */
function isIgnored(file) {
  try {
    execFileSync('git', ['check-ignore', '-q', file], { cwd: ROOT, stdio: 'ignore' });
    return true; // 退出码 0 = 被忽略
  } catch (err) {
    // 退出码 1 = 未被忽略（正常）；其他退出码视为未忽略，交给 prettier 自己判。
    return (err.status ?? 1) === 0;
  }
}

/** 文件是否在 prettier 的管辖范围内（扩展名 + .prettierignore 过滤）。 */
function isPrettierTarget(file) {
  const ext = file.includes('.') ? file.split('.').pop() : '';
  if (!PRETTIER_GLOBS.includes(ext)) return false;
  // 已被 .prettierignore 排除的路径（codegen 产物、会话流水账等）不该拦。
  if (isIgnored(file)) return false;
  return true;
}

/**
 * 定位 prettier 可执行文件。
 *
 * ⚠️ 不能用 `pnpm exec prettier`：pnpm exec 依赖 workspace 根才能解析，
 * 在一次性沙箱仓库（itest）里会直接失败并报「找不到文件」。
 * 直接调 node_modules 里的二进制，行为在任何 cwd 下都一致。
 */
function prettierBin() {
  const candidates = [
    join(ROOT, 'node_modules', 'prettier', 'bin', 'prettier.cjs'),
    join(ROOT, 'node_modules', '.bin', 'prettier'),
    join(resolve(HERE, '..', '..'), 'node_modules', 'prettier', 'bin', 'prettier.cjs'),
  ];
  for (const c of candidates) if (existsSync(c)) return c;
  return null;
}

/**
 * 对给定文件列表跑 prettier --check，返回不合规的文件名。
 *
 * ⚠️ prettier 的退出码语义：
 *   0 = 全部合规
 *   1 = 有不合规文件（stdout 是文件清单）
 *   2 = **执行错误**（配置坏了、文件不存在、pnpm 不可用）
 *
 * 所以退出码 1 与 2 必须分开处理：把 1 当成「有违规」是对的，
 * 把 2 当成「有违规」会把真实错误静默掉。
 *
 * 另外：文件可能在本批被**删除**（diff 里出现但工作区已无），
 * 这种文件要先剔除，否则 prettier 会因「找不到文件」返回 2。
 */
function checkFiles(files) {
  const existing = files.filter((f) => existsSync(join(ROOT, f)));
  if (!existing.length) return [];

  const bin = prettierBin();
  if (!bin) {
    console.error('✗ 找不到 prettier 可执行文件（node_modules/prettier 不存在）');
    process.exit(2);
  }

  let out = '';
  try {
    out = execFileSync(process.execPath, [bin, '--list-different', ...existing], {
      cwd: ROOT,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    });
  } catch (err) {
    if ((err.status ?? 0) === 1) {
      // 有不合规文件 —— 正常路径。
      out = err.stdout ?? '';
    } else {
      const msg = err.stderr || err.stdout || String(err);
      console.error(`✗ prettier 执行失败（退出码 ${err.status ?? '未知'}）：\n${msg}`);
      process.exit(2);
    }
  }
  return out
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}

// ── 主流程 ────────────────────────────────────────────────────────────────

const explicitFiles = flag('files');
let base = flag('base');
let head = flag('head');

let changed;
let batchInfo;

if (explicitFiles) {
  changed = explicitFiles
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  batchInfo = { base: '(files)', head: '(files)', merges: [] };
} else {
  const branch = flag('branch') || 'integration/module-dev';
  if (!existsSync(join(ROOT, '.git'))) {
    console.error('✗ 当前目录不是 git 仓库');
    process.exit(2);
  }
  // 分支不存在时不能继续 —— git log 会报错，而 gitRaw 会把错误吞成空串，
  // 那样就会走到「没有批次」的路径，把「分支写错了」误报成「无需检查」。
  if (!gitRaw(['rev-parse', '--verify', `${branch}^{commit}`])) {
    console.error(`✗ 分支不存在：${branch}`);
    process.exit(2);
  }
  const batch = recentBatchMerges(branch);
  // ⚠️ 判据是「长度为零」，不是「!batch」——
  // 空数组在 JS 里是 truthy，`!batch` 抓不到「没找到批次」这种情况，
  // 会一路走到 batch.base（undefined）上抛 TypeError。
  if (!batch || batch.merges.length === 0) {
    console.log(
      JSON.stringify(
        {
          ok: true,
          skipped: true,
          reason: `集成头 ${branch} 上没有找到批次 merge commit（形如 merge(<模块ID>): <名称>），本轮无新增文件可查`,
        },
        null,
        2,
      ),
    );
    process.exit(0);
  }
  if (!base) base = batch.base;
  if (!head) head = batch.head;
  batchInfo = batch;
  changed = filesBetween(base, head);
}

const targets = changed.filter((f) => isPrettierTarget(f));
const dirty = checkFiles(targets);

const result = {
  ok: dirty.length === 0,
  batch: {
    base: batchInfo.base.slice(0, 12),
    head: batchInfo.head.slice(0, 12),
    mergeCount: batchInfo.merges.length,
  },
  changedFiles: changed.length,
  checkedFiles: targets.length,
  violations: dirty,
  hint: dirty.length
    ? '本批有文件不符合 prettier 约定。请在该车道的 worktree 内跑 `pnpm exec prettier --write <文件>` 后重新提交。' +
      '（不要在集成头上改，也不要全仓 --write —— 全仓重排会制造巨量 diff 并与在途车道冲突。）'
    : '',
};

console.log(JSON.stringify(result, null, 2));
process.exit(dirty.length === 0 ? 0 : 1);
