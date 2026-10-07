#!/usr/bin/env node
/**
 * 并行车道调度器
 *
 * 背景：原来只有一把全局互斥锁（lock.mjs），一次只能跑一个模块。
 * 实测单个模块 35–40 分钟，465 个模块里还有 409 个没开发 ——
 * 串行跑完要 10 天，而同一天里 D1–D2 这 26 个未开发模块就散落在 14 个不同的 L3 锚点上，
 * 彼此并不需要排队。原来的调度等于把 14 条互不相干的路堵成了一条。
 *
 * 这个脚本把「一次一个模块」改成「N 条车道并行」：
 *   - 每条车道 = 独立 git worktree + 独立分支 + 独立依赖，从同一集成头切出
 *   - Flyway 版本号由调度器**预先分配**，避免两条车道抢同一个 V27
 *   - 车道跑完各自 commit，调度器再统一并入集成头，冲突交给 merge-union 驱动器按键求并
 *
 * 状态落在 .dev-agent/lanes.json（项目内、可审计、跨会话读得到）。
 *
 * 用法：
 *   node tools/module-progress/lane.mjs init
 *   node tools/module-progress/lane.mjs status
 *   node tools/module-progress/lane.mjs plan --lanes 4
 *   node tools/module-progress/lane.mjs heartbeat --lane 3.5.2.2 --note "迁移写完了"
 *   node tools/module-progress/lane.mjs land --lane 3.5.2.2
 *   node tools/module-progress/lane.mjs merge-ready
 *   node tools/module-progress/lane.mjs abandon --lane 3.5.2.2 --reason "依赖未就绪"
 *   node tools/module-progress/lane.mjs gc
 */

import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
  rmSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const STATE_DIR = join(ROOT, '.dev-agent');
const STATE_FILE = join(STATE_DIR, 'lanes.json');
const WORKTREE_DIR = join(ROOT, '.worktrees');
const LEDGER = join(ROOT, 'docs/product/capability-blueprint/module-progress.md');
const BLUEPRINT_DIR = join(ROOT, 'docs/product/capability-blueprint');
const INTEGRATION = 'integration/module-dev';

/** 心跳超过这个分钟数即视为失联，车道可以被回收重派 */
const TTL_MINUTES = 90;

/** 阶段依赖就绪顺序 —— 与台账「跨期说明」一致，不要自己另排一套 */
const STAGE_ORDER = ['D1–D2', 'D3', 'D4', 'D5', 'D6', 'D7'];

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

/* ── 状态读写 ─────────────────────────────────────────────────────────── */

const readState = () => {
  try {
    const s = JSON.parse(readFileSync(STATE_FILE, 'utf8'));
    s.lanes ??= {};
    return s;
  } catch {
    return { integrationBranch: INTEGRATION, lanes: {} };
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

/* ── 台账解析 ─────────────────────────────────────────────────────────── */

/** 返回 [{ id, name, route, stage, status, evidence }] */
function readLedger() {
  const rows = [];
  for (const line of readFileSync(LEDGER, 'utf8').split('\n')) {
    if (!/^\|\s*\d+(?:\.\d+)+\s*\|/.test(line)) continue;
    const cells = line
      .split('|')
      .slice(1, -1)
      .map((c) => c.trim());
    if (cells.length < 6) continue;
    rows.push({
      id: cells[0],
      name: cells[1],
      route: cells[2],
      stage: cells[3],
      status: cells[4],
      evidence: cells[5] ?? '',
    });
  }
  return rows;
}

/** 模块 ID 前两位是卷号（3.5.2.2 → 卷 03），用来定位蓝图文件 */
function blueprintFile(id) {
  const vol = id.split('.')[0].padStart(2, '0');
  const hit = readdirSync(BLUEPRINT_DIR).find((f) => f.startsWith(`${vol}-`) && f.endsWith('.md'));
  return hit ? join(BLUEPRINT_DIR, hit) : null;
}

/* ── Flyway 版本号分配 ────────────────────────────────────────────────── */

/** 扫全部服务的迁移目录，返回已占用的版本号集合 */
function usedVersions() {
  const used = new Set();
  const base = join(ROOT, 'backend/services');
  if (!existsSync(base)) return used;
  for (const svc of readdirSync(base)) {
    const dir = join(base, svc, 'src/main/resources/db/migration');
    if (!existsSync(dir)) continue;
    for (const f of readdirSync(dir)) {
      const m = /^V(\d+)__/.exec(f);
      if (m) used.add(Number(m[1]));
    }
  }
  return used;
}

/**
 * 分配 count 个全局未占用的版本号。
 * 为什么全局唯一而不是「每个服务各自往后排」：并行车道可能同时改同一个服务，
 * 全局唯一分配可以让「哪条车道拿哪个号」变成调度器的单点决定，车道之间无需协商。
 */
function allocateVersions(count) {
  const used = usedVersions();
  const out = [];
  let n = Math.max(0, ...used);
  while (out.length < count) {
    n += 1;
    if (!used.has(n)) out.push(n);
  }
  return out;
}

/* ── 蓝图依赖图：谁是谁的上游 ─────────────────────────────────────────── */

/**
 * 从 18 卷蓝图里抽出每个模块声明的「上游」。
 *
 * 为什么必须读这个，不能只看模块 ID 顺序：蓝图的「关系」字段是显式依赖声明。
 * 实测 3.6.2.1 供应商报价写着「上游 = 3.6.1.2」，而 3.6.1.2 自己还没开发 ——
 * 只按 ID 顺序排队会把「必须串联」的一对排成并行，worker 写到一半才发现上游的表不存在，
 * 整条车道的 40 分钟就白烧了。493KB 蓝图每次 plan 全量解析一次，代价可忽略。
 */
function readUpstreamMap() {
  const map = new Map();
  const L4 = /\b(\d+\.\d+\.\d+\.\d+)\b/g;

  for (const f of readdirSync(BLUEPRINT_DIR)) {
    if (!f.endsWith('.md') || f === 'README.md' || f === 'module-progress.md') continue;
    const text = readFileSync(join(BLUEPRINT_DIR, f), 'utf8');
    for (const sec of text.split(/^###\s+/m).slice(1)) {
      const id = /^(\d+\.\d+\.\d+\.\d+)/.exec(sec)?.[1];
      if (!id) continue;
      const rel = /\*\*关系\*\*[：:]([^\n]*)/.exec(sec)?.[1] ?? '';
      const upPart = /上游\s*[=：:]\s*([^；;。\n]*)/.exec(rel)?.[1] ?? '';
      map.set(
        id,
        [...upPart.matchAll(L4)].map((m) => m[1]),
      );
    }
  }
  return map;
}

/* ── 模块选取：L3 锚点互不相同 + 上游必须已交付 ───────────────────────── */

const l3Of = (id) => id.split('.').slice(0, 3).join('.');

/**
 * 挑 count 个互相独立、且依赖已就绪的模块。
 *
 * 两条硬约束：
 *   1. 上游全部已交付 —— 蓝图的「关系」字段声明了必须串联的依赖，没就绪就不能并行开工。
 *   2. L3 锚点互不相同 —— 同一个 L3 下的兄弟模块共享表、共享端点前缀、共享前端路由，
 *      确实需要排队；不同 L3 之间在约束 1 成立时就没有硬依赖。
 *
 * 万一还有蓝图没写出来的隐性依赖，任务书要求 worker 开工 10 分钟内自检、
 * 不满足就报 BLOCKED —— 宁可 10 分钟退出重派，也不要占满 40 分钟做废功。
 */
function pickModules(count, ledger, state) {
  const taken = new Set(
    Object.values(state.lanes)
      .filter((l) => l.state === 'running' || l.state === 'ready')
      .map((l) => l.id),
  );

  const delivered = new Set(ledger.filter((r) => r.status === '已交付').map((r) => r.id));
  const upstream = readUpstreamMap();

  const unfinished = ledger.filter(
    (r) => (r.status === '未开发' || r.status === '进行中') && !taken.has(r.id),
  );

  // 上游没交付的不进候选（进行中例外：它自己已经开工了，收尾优先）
  const upstreamReady = (r) =>
    r.status === '进行中' || (upstream.get(r.id) ?? []).every((u) => delivered.has(u));

  // 进行中优先：有人开了头没收口，收尾比开新坑重要
  const inFlight = unfinished
    .filter((r) => r.status === '进行中')
    .sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }));

  const picked = [];
  const usedL3 = new Set();

  const tryTake = (cands) => {
    for (const r of cands) {
      if (picked.length >= count) return;
      const l3 = l3Of(r.id);
      if (usedL3.has(l3)) continue;
      // 已知阻塞项（证据列带「阻塞：」）先跳过，除非实在凑不满
      if (/^阻塞[：:]/.test(r.evidence)) continue;
      usedL3.add(l3);
      picked.push(r);
    }
  };

  tryTake(inFlight);

  // 再按阶段顺序补齐：同一阶段内按模块 ID 升序
  for (const stage of STAGE_ORDER) {
    if (picked.length >= count) break;
    const cands = unfinished
      .filter((r) => r.stage === stage && r.status !== '进行中' && upstreamReady(r))
      .sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }));
    tryTake(cands);
  }

  // 仍然凑不满才动用阻塞项（明确标注，让任务书知道要先修数据模型）
  if (picked.length < count) {
    tryTake(unfinished.filter((r) => /^阻塞[：:]/.test(r.evidence)));
  }

  return picked;
}

/* ── 车道生命周期 ─────────────────────────────────────────────────────── */

const laneWorktree = (id) => join(WORKTREE_DIR, id);

function laneIsStale(lane) {
  const age = (Date.now() - new Date(lane.heartbeatAt ?? lane.createdAt).getTime()) / 60000;
  return !Number.isFinite(age) || age > TTL_MINUTES;
}

function ensureIntegration() {
  const exists = git(['rev-parse', '--verify', INTEGRATION], ROOT, true);
  if (exists) return git(['rev-parse', '--short', INTEGRATION]);
  const head = git(['rev-parse', 'HEAD']);
  git(['branch', INTEGRATION, head]);
  return git(['rev-parse', '--short', head]);
}

/* ── 命令 ─────────────────────────────────────────────────────────────── */

/* ── 调度闸：防止两个定时 tick 同时派发 ───────────────────────────────── */

const GATE_DIR = join(STATE_DIR, 'dispatch.gate');

/**
 * 用 mkdir 做原子互斥（POSIX 下 mkdir 对已存在目录必然失败，天然原子）。
 *
 * 为什么需要：定时任务每隔一段时间就触发一次，而「回收 → 排车道 → 派发」之间
 * 有网络与 git 操作，耗时可达十几秒。两次 tick 撞在一起时，
 * 两个调度者会同时读台账、同时排出同一个模块、同时写 lanes.json ——
 * 于是同一个模块被两条车道同时开工，两边都在同一个 worktree 里改代码。
 * 闸只在派发窗口持有（秒级），不会把并行开发重新变成串行。
 */
function gateAcquire(ttlSeconds = 600) {
  mkdirSync(STATE_DIR, { recursive: true });
  try {
    mkdirSync(GATE_DIR);
  } catch {
    // 已存在：看是不是陈旧闸
    try {
      const age = (Date.now() - statSync(GATE_DIR).mtimeMs) / 1000;
      if (age > ttlSeconds) {
        rmSync(GATE_DIR, { recursive: true, force: true });
        mkdirSync(GATE_DIR);
      } else {
        return false;
      }
    } catch {
      return false;
    }
  }
  writeFileSync(join(GATE_DIR, 'owner'), `${process.pid}\n${new Date().toISOString()}\n`);
  return true;
}

const gateRelease = () => rmSync(GATE_DIR, { recursive: true, force: true });

/* ── 命令 ─────────────────────────────────────────────────────────────── */

const [cmd = 'status'] = process.argv.slice(2).filter((a) => !a.startsWith('--') || a);

if (cmd === 'gate') {
  const action = process.argv[3] ?? 'status';
  if (action === 'acquire') {
    const ok = gateAcquire();
    console.log(JSON.stringify({ ok, gate: ok ? 'acquired' : 'held-by-other' }, null, 2));
    process.exit(ok ? 0 : 3);
  }
  if (action === 'release') {
    gateRelease();
    console.log(JSON.stringify({ ok: true, gate: 'released' }, null, 2));
    process.exit(0);
  }
  // status
  let held = false;
  let ageSeconds = null;
  try {
    ageSeconds = Math.round((Date.now() - statSync(GATE_DIR).mtimeMs) / 1000);
    held = true;
  } catch {
    held = false;
  }
  console.log(JSON.stringify({ gate: held ? 'held' : 'free', ageSeconds }, null, 2));
  process.exit(0);
}

if (cmd === 'init') {
  const short = ensureIntegration();

  // 注册并集合并驱动器：让「多车道同时改生成物」从人工解冲突变成自动按键求并
  git([
    'config',
    'merge.contract-union.driver',
    'node tools/module-progress/merge-union.mjs %O %A %B',
  ]);

  // .worktrees 必须忽略，否则每条车道的依赖目录会进 git 状态
  let gi = readFileSync(join(ROOT, '.gitignore'), 'utf8');
  if (!/^\.worktrees\/?$/m.test(gi)) {
    gi = gi.replace(
      /\n*$/,
      '\n\n# 并行车道的独立 worktree（tools/module-progress/lane.mjs 管理）\n.worktrees/\n',
    );
    writeFileSync(join(ROOT, '.gitignore'), gi);
  }

  writeState({ ...readState(), integrationBranch: INTEGRATION });
  console.log(JSON.stringify({ ok: true, integration: INTEGRATION, head: short }, null, 2));
  process.exit(0);
}

if (cmd === 'status') {
  const state = readState();
  const ledger = readLedger();
  const lanes = Object.values(state.lanes).map((l) => ({
    ...l,
    ageMinutes: Math.round((Date.now() - new Date(l.heartbeatAt ?? l.createdAt).getTime()) / 60000),
    stale: laneIsStale(l),
    worktreeExists: existsSync(laneWorktree(l.id)),
  }));

  const byStage = {};
  for (const r of ledger) {
    byStage[r.stage] ??= { 已交付: 0, 进行中: 0, 未开发: 0 };
    byStage[r.stage][r.status] = (byStage[r.stage][r.status] ?? 0) + 1;
  }

  console.log(
    JSON.stringify(
      {
        integrationBranch: state.integrationBranch,
        integrationHead: git(['rev-parse', '--short', state.integrationBranch], ROOT, true),
        total: ledger.length,
        delivered: ledger.filter((r) => r.status === '已交付').length,
        inProgress: ledger.filter((r) => r.status === '进行中').length,
        untouched: ledger.filter((r) => r.status === '未开发').length,
        byStage,
        lanesRunning: lanes.filter((l) => l.state === 'running' && !l.stale).length,
        lanesReady: lanes.filter((l) => l.state === 'ready').length,
        lanes,
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

if (cmd === 'plan') {
  const count = Number(flag('--lanes') ?? 1);
  ensureIntegration();
  const state = readState();
  const ledger = readLedger();

  // 复用已分配但未使用的 Flyway 号段
  const used = usedVersions();
  const nextFree = () => {
    let n = Math.max(0, ...used);
    for (;;) {
      n += 1;
      if (!used.has(n)) {
        used.add(n);
        return n;
      }
    }
  };

  const picks = pickModules(count, ledger, state);
  if (!picks.length) {
    console.log(JSON.stringify({ ok: true, planned: [], note: '没有可并行的未开发模块' }, null, 2));
    process.exit(0);
  }

  const base = git(['rev-parse', state.integrationBranch]);
  const planned = [];

  for (const row of picks) {
    const id = row.id;
    if (state.lanes[id] && ['running', 'ready'].includes(state.lanes[id].state)) continue;

    const branch = `feat/${id.replace(/\./g, '-')}`;
    const wt = laneWorktree(id);
    const version = nextFree();

    // 车道分支已存在（上一次跑崩了留的）就直接复用，不重新切
    const branchExists = git(['rev-parse', '--verify', branch], ROOT, true);
    if (!branchExists) git(['branch', branch, base]);

    // worktree 已存在就直接复用
    if (!existsSync(wt)) {
      mkdirSync(WORKTREE_DIR, { recursive: true });
      git(['worktree', 'add', wt, branch]);
      sh('pnpm install --prefer-offline', wt);
    }

    state.lanes[id] = {
      id,
      name: row.name,
      stage: row.stage,
      route: row.route,
      l3: l3Of(id),
      blueprint: blueprintFile(id),
      branch,
      worktree: wt,
      migrationVersion: `V${version}`,
      state: 'running',
      sessionId: flag('--session') ?? null,
      baseSha: base.slice(0, 7),
      createdAt: new Date().toISOString(),
      heartbeatAt: new Date().toISOString(),
      note: null,
    };
    planned.push(state.lanes[id]);
  }

  writeState(state);
  console.log(JSON.stringify({ ok: true, integration: state.integrationBranch, planned }, null, 2));
  process.exit(0);
}

if (cmd === 'adopt') {
  // 把「老调度器用 lock.mjs 派出去、已经在主工作区里跑着」的任务收编成一条车道，
  // 这样它落地后也能走同一条并入路径，不用人工搬。
  const id = String(flag('--lane'));
  const row = readLedger().find((r) => r.id === id);
  if (!row) {
    console.error(`✗ 台账里没有模块 ${id}`);
    process.exit(1);
  }
  const state = readState();
  const branch = String(flag('--branch') ?? `feat/${id.replace(/\./g, '-')}`);
  state.lanes[id] = {
    id,
    name: row.name,
    stage: row.stage,
    route: row.route,
    l3: l3Of(id),
    blueprint: blueprintFile(id),
    branch,
    worktree: String(flag('--worktree') ?? ROOT),
    migrationVersion:
      flag('--migration-version') === true ? null : (flag('--migration-version') ?? null),
    inPlace: !flag('--worktree'),
    state: 'running',
    sessionId: flag('--session') ?? null,
    baseSha: git(['rev-parse', '--short', `${branch}~1`], ROOT, true),
    createdAt: new Date().toISOString(),
    heartbeatAt: new Date().toISOString(),
    note: '老调度器任务，已收编',
  };
  writeState(state);
  console.log(JSON.stringify({ ok: true, adopted: state.lanes[id] }, null, 2));
  process.exit(0);
}

if (cmd === 'heartbeat') {
  const id = flag('--lane');
  const state = readState();
  const lane = state.lanes[String(id)];
  if (!lane) {
    console.error(`✗ 没有这条车道：${id}`);
    process.exit(1);
  }
  lane.heartbeatAt = new Date().toISOString();
  const note = flag('--note');
  if (note) lane.note = note === true ? '' : note;
  writeState(state);
  console.log(`✓ ${id} 心跳已续${lane.note ? `（${lane.note}）` : ''}`);
  process.exit(0);
}

if (cmd === 'land') {
  const id = String(flag('--lane'));
  const state = readState();
  const lane = state.lanes[id];
  if (!lane) {
    console.error(`✗ 没有这条车道：${id}`);
    process.exit(1);
  }

  // 落地前的三道闸：没提交完 / 有未跟踪文件 / 一条 commit 都没有 → 不许标 ready
  const dirty = git(['status', '--porcelain'], lane.worktree);
  if (dirty) {
    console.error(`✗ ${id} 工作区不干净，拒绝标记 ready：\n${dirty}`);
    process.exit(1);
  }
  const sha = git(['rev-parse', 'HEAD'], lane.worktree);
  if (sha === lane.baseSha || !sha) {
    console.error(`✗ ${id} 没有任何新提交（HEAD 仍是基线 ${lane.baseSha}）`);
    process.exit(1);
  }

  lane.state = 'ready';
  lane.headSha = sha;
  lane.landedAt = new Date().toISOString();
  writeState(state);
  console.log(
    JSON.stringify({ ok: true, lane: id, head: sha.slice(0, 7), branch: lane.branch }, null, 2),
  );
  process.exit(0);
}

if (cmd === 'merge-ready') {
  const state = readState();
  const ready = Object.values(state.lanes).filter((l) => l.state === 'ready');

  if (!ready.length) {
    console.log(JSON.stringify({ ok: true, merged: [], note: '没有待并入的车道' }, null, 2));
    process.exit(0);
  }

  // 只允许在集成分支上并入；主工作区此前可能停在某条车道分支上，先切回来
  const current = git(['rev-parse', '--abbrev-ref', 'HEAD']);
  const dirty = git(['status', '--porcelain']);
  if (dirty) {
    console.error(`✗ 主工作区不干净，拒绝切分支：\n${dirty}`);
    process.exit(1);
  }
  if (current !== state.integrationBranch) git(['checkout', state.integrationBranch]);

  const merged = [];
  const failed = [];

  for (const lane of ready) {
    const before = git(['rev-parse', '--short', 'HEAD']);
    const r = git(
      ['merge', '--no-ff', '-m', `merge(${lane.id}): ${lane.name}`, lane.branch],
      ROOT,
      true,
    );
    if (r === null) {
      const conflicts = git(['diff', '--name-only', '--diff-filter=U']);
      failed.push({
        lane: lane.id,
        conflicts: conflicts ? conflicts.split('\n').filter(Boolean) : [],
      });
      console.error(`✗ ${lane.id} 并入冲突：${conflicts || '(未知)'}`);
      // 冲突留给人工/下一轮处理，先把这辆车道挪出 ready，避免反复重试
      lane.state = 'conflict';
      lane.conflicts = conflicts ? conflicts.split('\n').filter(Boolean) : [];
      continue;
    }
    lane.state = 'merged';
    lane.mergedAt = new Date().toISOString();
    merged.push({
      lane: lane.id,
      name: lane.name,
      from: before,
      to: git(['rev-parse', '--short', 'HEAD']),
    });
  }

  writeState(state);
  console.log(JSON.stringify({ ok: failed.length === 0, merged, failed }, null, 2));
  process.exit(failed.length ? 2 : 0);
}

if (cmd === 'abandon') {
  const id = String(flag('--lane'));
  const state = readState();
  const lane = state.lanes[id];
  if (!lane) {
    console.error(`✗ 没有这条车道：${id}`);
    process.exit(1);
  }
  lane.state = 'abandoned';
  lane.reason = flag('--reason') === true ? '' : (flag('--reason') ?? '');
  lane.heartbeatAt = new Date().toISOString();
  writeState(state);
  console.log(`✓ 已放弃 ${id}${lane.reason ? `（${lane.reason}）` : ''}，工作区保留供人工检查`);
  process.exit(0);
}

if (cmd === 'gc') {
  const state = readState();
  const removed = [];
  for (const lane of Object.values(state.lanes)) {
    if (!['merged', 'abandoned'].includes(lane.state)) continue;
    const wt = laneWorktree(lane.id);
    if (!existsSync(wt)) continue;
    git(['worktree', 'remove', '--force', wt], ROOT, true);
    rmSync(wt, { recursive: true, force: true });
    removed.push(lane.id);
  }
  git(['worktree', 'prune'], ROOT, true);
  console.log(JSON.stringify({ ok: true, removed }, null, 2));
  process.exit(0);
}

console.error(
  `未知命令：${cmd}（可用：init / status / plan / heartbeat / land / merge-ready / abandon / gc）`,
);
process.exit(1);
