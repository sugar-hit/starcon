#!/usr/bin/env node
/**
 * 跨机车道 —— Mac B（执行者）侧：轮询 / 领票 / 读任务书 / 心跳 / 交付。
 *
 * Mac B 只做四件事，其余一律不做：
 *   1. 领票（把远端车道分支拉成本地 worktree）
 *   2. 干活（由调度者派发的 worker 子任务完成）
 *   3. 交付（删票据 + 提交 + push 车道分支）
 *   4. 心跳（打一个 tag，让 Mac A 知道这条车道还活着）
 *
 * Mac B **不做**：plan / merge-ready / L2 全量验证 / 改台账 / push 集成头。
 * 那些都是 Mac A 的职责 —— 共享状态只允许一个写者，否则跨机冲突无法根除。
 *
 * 本地状态存在 `.dev-agent/remote-claims.json`（整个 `.dev-agent/` 被 gitignore，
 * 所以它是本机私有的，不会被推上去）。
 *
 * 用法：
 *   node tools/module-progress/remote-worker.mjs poll
 *   node tools/module-progress/remote-worker.mjs claim  --lane <模块ID>
 *   node tools/module-progress/remote-worker.mjs read   --lane <模块ID>
 *   node tools/module-progress/remote-worker.mjs heartbeat --lane <模块ID>
 *   node tools/module-progress/remote-worker.mjs finish --lane <模块ID> --report <报告文件>
 *   node tools/module-progress/remote-worker.mjs release --lane <模块ID> --why <原因>
 *   node tools/module-progress/remote-worker.mjs status
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = process.env.LANE_ROOT ? resolve(process.env.LANE_ROOT) : join(HERE, '..', '..');
const STATE_DIR = join(ROOT, '.dev-agent');
const CLAIM_FILE = join(STATE_DIR, 'remote-claims.json');
const WORKTREE_DIR = join(ROOT, '.worktrees');

const TICKET_DIR = '.lane-tickets';
const REMOTE = process.env.LANE_REMOTE ?? 'upstream';

/* ── git 封装 ─────────────────────────────────────────────────────────── */

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

const flag = (name) => {
  const i = process.argv.indexOf(name);
  if (i === -1) return undefined;
  const next = process.argv[i + 1];
  return next && !next.startsWith('--') ? next : true;
};

/* ── 本机领票状态 ─────────────────────────────────────────────────────── */

const readClaims = () => {
  try {
    const s = JSON.parse(readFileSync(CLAIM_FILE, 'utf8'));
    s.lanes ??= {};
    return s;
  } catch {
    return { lanes: {} };
  }
};

const writeClaims = (s) => {
  mkdirSync(STATE_DIR, { recursive: true });
  writeFileSync(CLAIM_FILE, `${JSON.stringify(s, null, 2)}\n`);
};

/* ── 票据解析 ─────────────────────────────────────────────────────────── */

/** 从票据正文里读机器可读头部。 */
function parseTicket(text) {
  const m = /<!--\s*lane-ticket([\s\S]*?)-->/.exec(text);
  if (!m) return null;
  const meta = {};
  for (const line of m[1].split('\n')) {
    const kv = /^\s*([a-zA-Z]+):\s*(.*?)\s*$/.exec(line);
    if (kv) meta[kv[1]] = kv[2];
  }
  return meta;
}

const ticketPathOf = (id) => `${TICKET_DIR}/${id}.md`;

/* ── 命令 ─────────────────────────────────────────────────────────────── */

const cmd = process.argv[2] ?? 'status';

if (cmd === 'poll') {
  // 一次 fetch 拿到所有远端分支。--prune 清掉已删除的。
  const fetched = git(['fetch', REMOTE, '--prune'], ROOT, true);
  if (fetched === null) {
    console.error(`✗ fetch ${REMOTE} 失败 —— 检查网络与远端凭据`);
    process.exit(1);
  }
  const refs = git(['for-each-ref', '--format=%(refname:short)', `refs/remotes/${REMOTE}/feat/`], ROOT, true);
  const claims = readClaims();
  const unclaimed = [];
  const inFlight = [];
  for (const ref of (refs ?? '').split('\n').map((s) => s.trim()).filter(Boolean)) {
    const branch = ref.replace(`${REMOTE}/`, '');
    // 票据文件名就是模块 ID；用目录列表找，避免从分支名反推。
    // **必须带 -r**：不带的话 ls-tree 列的是 `.lane-tickets` 这个目录项本身，
    // 永远拿不到里面的文件，poll 会一直返回空数组（2026-10-19 实测踩到）。
    const listing = git(['ls-tree', '-r', '--name-only', ref, TICKET_DIR], ROOT, true);
    if (!listing) continue;
    const files = listing.split('\n').map((s) => s.trim()).filter((f) => f.endsWith('.md'));
    for (const f of files) {
      const id = f.replace(/^.*\//, '').replace(/\.md$/, '');
      const body = git(['show', `${ref}:${f}`], ROOT, true);
      const meta = body ? parseTicket(body) : null;
      const rec = {
        lane: id,
        branch,
        remoteRef: ref,
        ticket: f,
        migrationVersion: meta?.migrationVersion ?? null,
        l3: meta?.l3 ?? null,
        stage: meta?.stage ?? null,
        claimed: Boolean(claims.lanes[id]),
        claimAgeMinutes: claims.lanes[id]
          ? Math.round((Date.now() - new Date(claims.lanes[id].claimedAt).getTime()) / 60000)
          : null,
      };
      if (rec.claimed) inFlight.push(rec);
      else unclaimed.push(rec);
    }
  }
  console.log(JSON.stringify({ ok: true, unclaimed, inFlight }, null, 2));
  process.exit(0);
}

if (cmd === 'claim') {
  const id = String(flag('--lane') ?? '');
  if (!id) {
    console.error('✗ 用法：claim --lane <模块ID>');
    process.exit(1);
  }
  const claims = readClaims();
  if (claims.lanes[id]) {
    console.error(`✗ ${id} 已经在本机领过了（${claims.lanes[id].claimedAt}）`);
    process.exit(2);
  }

  git(['fetch', REMOTE, '--prune'], ROOT, true);
  const ref = `${REMOTE}/feat/${id.replace(/\./g, '-')}`;
  const body = git(['show', `${ref}:${ticketPathOf(id)}`], ROOT, true);
  if (body === null) {
    console.error(`✗ 远端 ${ref} 上没有票据 ${ticketPathOf(id)} —— 车道还没签发，或已被领走`);
    process.exit(1);
  }
  const meta = parseTicket(body) ?? {};

  // 本地分支快进到远端 tip，然后建 worktree
  const branch = meta.branch ?? `feat/${id.replace(/\./g, '-')}`;
  const ff = git(['fetch', REMOTE, `${branch}:${branch}`], ROOT, true);
  if (ff === null) {
    console.error(`✗ 本地 ${branch} 与远端分叉，无法快进`);
    process.exit(3);
  }
  const wt = join(WORKTREE_DIR, id);
  if (existsSync(wt)) {
    console.error(`✗ worktree 已存在：${wt}（可能上一次没收干净）`);
    process.exit(4);
  }
  mkdirSync(WORKTREE_DIR, { recursive: true });
  git(['worktree', 'add', wt, branch], ROOT);
  // 依赖装不上不该让「领票」失败 —— 票已经领到了，worktree 也在。
  // 装不上是环境问题，交给 worker 任务书里的自检环节处理并回报 RESOURCE_BLOCKED。
  try {
    if (existsSync(join(wt, 'package.json'))) sh('pnpm install --prefer-offline', wt);
  } catch (err) {
    console.error(`⚠ pnpm install 失败（领票不受影响，worker 会再试）：${err.message}`);
  }

  claims.lanes[id] = {
    lane: id,
    branch,
    worktree: wt,
    migrationVersion: meta.migrationVersion ?? null,
    claimedAt: new Date().toISOString(),
    heartbeatAt: new Date().toISOString(),
    state: 'claimed',
  };
  writeClaims(claims);
  console.log(JSON.stringify({ ok: true, action: 'claim', lane: id, branch, worktree: wt }, null, 2));
  process.exit(0);
}

if (cmd === 'read') {
  const id = String(flag('--lane') ?? '');
  const claims = readClaims();
  const rec = claims.lanes[id];
  const wt = rec?.worktree ?? join(WORKTREE_DIR, id);
  const file = join(wt, ticketPathOf(id));
  if (!existsSync(file)) {
    console.error(`✗ 找不到票据：${file}`);
    process.exit(1);
  }
  process.stdout.write(readFileSync(file, 'utf8'));
  process.exit(0);
}

if (cmd === 'heartbeat') {
  const id = String(flag('--lane') ?? '');
  const claims = readClaims();
  const rec = claims.lanes[id];
  if (!rec) {
    console.error(`✗ 本机没有领这条车道：${id}`);
    process.exit(1);
  }
  const now = new Date();
  const tag = `hb/${id}`;
  // 用**带注释的 tag** 记录心跳：tagger 时间就是心跳时间。
  // 不用文件、不用 commit —— 否则每 25 分钟一条心跳 commit 会把车道分支历史冲垮。
  sh(
    `git tag -f -a ${JSON.stringify(tag)} -m "lane heartbeat ${now.toISOString()}" HEAD`,
    rec.worktree,
  );
  const pushed = git(['push', '--force', REMOTE, `refs/tags/${tag}:refs/tags/${tag}`], rec.worktree, true);
  if (pushed === null) {
    console.error(`✗ 推心跳 tag 失败：${tag}`);
    process.exit(1);
  }
  rec.heartbeatAt = now.toISOString();
  writeClaims(claims);
  console.log(JSON.stringify({ ok: true, lane: id, tag, at: rec.heartbeatAt }, null, 2));
  process.exit(0);
}

if (cmd === 'finish') {
  const id = String(flag('--lane') ?? '');
  const reportPath = flag('--report');
  const claims = readClaims();
  const rec = claims.lanes[id];
  if (!rec) {
    console.error(`✗ 本机没有领这条车道：${id}`);
    process.exit(1);
  }
  if (!reportPath || !existsSync(reportPath)) {
    console.error(`✗ 用法：finish --lane <id> --report <报告文件>（报告文件不存在）`);
    process.exit(1);
  }
  const report = readFileSync(reportPath, 'utf8').trim();

  // 删掉票据（= 完成信号，Mac A 靠它判断）—— 删之前先确认工作区里没有别的东西没收干净。
  const dirty = git(['status', '--porcelain'], rec.worktree);
  const unexpected = (dirty ?? '')
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
    .filter((s) => !s.endsWith(` ${ticketPathOf(id)}`));
  if (unexpected.length) {
    console.error(
      `✗ worktree 里有未预期的改动，拒绝交付：\n${unexpected.join('\n')}\n` +
        '  票据与心跳文件会被自动删除；其余改动必须先提交。',
    );
    process.exit(1);
  }

  git(['rm', '--quiet', '--', ticketPathOf(id)], rec.worktree);

  const msg = `chore(lane): 交付 ${id}\n\n${report}`;
  git(['commit', '-m', msg], rec.worktree);

  const pushed = git(['push', REMOTE, `${rec.branch}:${rec.branch}`], rec.worktree, true);
  if (pushed === null) {
    console.error(`✗ 推 ${rec.branch} 到 ${REMOTE} 失败。远端状态：\n${git(['ls-remote', '--heads', REMOTE, `refs/heads/${rec.branch}`], ROOT, true) ?? '(无)'}`);
    process.exit(2);
  }
  const head = git(['rev-parse', 'HEAD'], rec.worktree);
  rec.state = 'delivered';
  rec.deliveredAt = new Date().toISOString();
  rec.headSha = head;
  writeClaims(claims);
  console.log(
    JSON.stringify(
      { ok: true, action: 'finish', lane: id, branch: rec.branch, head: head?.slice(0, 7), pushed: true },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'release') {
  const id = String(flag('--lane') ?? '');
  const why = flag('--why') ?? '未说明原因';
  const claims = readClaims();
  const rec = claims.lanes[id];
  if (!rec) {
    console.error(`✗ 本机没有领这条车道：${id}`);
    process.exit(1);
  }
  if (rec.worktree && existsSync(rec.worktree)) {
    sh(`git worktree remove --force ${JSON.stringify(rec.worktree)}`, ROOT);
    git(['worktree', 'prune'], ROOT, true);
  }
  rec.state = 'released';
  rec.releasedAt = new Date().toISOString();
  rec.reason = why;
  writeClaims(claims);
  console.log(JSON.stringify({ ok: true, action: 'release', lane: id, why }, null, 2));
  process.exit(0);
}

if (cmd === 'status') {
  const claims = readClaims();
  const rows = Object.values(claims.lanes).map((r) => ({
    ...r,
    ageMinutes: Math.round((Date.now() - new Date(r.heartbeatAt ?? r.claimedAt).getTime()) / 60000),
    worktreeExists: r.worktree ? existsSync(r.worktree) : false,
  }));
  console.log(JSON.stringify({ ok: true, count: rows.length, lanes: rows }, null, 2));
  process.exit(0);
}

console.error(`未知命令：${cmd}（可用：poll / claim / read / heartbeat / finish / release / status）`);
process.exit(1);
