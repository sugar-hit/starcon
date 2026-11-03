#!/usr/bin/env node
/**
 * 合 main 任务的合并锁（main-merge lock）
 *
 * 为什么要有它 —— 2026-11-03 实测的根因：
 *   「每日合 main」任务原来复用流水线的**调度闸**（`.dev-agent/dispatch.gate`），
 *   而调度闸保护的是**集成头 worktree 的本地状态**。流水线一轮 L2 可持闸 40 分钟～4h46m
 *   （实测记录见 docs/sessions/2026-10/2026-10-30-1249-*.md），
 *   于是合 main 任务几乎每轮都撞上 `held` + `stale=false` ⇒ 按协议「不要抢，只读 + 汇报」
 *   ⇒ **整轮空转，main 一直停在 10-30 的台账，交付看板因此长期显示过期数字**。
 *
 *   但合 main 的真正动作（推同步分支 / 建 PR / 合并 PR）**不改集成头 worktree** ——
 *   它只写远端分支与远端 PR。所以它本来就不需要调度闸，需要的只是
 *   **「同一时刻只有一轮合 main 在动」** 这一个互斥。
 *
 * 两个锁的分工（改完之后）：
 *   - 调度闸（integration worktree 的 dispatch.gate）
 *       ⇒ 保护「集成头 worktree 的本地状态」。流水线用它；
 *         合 main 任务**只在第 1 步（把 main 的新提交同步进集成头）**用它。
 *   - 合并锁（本脚本，落在 git common dir）
 *       ⇒ 保护「合 main 的远端动作」。合 main 任务的第 2 步用它。
 *
 * 与调度闸的三点差别（都是踩过坑之后刻意做的）：
 *   1. **落在 git common dir** ⇒ 所有 worktree 共享同一个锁，主工作区与集成头看到的是同一份。
 *   2. **靠 owner 令牌而不是 pid 认主** ⇒ 本脚本是一次性短命进程，
 *      用 pid 认主会让锁在下一个命令到来时立刻变成「持锁者已死」。
 *      owner 由调用方在整轮里保持不变（一轮一个令牌），release/touch 都按它校验。
 *   3. **release 只删自己的** ⇒ 调度闸的 `release` 是无条件 `rmSync`，
 *      会把别人活着的闸删掉（清闸）；本脚本带 owner 校验，不属于自己就拒绝删。
 *
 * 用法（一轮内 owner 必须保持不变）：
 *   OWNER="main-merge-$(date +%Y%m%d-%H%M%S)-$$"
 *   node tools/module-progress/merge-lock.mjs acquire --owner "$OWNER" --note "PR#72"
 *   node tools/module-progress/merge-lock.mjs touch   --owner "$OWNER"   # 等 CI 久了可续期
 *   node tools/module-progress/merge-lock.mjs release --owner "$OWNER"
 *
 * 退出码：
 *   0 = 成功（acquired / released / touched / 查到锁）
 *   3 = 撞锁（有人持着且未 stale）/ 不是自己的锁
 */

import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..');

/** 锁文件放在 git common dir ⇒ 所有 linked worktree 共享同一份 */
const GIT_COMMON = execFileSync('git', ['rev-parse', '--git-common-dir'], {
  cwd: REPO,
  encoding: 'utf8',
}).trim();
const LOCK_DIR = GIT_COMMON.startsWith('/') ? GIT_COMMON : join(REPO, GIT_COMMON);
const LOCK_FILE = join(LOCK_DIR, 'main-merge.lock');

/** 心跳超过这个秒数即视为 stale，可被接管 */
const TTL_SECONDS = 1800;

const flag = (name) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : null;
};

const read = () => {
  try {
    return JSON.parse(readFileSync(LOCK_FILE, 'utf8'));
  } catch {
    return null;
  }
};

const evaluate = () => {
  const lock = read();
  if (!lock) return { state: 'free', lock: null, ageSeconds: null, reason: null };
  const hb = lock.heartbeatAt || lock.acquiredAt;
  const ageSeconds = Math.max(0, Math.round((Date.now() - new Date(hb).getTime()) / 1000));
  if (ageSeconds > TTL_SECONDS) {
    return {
      state: 'stale',
      lock,
      ageSeconds,
      reason: `心跳已过期 ${ageSeconds}s（上限 ${TTL_SECONDS}s）`,
    };
  }
  return { state: 'busy', lock, ageSeconds, reason: '持锁者心跳正常' };
};

const cmd = process.argv[2] ?? 'status';
const owner = flag('owner');

if (cmd === 'status') {
  const st = evaluate();
  console.log(
    JSON.stringify(
      {
        ok: true,
        lock: st.state,
        ageSeconds: st.ageSeconds,
        ttlSeconds: TTL_SECONDS,
        reason: st.reason,
        owner: st.lock?.owner ?? null,
        holderPid: st.lock?.pid ?? null,
        holderNote: st.lock?.note ?? null,
        acquiredAt: st.lock?.acquiredAt ?? null,
        heartbeatAt: st.lock?.heartbeatAt ?? null,
        file: LOCK_FILE,
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'acquire') {
  if (!owner) {
    console.error('acquire 必须带 --owner <令牌>（整轮保持不变，release/touch 用同一个）');
    process.exit(2);
  }
  const st = evaluate();
  if (st.state === 'busy') {
    console.log(
      JSON.stringify(
        {
          ok: false,
          lock: 'held-by-other',
          owner: st.lock.owner,
          holderPid: st.lock.pid,
          ageSeconds: st.ageSeconds,
          reason: st.reason,
        },
        null,
        2,
      ),
    );
    process.exit(3);
  }
  mkdirSync(LOCK_DIR, { recursive: true });
  const now = new Date().toISOString();
  const payload = {
    owner,
    pid: process.pid,
    acquiredAt: now,
    heartbeatAt: now,
    note: flag('note') || '',
    ttlSeconds: TTL_SECONDS,
  };
  writeFileSync(LOCK_FILE, JSON.stringify(payload, null, 2));
  console.log(
    JSON.stringify(
      {
        ok: true,
        lock: 'acquired',
        reclaimed: st.state === 'stale',
        reclaimReason: st.state === 'stale' ? st.reason : null,
        owner,
        file: LOCK_FILE,
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'touch') {
  if (!owner) {
    console.error('touch 必须带 --owner <令牌>');
    process.exit(2);
  }
  const st = evaluate();
  if (!st.lock || st.lock.owner !== owner) {
    console.log(
      JSON.stringify(
        {
          ok: false,
          lock: 'not-mine',
          owner: st.lock?.owner ?? null,
          reason: '锁不属于本轮，拒绝续期',
        },
        null,
        2,
      ),
    );
    process.exit(3);
  }
  st.lock.heartbeatAt = new Date().toISOString();
  writeFileSync(LOCK_FILE, JSON.stringify(st.lock, null, 2));
  console.log(JSON.stringify({ ok: true, lock: 'refreshed', owner }, null, 2));
  process.exit(0);
}

if (cmd === 'release') {
  if (!owner) {
    console.error('release 必须带 --owner <令牌>');
    process.exit(2);
  }
  const st = evaluate();
  if (!st.lock) {
    console.log(JSON.stringify({ ok: true, lock: 'not-held', reason: '没有锁文件' }, null, 2));
    process.exit(0);
  }
  if (st.lock.owner !== owner) {
    console.log(
      JSON.stringify(
        {
          ok: false,
          lock: 'not-mine',
          owner: st.lock.owner,
          reason: '锁不属于本轮，拒绝删除（不许清别人的锁）',
        },
        null,
        2,
      ),
    );
    process.exit(3);
  }
  rmSync(LOCK_FILE, { force: true });
  console.log(JSON.stringify({ ok: true, lock: 'released', owner }, null, 2));
  process.exit(0);
}

console.error(`未知命令：${cmd}（可用：status / acquire / touch / release）`);
process.exit(2);
