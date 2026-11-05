#!/usr/bin/env node
/**
 * 按仓库分支纪律创建分支。
 *
 * 这条纪律来自两处：AGENTS.md「PR & commit 规范」与用户级「分支纪律」约定。
 * 脚本要保证三件事，缺一不可：
 *
 *   1. **type 表达改动性质** —— 只允许 feat / fix / refactor / docs / test / chore / perf，
 *      避免 `feature` / `bugfix` / `wip` 这类同义异形把历史搅乱。
 *   2. **短名说清楚改了什么** —— 必须是 kebab-case，且不得是纯数字或
 *      `wip` / `tmp` / `misc` 这类无语义词。分支名是长期可读的上下文，
 *      纯数字名等于把上下文直接丢掉。
 *   3. **重名不覆盖** —— 同名时自动追加时间戳 `-YYYYMMDD-HHMM`，
 *      同一分钟内再次冲突再补秒，保证既有分支一个都不会被动。
 *
 * 用法：
 *   node tools/scripts/new-branch.mjs <type> <short-desc> [options]
 *   node tools/scripts/new-branch.mjs <type>/<short-desc> [options]
 *
 * options:
 *   --base <ref>     起点，默认取远端主分支（upstream/main → origin/main → main）
 *   --remote <name>  查重用的远端，默认自动探测（upstream → origin）
 *   --dry-run        只打印将采用的分支名，不做任何写操作
 *   --no-switch      建分支但不切换过去
 *
 * 退出码：0 成功 / 1 参数非法 / 2 git 操作失败。
 */

import { execFileSync } from 'node:child_process';

/** 允许的改动性质。 */
const ALLOWED_TYPES = ['feat', 'fix', 'refactor', 'docs', 'test', 'chore', 'perf'];

/**
 * 无语义词黑名单：这些词单独出现时没有交代「改了什么」。
 * 命中即拒绝，让人再想一个能说明内容的名字。
 */
const MEANINGLESS_NAMES = new Set([
  'wip',
  'tmp',
  'temp',
  'misc',
  'stuff',
  'foo',
  'bar',
  'baz',
  'qux',
  'untitled',
  'new',
  'update',
  'updates',
  'change',
  'changes',
  'work',
  'task',
  'tasks',
  'dev',
  'asdf',
  'qwerty',
  'xxx',
]);

/** 参数非法。 */
class UsageError extends Error {}

/** 执行 git 命令，返回 { code, out, err }，不抛异常。 */
function git(args, options = {}) {
  try {
    const out = execFileSync('git', args, {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      ...options,
    });
    return { code: 0, out: out ?? '', err: '' };
  } catch (err) {
    return { code: err.status ?? 1, out: err.stdout ?? '', err: err.stderr ?? '' };
  }
}

/** 解析命令行参数。 */
function parseArgs(argv) {
  const opts = { base: null, remote: null, dryRun: false, switch: true, help: false };
  const positional = [];

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--dry-run') {
      opts.dryRun = true;
    } else if (arg === '--no-switch') {
      opts.switch = false;
    } else if (arg === '--base') {
      opts.base = argv[i + 1] ?? null;
      i += 1;
    } else if (arg === '--remote') {
      opts.remote = argv[i + 1] ?? null;
      i += 1;
    } else if (arg === '--help' || arg === '-h') {
      opts.help = true;
    } else if (arg.startsWith('-')) {
      throw new UsageError(`未知选项：${arg}`);
    } else {
      positional.push(arg);
    }
  }

  return { opts, positional };
}

/** 校验 type。 */
function validateType(type) {
  if (!ALLOWED_TYPES.includes(type)) {
    throw new UsageError(
      `type 必须是改动性质，允许值：${ALLOWED_TYPES.join(' / ')}（收到 "${type}"）`,
    );
  }
}

/** 校验短名：kebab-case、含字母、有语义。 */
function validateShortName(name) {
  if (!name) {
    throw new UsageError('缺少短名');
  }
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(name)) {
    throw new UsageError(
      `短名必须是 kebab-case 小写短横线形式（收到 "${name}"），例如 invoice-export`,
    );
  }
  if (!/[a-z]/.test(name)) {
    throw new UsageError(`短名不得是纯数字或纯符号（收到 "${name}"）—— 分支名要说明这里改了什么`);
  }
  if (MEANINGLESS_NAMES.has(name)) {
    throw new UsageError(`短名 "${name}" 没有语义，请换成能说明改动内容的名词短语`);
  }
}

/** 列出本地分支与远端跟踪分支的短名。 */
function localRefNames() {
  const out = git(['for-each-ref', '--format=%(refname:short)', 'refs/heads', 'refs/remotes']).out;
  return out
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
}

/** 远端是否已有同名分支。远端不可达时返回 null，表示查不到而不是「没有」。 */
function remoteBranchExists(remote, name) {
  if (!remote) {
    return null;
  }
  const r = git(['ls-remote', '--heads', remote, name]);
  if (r.code !== 0) {
    return null;
  }
  return r.out.trim().length > 0;
}

/** 查重判定：本地分支、远端跟踪分支、远端真实分支任一命中即算已存在。 */
function makeTakenChecker(remote, refs) {
  const remotePrefixes = remote ? [`${remote}/`] : [];
  return (name) => {
    if (refs.includes(name)) {
      return true;
    }
    if (remotePrefixes.some((prefix) => refs.includes(`${prefix}${name}`))) {
      return true;
    }
    return remoteBranchExists(remote, name) === true;
  };
}

/** 时间戳后缀。 */
function timestampSuffix(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  const day = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`;
  const hourMinute = `${pad(date.getHours())}${pad(date.getMinutes())}`;
  const second = `${pad(date.getSeconds())}`;
  return { minute: `${day}-${hourMinute}`, second: `${day}-${hourMinute}${second}` };
}

/** 在重名时挑一个可用名：先补到分钟，再补到秒，最后补序号。 */
function resolveBranchName(base, isTaken) {
  if (!isTaken(base)) {
    return { name: base, deduped: false };
  }

  const { minute, second } = timestampSuffix();
  const candidates = [`${base}-${minute}`, `${base}-${second}`];
  for (const candidate of candidates) {
    if (!isTaken(candidate)) {
      return { name: candidate, deduped: true };
    }
  }

  for (let i = 2; i < 100; i += 1) {
    const candidate = `${base}-${second}-${i}`;
    if (!isTaken(candidate)) {
      return { name: candidate, deduped: true };
    }
  }

  throw new UsageError(`无法为 "${base}" 找到不重名的分支名`);
}

/** 探测远端。 */
function resolveRemote(override) {
  const remotes = git(['remote'])
    .out.split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  if (override) {
    if (!remotes.includes(override)) {
      throw new UsageError(`远端 "${override}" 不存在（可用：${remotes.join(' / ') || '无'}）`);
    }
    return override;
  }

  for (const candidate of ['upstream', 'origin']) {
    if (remotes.includes(candidate)) {
      return candidate;
    }
  }
  return remotes[0] ?? null;
}

/** 解析起点引用。 */
function resolveBase(explicit, remote) {
  if (explicit) {
    if (git(['rev-parse', '--verify', '--quiet', `${explicit}^{commit}`]).code !== 0) {
      throw new UsageError(`起点 "${explicit}" 不是有效引用`);
    }
    return explicit;
  }

  const candidates = [];
  if (remote) {
    candidates.push(`${remote}/main`, `${remote}/master`);
  }
  candidates.push('main', 'master');

  for (const candidate of candidates) {
    if (git(['rev-parse', '--verify', '--quiet', `${candidate}^{commit}`]).code === 0) {
      return candidate;
    }
  }
  throw new UsageError('找不到可用起点，请用 --base 指定');
}

/** 从位置参数里取出 type 与短名。 */
function parseTarget(positional) {
  if (positional.length === 2) {
    return { type: positional[0], name: positional[1] };
  }
  if (positional.length === 1) {
    const only = positional[0];
    const slash = only.indexOf('/');
    if (slash <= 0 || slash === only.length - 1) {
      throw new UsageError('分支参数写成 <type>/<short-desc> 形式，例如 feat/invoice-export');
    }
    return { type: only.slice(0, slash), name: only.slice(slash + 1) };
  }
  throw new UsageError('需要提供 type 与 short-desc 两个参数');
}

/** 主流程。 */
function main() {
  const { opts, positional } = parseArgs(process.argv.slice(2));

  if (opts.help) {
    console.log(
      [
        '用法：node tools/scripts/new-branch.mjs <type> <short-desc> [options]',
        '      node tools/scripts/new-branch.mjs <type>/<short-desc> [options]',
        '',
        `type 允许值：${ALLOWED_TYPES.join(' / ')}`,
        '',
        'options:',
        '  --base <ref>     起点，默认远端主分支（upstream/main → origin/main → main）',
        '  --remote <name>  查重用的远端，默认自动探测（upstream → origin）',
        '  --dry-run        只打印将采用的分支名，不做写操作',
        '  --no-switch      建分支但不切换过去',
      ].join('\n'),
    );
    return 0;
  }

  const { type, name } = parseTarget(positional);
  validateType(type);
  validateShortName(name);

  const remote = resolveRemote(opts.remote);
  const base = resolveBase(opts.base, remote);
  const isTaken = makeTakenChecker(remote, localRefNames());
  const { name: branchName, deduped } = resolveBranchName(`${type}/${name}`, isTaken);

  if (opts.dryRun) {
    console.log(`分支：${branchName}`);
    console.log(`起点：${base}`);
    console.log(`查重远端：${remote ?? '（无）'}`);
    console.log(deduped ? '说明：原名已存在，已自动追加时间戳' : '说明：原名可用');
    return 0;
  }

  const args = opts.switch ? ['switch', '-c', branchName, base] : ['branch', branchName, base];
  const created = git(args);
  if (created.code !== 0) {
    console.error(`创建分支失败：${created.err.trim() || created.out.trim()}`);
    return 2;
  }

  console.log(`分支：${branchName}`);
  console.log(`起点：${base}`);
  console.log(deduped ? '说明：原名已存在，已自动追加时间戳' : '说明：原名可用');
  return 0;
}

try {
  process.exit(main());
} catch (err) {
  if (err instanceof UsageError) {
    console.error(`参数错误：${err.message}`);
    process.exit(1);
  }
  console.error(`执行失败：${err?.message ?? String(err)}`);
  process.exit(2);
}
