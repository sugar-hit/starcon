#!/usr/bin/env node
/**
 * 跨机车道 —— Mac A（调度者）侧：签发票据 / 收割 / 收编 / 查状态。
 *
 * 为什么需要这个脚本（而不是让调度者手搓 git 命令）：
 *
 *   1. **票据不能放 `.dev-agent/`** —— 那个目录整个被 gitignore（.gitignore:104），
 *      放进去 push 上去的是空分支，远端根本收不到任务书。
 *      所以票据走仓库根的 `.lane-tickets/`。
 *   2. **完成判据必须是两个条件同时成立**：远端 tip 上票据文件消失（worker 删了）
 *      且 tip != baseSha（确实有提交）。只看一个都会误判。
 *   3. **收编时不能强推**：远端分支若与本地分叉，说明 B 在拿到票据前就自己开工了
 *      —— 那是真冲突，必须报给人，不许脚本自作主张覆盖掉 B 的提交。
 *
 * 只在 Mac A 上运行。Mac B 不碰这个脚本。
 *
 * 用法：
 *   node tools/module-progress/remote-lane.mjs issue  --lane <模块ID> --brief <任务书文件>
 *   node tools/module-progress/remote-lane.mjs adopt  --lane <模块ID>
 *   node tools/module-progress/remote-lane.mjs harvest
 *   node tools/module-progress/remote-lane.mjs status
 */

import { execFileSync } from 'node:child_process';
import {
  existsSync,
  readFileSync,
  writeFileSync,
  mkdirSync,
  rmSync,
  statSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = process.env.LANE_ROOT ? resolve(process.env.LANE_ROOT) : join(HERE, '..', '..');
const STATE_DIR = join(ROOT, '.dev-agent');
const STATE_FILE = join(STATE_DIR, 'lanes.json');

/** 票据目录（仓库相对路径）。**不能在 .dev-agent/ 下** —— 那里整个被 gitignore。 */
const TICKET_DIR = '.lane-tickets';
/** 远端名。默认 upstream（GitHub）。 */
const REMOTE = process.env.LANE_REMOTE ?? 'upstream';
/** 失联阈值（分钟）：与 lane.mjs 的 TTL 对齐。 */
const TTL_MINUTES = 90;

/* ── git 封装（与 lane.mjs 同风格） ────────────────────────────────────── */

function git(args, cwd = ROOT, allowFail = false) {
  try {
    return execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).trim();
  } catch (err) {
    if (allowFail) return null;
    throw new Error(`git ${args.join(' ')} 失败：${err.stderr || err.message}`);
  }
}

const sh = (cmd, cwd = ROOT) =>
  execFileSync('bash', ['-lc', cmd], { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).trim();

/* ── 状态读写 ─────────────────────────────────────────────────────────── */

const readState = () => {
  try {
    const s = JSON.parse(readFileSync(STATE_FILE, 'utf8'));
    s.lanes ??= {};
    return s;
  } catch {
    return { lanes: {} };
  }
};

const writeState = (s) => {
  mkdirSync(STATE_DIR, { recursive: true });
  writeFileSync(STATE_FILE, `${JSON.stringify(s, null, 2)}\n`);
};

const flag = (name) => {
  const i = process.argv.indexOf(name);
  if (i === -1) return undefined;
  const next = process.argv[i + 1];
  return next && !next.startsWith('--') ? next : true;
};

const ticketPath = (id) => `${TICKET_DIR}/${id}.md`;

/* ── 远端探针 ─────────────────────────────────────────────────────────── */

/** 远端分支 tip（sha），不存在返回 null。 */
function remoteTip(branch) {
  const out = git(['ls-remote', '--heads', REMOTE, `refs/heads/${branch}`], ROOT, true);
  if (!out) return null;
  const sha = out.split(/\s+/)[0];
  return sha || null;
}

/**
 * 把远端车道分支拉到**远端跟踪引用**上。
 *
 * 为什么不是直接 fetch 到本地分支：本地分支被 A 的车道 worktree 检出着，
 * `git fetch remote branch:branch` 会被 git 以「分支已被检出」拒绝。
 * 远端跟踪引用永远不会被检出，所以能随时更新。
 *
 * **这正是「收割时必须先刷新」的原因** ——
 * 本地分支引用是陈旧的，用它判「票据还在不在」会把已交付的车道
 * 误判成未交付，于是永远收编不了（2026-10-19 集成测试抓到）。
 */
function fetchRemoteBranch(branch) {
  return git(['fetch', REMOTE, `+${branch}:refs/remotes/${REMOTE}/${branch}`], ROOT, true);
}

const remoteRef = (branch) => `refs/remotes/${REMOTE}/${branch}`;

/** 某个 ref 上票据文件是否仍存在（存在 = worker 还没交付）。 */
function ticketAliveAt(ref, id) {
  return git(['show', `${ref}:${ticketPath(id)}`], ROOT, true) !== null;
}

/**
 * 心跳：Mac B 推**带注释的 tag** `hb/<id>`，心跳时间 = tag 的 tagger 时间。
 *
 * 不用文件、不用 commit —— 每 25 分钟一条心跳 commit 会把车道分支历史冲垮。
 * 也不用自定义 ref 命名空间（`refs/hb/*`）：GitHub 只保证放行
 * `refs/heads/*` / `refs/tags/*` / `refs/notes/*`，自定义命名空间会被拒。
 */
function remoteHeartbeat(id) {
  const when = git(
    ['for-each-ref', '--format=%(taggerdate:iso-strict)', `refs/tags/hb/${id}`],
    ROOT,
    true,
  );
  if (!when) return null;
  const at = Date.parse(when.split('\n')[0].trim());
  if (!Number.isFinite(at)) return null;
  return { at: new Date(at).toISOString(), ageMinutes: Math.round((Date.now() - at) / 60000) };
}

/** 一次性把远端心跳 tag 拉下来（只拉 hb/* 命名空间，不动其它 tag）。 */
function fetchHeartbeatTags() {
  git(['fetch', REMOTE, `+refs/tags/hb/*:refs/tags/hb/*`], ROOT, true);
}

/* ── 命令 ─────────────────────────────────────────────────────────────── */

const cmd = process.argv[2] ?? 'status';

if (cmd === 'issue') {
  const id = String(flag('--lane') ?? '');
  const brief = flag('--brief');
  if (!id || !brief) {
    console.error('✗ 用法：issue --lane <模块ID> --brief <任务书文件>');
    process.exit(1);
  }
  if (!existsSync(brief)) {
    console.error(`✗ 任务书文件不存在：${brief}`);
    process.exit(1);
  }

  const state = readState();
  const lane = state.lanes[id];
  if (!lane) {
    console.error(`✗ lanes.json 里没有这条车道：${id}`);
    process.exit(1);
  }
  if (!lane.worktree || !existsSync(lane.worktree)) {
    console.error(`✗ 车道 worktree 不存在：${lane.worktree}（先跑 lane.mjs plan）`);
    process.exit(1);
  }

  // 远端若已有同名分支且内容不同，说明这条车道是陈旧的 —— 不能覆盖。
  const preRemote = remoteTip(lane.branch);
  const localTip = git(['rev-parse', lane.branch], ROOT, true);
  if (preRemote && preRemote !== localTip) {
    // 远端 tip 是本地 tip 的祖先 → 正常的快进场景，不算冲突
    const isAncestor = git(['merge-base', '--is-ancestor', preRemote, localTip], ROOT, true) === '';
    if (!isAncestor) {
      console.error(
        `✗ 远端已有 ${lane.branch} 且与本地分叉（远端 ${preRemote.slice(0, 7)} / 本地 ${String(localTip).slice(0, 7)}）。\n` +
          `  这是陈旧车道或有人在远端自己开了工。不要强推 —— 先人工核对，再 lane.mjs abandon。`,
      );
      process.exit(2);
    }
  }

  // 写票据到车道 worktree。
  // 头部由本脚本生成（不靠任务书作者手写）：Mac B 从票据头部读模块 ID，
  // 比从分支名 `feat/11-2-3-2` 反推可靠 —— 反推遇到分段歧义就废了。
  const rel = ticketPath(id);
  const abs = join(lane.worktree, rel);
  mkdirSync(dirname(abs), { recursive: true });
  const header = [
    '<!-- lane-ticket',
    `lane: ${id}`,
    `branch: ${lane.branch}`,
    `migrationVersion: ${lane.migrationVersion ?? ''}`,
    `base: ${lane.baseSha ?? ''}`,
    `l3: ${lane.l3 ?? ''}`,
    `stage: ${lane.stage ?? ''}`,
    `blueprint: ${lane.blueprint ?? ''}`,
    `issuedAt: ${new Date().toISOString()}`,
    'executor: remote',
    '-->',
    '',
  ].join('\n');
  writeFileSync(abs, header + readFileSync(brief, 'utf8'));

  const dirty = git(['status', '--porcelain', '--', rel], lane.worktree);
  if (!dirty) {
    console.error(`✗ 票据没有写进 ${rel}（工作区里看不到改动）`);
    process.exit(1);
  }

  const msg = `chore(lane): 签发跨机任务书 ${id} ${lane.name ?? ''}`.trim();
  git(['add', '--', rel], lane.worktree);
  git(['commit', '-m', msg], lane.worktree);

  const push = git(['push', REMOTE, `${lane.branch}:${lane.branch}`], lane.worktree, true);
  if (push === null) {
    // 强推被拒的典型原因：远端 tip 与本地分叉。上面已经拦过一次，这里是第二道。
    console.error(`✗ push ${lane.branch} 到 ${REMOTE} 失败。远端状态：\n${git(['ls-remote', '--heads', REMOTE, `refs/heads/${lane.branch}`], ROOT, true) ?? '(无)'}`);
    process.exit(3);
  }

  lane.executor = 'remote';
  lane.ticket = { path: rel, issuedAt: new Date().toISOString(), remote: REMOTE };
  lane.remoteTipAtIssue = git(['rev-parse', lane.branch], ROOT, true);
  writeState(state);

  console.log(
    JSON.stringify(
      {
        ok: true,
        action: 'issue',
        lane: id,
        branch: lane.branch,
        migrationVersion: lane.migrationVersion,
        ticket: rel,
        remote: REMOTE,
        note: 'Mac B 会在下一次轮询（≤5 分钟）领走这条车道',
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'adopt') {
  const id = String(flag('--lane') ?? '');
  const state = readState();
  const lane = state.lanes[id];
  if (!lane) {
    console.error(`✗ lanes.json 里没有这条车道：${id}`);
    process.exit(1);
  }
  if (lane.state !== 'running') {
    console.error(`✗ 车道 ${id} 当前状态是 ${lane.state}，只有 running 才能收编`);
    process.exit(1);
  }

  // A 本地为远端车道建的 worktree 对收编没有用，且它占着分支 → 必须先摘掉，
  // 否则 `git fetch remote branch:branch` 会因为「分支已被某个 worktree 检出」被拒。
  if (lane.worktree && existsSync(lane.worktree)) {
    sh(`git worktree remove --force ${JSON.stringify(lane.worktree)}`, ROOT);
    git(['worktree', 'prune'], ROOT, true);
  }

  // 快进拉取远端提交。**不加 `+`**：分叉就报错，不覆盖 B 的工作。
  const fetched = git(['fetch', REMOTE, `${lane.branch}:${lane.branch}`], ROOT, true);
  if (fetched === null) {
    console.error(
      `✗ 拉取 ${lane.branch} 失败 —— 远端与本地分叉，或远端还没有这条分支。\n` +
        `  分叉意味着 B 在拿到票据前就自己开工了，属真冲突，必须人工核对，不要强推。`,
    );
    process.exit(2);
  }

  const tip = git(['rev-parse', lane.branch], ROOT, true);
  if (!tip) {
    console.error(`✗ 本地没有 ${lane.branch}`);
    process.exit(1);
  }
  if (tip === lane.baseSha) {
    console.error(`✗ ${lane.branch} 的 tip 仍等于基线 ${lane.baseSha} —— 远端没有新提交，不能收编`);
    process.exit(1);
  }
  if (ticketAliveAt(lane.branch, id)) {
    console.error(`✗ 远端票据仍在（${ticketPath(id)}）—— worker 还没走完交付流程，不能收编`);
    process.exit(1);
  }

  lane.state = 'ready';
  lane.headSha = tip;
  lane.landedAt = new Date().toISOString();
  lane.landedBy = 'remote-adopt';
  writeState(state);

  console.log(
    JSON.stringify({ ok: true, action: 'adopt', lane: id, head: tip.slice(0, 7), branch: lane.branch }, null, 2),
  );
  process.exit(0);
}

if (cmd === 'harvest') {
  const state = readState();
  fetchHeartbeatTags();
  const remoteLanes = Object.values(state.lanes).filter(
    (l) => l.executor === 'remote' && l.state === 'running',
  );
  const adopted = [];
  const skipped = [];
  for (const lane of remoteLanes) {
    const tip = remoteTip(lane.branch);
    if (!tip) {
      skipped.push({ lane: lane.id, why: '远端还没有这条分支' });
      continue;
    }
    if (tip === lane.baseSha) {
      skipped.push({ lane: lane.id, why: '远端 tip 仍等于基线' });
      continue;
    }
    // **先刷新远端跟踪引用，再判票据。**
    // 用陈旧的本地分支判「票据还在不在」，会把已交付的车道误判成未交付 ——
    // 收割永远收编不了它，交付物就烂在远端（2026-10-19 集成测试抓到）。
    fetchRemoteBranch(lane.branch);
    if (ticketAliveAt(remoteRef(lane.branch), lane.id)) {
      const hb = remoteHeartbeat(lane.id);
      skipped.push({
        lane: lane.id,
        why: '票据仍在（未交付）',
        heartbeat: hb ?? null,
        stale: hb ? hb.ageMinutes > TTL_MINUTES : null,
      });
      continue;
    }
    // 走一遍 adopt 的完整校验
    const r = execFileSync('node', [join(HERE, 'remote-lane.mjs'), 'adopt', '--lane', lane.id], {
      cwd: ROOT,
      encoding: 'utf8',
    });
    adopted.push({ lane: lane.id, detail: r.trim() });
  }
  console.log(JSON.stringify({ ok: true, action: 'harvest', adopted, skipped }, null, 2));
  process.exit(0);
}

if (cmd === 'status') {
  const state = readState();
  fetchHeartbeatTags();
  const rows = Object.values(state.lanes)
    .filter((l) => l.executor === 'remote')
    .map((l) => {
      const tip = remoteTip(l.branch);
      fetchRemoteBranch(l.branch);
      const hb = remoteHeartbeat(l.id);
      return {
        lane: l.id,
        name: l.name,
        state: l.state,
        branch: l.branch,
        migrationVersion: l.migrationVersion,
        remoteTip: tip ? tip.slice(0, 7) : null,
        ticketAlive: tip ? ticketAliveAt(remoteRef(l.branch), l.id) : null,
        heartbeat: hb,
        stale: hb ? hb.ageMinutes > TTL_MINUTES : null,
        ageMinutes: Math.round((Date.now() - new Date(l.heartbeatAt ?? l.createdAt).getTime()) / 60000),
      };
    });
  console.log(JSON.stringify({ ok: true, count: rows.length, lanes: rows }, null, 2));
  process.exit(0);
}

console.error(`未知命令：${cmd}（可用：issue / adopt / harvest / status）`);
process.exit(1);
