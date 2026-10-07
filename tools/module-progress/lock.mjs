#!/usr/bin/env node
/**
 * 开发任务互斥锁
 *
 * 为什么需要：定时任务每小时触发一次，但一个模块的开发远超一小时。
 * 没有互斥就会出现两个任务同时改同一个仓库、抢同一条 git 分支。
 *
 * 为什么用文件锁而不是查会话列表：会话列表跨会话看不到「这个会话是不是在开发」，
 * 文件锁是落在项目里的、可审计的、跨会话一定读得到的真相。
 *
 * 心跳过期即视为可抢占：子任务崩了不会永久卡死流水线，
 * 下一个整点会自动接管（接管前会重新核对 git 状态，不盲信锁里的进度描述）。
 *
 * 用法：
 *   node tools/module-progress/lock.mjs status
 *   node tools/module-progress/lock.mjs acquire --module 7.1.1.1 --branch feat/inventory-ledger
 *   node tools/module-progress/lock.mjs heartbeat
 *   node tools/module-progress/lock.mjs release
 */

import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const LOCK_DIR = join(ROOT, '.dev-agent');
const LOCK_FILE = join(LOCK_DIR, 'dev-task.json');

/** 心跳超过这个分钟数即视为失联，下一个整点可抢占 */
const TTL_MINUTES = 90;

const read = () => {
  try {
    return JSON.parse(readFileSync(LOCK_FILE, 'utf8'));
  } catch {
    return null;
  }
};

/** 把锁的真实状态算出来：free / busy / stale */
const evaluate = () => {
  const lock = read();
  if (!lock) return { state: 'free', lock: null };

  const ageMin = (Date.now() - new Date(lock.heartbeatAt).getTime()) / 60000;
  if (!Number.isFinite(ageMin) || ageMin > TTL_MINUTES) {
    return { state: 'stale', ageMinutes: Math.round(ageMin), lock };
  }
  return { state: 'busy', ageMinutes: Math.round(ageMin), lock };
};

const flag = (name) => {
  const i = process.argv.indexOf(name);
  if (i === -1) return undefined;
  const next = process.argv[i + 1];
  return next && !next.startsWith('--') ? next : true;
};

const [cmd = 'status'] = process.argv.slice(2);

if (cmd === 'status') {
  const s = evaluate();
  console.log(
    JSON.stringify(
      {
        state: s.state,
        ttlMinutes: TTL_MINUTES,
        ageMinutes: s.ageMinutes ?? null,
        ...(s.lock
          ? {
              module: s.lock.module,
              moduleName: s.lock.moduleName ?? null,
              branch: s.lock.branch,
              sessionId: s.lock.sessionId ?? null,
              acquiredAt: s.lock.acquiredAt,
              heartbeatAt: s.lock.heartbeatAt,
            }
          : {}),
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'acquire') {
  const s = evaluate();
  if (s.state === 'busy') {
    console.error(
      `✗ 已有开发任务在执行：${s.lock.module} ${s.lock.moduleName ?? ''}（分支 ${s.lock.branch}，心跳 ${s.ageMinutes} 分钟前）`,
    );
    process.exit(2);
  }

  const moduleId = flag('--module');
  if (!moduleId || typeof moduleId !== 'string') {
    console.error('✗ 缺少 --module <模块 ID>');
    process.exit(1);
  }

  mkdirSync(LOCK_DIR, { recursive: true });
  const now = new Date().toISOString();
  const lock = {
    module: moduleId,
    moduleName: flag('--module-name') ?? null,
    branch: flag('--branch') ?? null,
    sessionId: flag('--session') ?? null,
    acquiredAt: now,
    heartbeatAt: now,
  };
  writeFileSync(LOCK_FILE, `${JSON.stringify(lock, null, 2)}\n`);
  console.log(
    `✓ 已加锁 ${moduleId}${lock.moduleName ? ` ${lock.moduleName}` : ''}` +
      `${s.state === 'stale' ? '（已抢占失联任务）' : ''}`,
  );
  process.exit(0);
}

if (cmd === 'heartbeat') {
  const lock = read();
  if (!lock) {
    console.error('✗ 没有锁可续，先 acquire');
    process.exit(1);
  }
  lock.heartbeatAt = new Date().toISOString();
  if (flag('--note')) lock.note = flag('--note');
  writeFileSync(LOCK_FILE, `${JSON.stringify(lock, null, 2)}\n`);
  console.log(`✓ 心跳已续 ${lock.module}`);
  process.exit(0);
}

if (cmd === 'release') {
  if (!existsSync(LOCK_FILE)) {
    console.log('✓ 无锁，无事可做');
    process.exit(0);
  }
  const lock = read();
  rmSync(LOCK_FILE, { force: true });
  console.log(`✓ 已释放 ${lock?.module ?? '未知模块'}`);
  process.exit(0);
}

console.error(`未知命令：${cmd}（可用：status / acquire / heartbeat / release）`);
process.exit(1);
