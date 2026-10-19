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
// 默认就是脚本所在的仓库；LANE_ROOT 仅供 lane-*.itest.mjs 把整条流水线
// 指向一个一次性仓库来跑端到端断言（不设时行为完全不变）。
const ROOT = process.env.LANE_ROOT ? resolve(process.env.LANE_ROOT) : join(HERE, '..', '..');
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

/**
 * 取文件原始字节，不做任何裁剪。
 *
 * 不能用 git()：它会 trim 掉末尾换行。拿 trim 过的内容去和磁盘上的文件比，
 * 永远差一个换行符，于是「内容完全一致」的判断恒为假 —— 安全检查会一直拒绝删除，
 * 切换分支永远走不到。实测就是这个坑：三个文件 md5 与集成头完全相同，
 * 却因为 trim 全部被判为「内容不同」。
 */
const gitRaw = (args, cwd = ROOT) =>
  execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });

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

/* ── 卷 → 后端主责服务（微服务盲区闸）─────────────────────────────────────
 *
 * 为什么需要这道闸（2026-10-07 22:52 / 23:03 实测）：
 * `plan` 原来只检查「上游已交付 + L3 锚点不同」，**不检查模块该落到哪个微服务**，
 * 而那个服务可能压根不在集成头里。集成头 backend/services 当时只有
 * catalog / channel / identity / warehouse —— 没有 trade。
 *
 * 于是卷 09（订单履约，主责 trade）与卷 11（售后，主责 trade）的车道**注定 BLOCK**：
 * 一条车道 40 分钟、烧掉一个 worktree 和一个 Flyway 版本号，产出为零。
 * 2026-10-07 一晚白烧了 9.3.2.2(V84)、11.4.2.2(V85)、7.8.1.1 三条。
 *
 * 数据源不是在这里硬编码一张表，而是读蓝图 README 里本来就有的
 * 「后端主责服务」列（docs/product/capability-blueprint/README.md）。
 * 那张表由人维护、与蓝图同步；在这里另抄一份只会漂移。
 *
 * 判定规则：**卷的主责服务（列表第一项）必须已存在于集成头**。
 * 列表顺序即优先级 —— 「trade + channel」表示履约归 trade、channel 只是协同方。
 * 若改成「任一存在即可」，卷 09 会因为 channel 存在而被放行，
 * worker 就会把履约逻辑写进 channel（平台接入服务），那才是真正的架构事故。
 *
 * 读不到 / 解析不出映射时**直接失败**，绝不静默放行 ——
 * 静默放行正是这道闸要消灭的那种失败。
 */

/** 卷号（01..18）→ 主责服务标识；主责服务不是 ASCII 标识符（如「前端聚合」）时返回 null */
function readVolumeOwners() {
  const readme = join(BLUEPRINT_DIR, 'README.md');
  if (!existsSync(readme)) {
    throw new Error(
      `找不到 ${readme}：无法判定卷的后端主责服务，微服务盲区闸拒绝放行任何模块。`,
    );
  }
  const owners = new Map();
  for (const line of readFileSync(readme, 'utf8').split('\n')) {
    // | 09  | **订单履约** | 核心业务域 | ... | trade + channel | [09](...) |
    const cells = line.split('|').map((c) => c.trim());
    if (cells.length < 6) continue;
    const vol = /^(\d{1,2})$/.exec(cells[1])?.[1];
    if (!vol) continue; // 表头行（cells[1] 是 '#'）与分隔行（'---'）都会在这里被滤掉，
    // 不需要再靠「单元格加粗」这种排版特征去猜 —— 2026-10-07 实测那种启发式
    // 会把任何未加粗的卷名整行丢掉。
    const raw = cells[5]; // 「后端主责服务」列
    // 去掉括注（catalog（finance 供数）→ catalog），再按 + 拆开取第一项
    const primary = raw
      .replace(/[（(][^）)]*[）)]/g, '')
      .split('+')
      .map((s) => s.trim())
      .filter(Boolean)[0];
    // README 里卷号是零填充的 `09`，模块 ID 是 `9.3.2.2` —— 两侧都归一到不填充才查得到。
    // 不归一时卷 4/6/8/9 会被判成「README 里没有这一卷」而全被误封。
    owners.set(String(Number(vol)), primary ?? null);
  }
  if (owners.size === 0) {
    throw new Error(`${readme} 里一行「后端主责服务」都没解析出来，闸门失效，拒绝放行。`);
  }
  return owners;
}

/** 该服务是否已经存在于集成头（**不是**磁盘上有没有目录 —— 那可能是别人 worktree 里的半成品） */
function serviceInHead(name) {
  if (!/^[a-z][a-z0-9_-]*$/.test(name)) return false;
  const out = git(['ls-tree', '--name-only', INTEGRATION, 'backend/services/'], ROOT, true);
  // ls-tree 返回的是完整路径 `backend/services/<name>`，不是裸目录名 ——
  // 2026-10-07 实测：不剥前缀时 channel 会被判成「不在集成头」，
  // 于是卷 16/14/15 这些真正可做的卷全被误封。
  return (out ?? '')
    .split('\n')
    .map((s) => s.trim().replace(/^backend\/services\//, ''))
    .includes(name);
}

/**
 * 预判一个模块所属卷的服务是否可落地。
 * 返回 null = 可排；返回字符串 = 不可排的原因。
 */
function serviceGate() {
  const owners = readVolumeOwners();
  const memo = new Map();
  return (id) => {
    const vol = String(Number(id.split('.')[0]));
    if (memo.has(vol)) return memo.get(vol);
    const primary = owners.get(vol);
    let verdict = null;
    if (!owners.has(vol)) {
      verdict = `卷 ${vol} 在蓝图 README 里没有「后端主责服务」条目，无法判定落地服务`;
    } else if (!primary) {
      // 主责不是服务标识符（如卷 01「前端聚合 + 各服务投影」）→ 纯前端聚合层，照排
      verdict = null;
    } else if (!serviceInHead(primary)) {
      verdict = `卷 ${vol} 主责服务 ${primary} 不在集成头`;
    }
    memo.set(vol, verdict);
    return verdict;
  };
}

/* ── 模块选取：L3 锚点互不相同 + 上游必须已交付 + 落地服务必须已存在 ─────── */

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
  const gate = serviceGate();

  // 服务未落地的卷：既不排车道，也不烧版本号。顺带把原因带出去，
  // 否则 plan 返回空数组时，调度器只会以为「没活了」，看不出是整体被服务缺口堵死。
  const serviceBlocked = new Map();

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
      // 落地微服务必须已存在于集成头，否则这条车道注定 BLOCK
      const blocked = gate(r.id);
      if (blocked) {
        const vol = String(Number(r.id.split('.')[0]));
        if (!serviceBlocked.has(vol)) {
          serviceBlocked.set(vol, { reason: blocked, sample: [r.id] });
        } else {
          serviceBlocked.get(vol).sample.push(r.id);
        }
        continue;
      }
      // 已知阻塞项（证据列带「阻塞：」）先跳过，除非实在凑不满
      if (/^阻塞[：:]/.test(r.evidence)) continue;
      usedL3.add(l3);
      picked.push(r);
    }
  };

  tryTake(inFlight);

  // 关键路径优先：依赖深度降序；同深度仍按阶段顺序，再按模块 ID 升序。
  //
  // 为什么改（2026-10-07 22:04 实测）：原逻辑走 STAGE_ORDER，同阶段内按 ID 升序。
  // 而 D7 的 ID 天然比 D4 大得多，于是「阶段靠后 + ID 靠后」的模块永远排在最后 ——
  // 可它们恰恰是下游最多、解锁面最大的那批。实测同样 6 个名额：
  //
  //   旧（阶段顺序）  平均依赖深度 1.2，且 6 个里 4 个 depth=0（蓝图没写上游）
  //   新（深度降序）  平均依赖深度 4.2
  //
  // 依赖深度 = 该模块到依赖链起点的最长路径长度。**与上游何时交付无关**：
  // 深度衡量的是「做完它能解锁多少下游」，而候选池已经过 upstreamReady 过滤，
  // 所以只看池内模块之间谁的解锁面更大。
  const ledgerIds = new Set(ledger.map((r) => r.id));
  const depth = new Map();

  const depthOf = (id, stack = new Set()) => {
    if (depth.has(id)) return depth.get(id);
    if (stack.has(id)) return 0; // 环保护：蓝图写坏时不要死循环
    stack.add(id);
    const us = (upstream.get(id) ?? []).filter((u) => ledgerIds.has(u));
    const d = us.length === 0 ? 0 : 1 + Math.max(...us.map((u) => depthOf(u, stack)));
    stack.delete(id);
    depth.set(id, d);
    return d;
  };

  const stageRank = (s) => {
    const i = STAGE_ORDER.indexOf(s);
    return i === -1 ? STAGE_ORDER.length : i;
  };

  const cands = unfinished
    .filter((r) => r.status !== '进行中' && upstreamReady(r))
    .sort(
      (a, b) =>
        depthOf(b.id) - depthOf(a.id) ||
        stageRank(a.stage) - stageRank(b.stage) ||
        a.id.localeCompare(b.id, 'en', { numeric: true }),
    );
  tryTake(cands);

  // 仍然凑不满才动用阻塞项（明确标注，让任务书知道要先修数据模型）
  if (picked.length < count) {
    tryTake(unfinished.filter((r) => /^阻塞[：:]/.test(r.evidence)));
  }

  return { picked, serviceBlocked: [...serviceBlocked].map(([volume, v]) => ({ volume, ...v })) };
}

/* ── 车道生命周期 ─────────────────────────────────────────────────────── */

const laneWorktree = (id) => join(WORKTREE_DIR, id);

function laneIsStale(lane) {
  const age = (Date.now() - new Date(lane.heartbeatAt ?? lane.createdAt).getTime()) / 60000;
  return !Number.isFinite(age) || age > TTL_MINUTES;
}

/**
 * 「车道建了但一直没人接手」——注意这**不等于** sessionId 为空。
 *
 * 2026-10-07 实测踩到的坑：cron 派发写的是 `plan --lanes 4`，**不带 --session**，
 * 于是每条车道的 sessionId 恒为 null。原来的孤儿判据是
 * `state === 'running' && (!l.sessionId || laneIsStale(l))`，
 * 后果是**每一条正在跑的车道都被当成孤儿**：
 *   - reDispatched 每轮 +1，`toDispatch` 里永远是同 4 条（照单全派 = 双 writer）；
 *   - `pickModules(count - orphaned.length)` 被这批「假孤儿」吃光名额，
 *     newlyPlanned 恒为 [] —— 一条新车道都排不出来，流水线静默卡死在 4 条。
 *
 * 心跳才是判活依据：worker 每 25 分钟续一次，TTL 90 分钟。
 * 宽限取 60 分钟：高于 25 分钟的心跳周期（活着的 worker 绝不会被误判成孤儿），
 * 又低于 90 分钟的 TTL（崩掉的车道比原来更快被回收）。
 */
const CLAIM_GRACE_MINUTES = 60;

function laneIsUnclaimed(lane) {
  if (lane.sessionId) return false;
  const age = (Date.now() - new Date(lane.heartbeatAt ?? lane.createdAt).getTime()) / 60000;
  return Number.isFinite(age) && age > CLAIM_GRACE_MINUTES;
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
 *
 * ⚠️ **TTL 必须覆盖一个完整 tick 的时长，不能按「派发窗口是秒级」来设。**
 * 原实现 TTL=600（10 分钟），前提是「闸只在派发窗口持有（秒级）」——
 * 那个前提在组批协议下已经不成立：现行流程让 tick **持闸跑完 merge + L2**，
 * 实测单轮 40–60 分钟（2026-10-19 20:00 那轮 20:00→20:59）。
 *
 * TTL=600 时，**下一轮 tick 会在持闸者还活着的时候把闸抢走** ⇒ 两个 tick 同时
 * `plan`（版本号在内存里分配，两边会拿到同一个号）、同时 `merge-ready`
 * （主工作区留 MERGE_HEAD 残骸）、同时跑 L2（互相争抢 Testcontainers → 成片假红）。
 * 记忆取证同源：`ageSeconds > 600` **不是「持闸者已死」的证据**，
 * 取到闸的 tick 紧接着跑 L2，闸本来就会自然超过 600 秒。
 *
 * 现在 TTL=4200（70 分钟）= 单轮实测最坏 60 分钟 + 10 分钟余量。
 * 代价：tick 崩在闸里，最多挡住下一轮（下一轮做只读 + 汇报，再下一轮自动接管）。
 * 取舍明确：**宁可挡住一轮，也不能双派发** —— 后者会污染版本号与集成头。
 */
const GATE_TTL_SECONDS = 4200;

function gateAcquire(ttlSeconds = GATE_TTL_SECONDS) {
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
  // **必须把 TTL 和 stale 一起输出。**
  // 只给 ageSeconds 的话，调度者无从判断「这闸还能不能抢」——
  // 2026-10-19 那轮就是因为缺这个判据，把「PG 闸误触发」误诊成「调度闸泄漏 3 小时」。
  const stale = held && ageSeconds > GATE_TTL_SECONDS;
  console.log(
    JSON.stringify(
      { gate: held ? 'held' : 'free', ageSeconds, ttlSeconds: GATE_TTL_SECONDS, stale },
      null,
      2,
    ),
  );
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

  // 版本号要同时避开「磁盘上已存在的迁移」和「其它车道已预分配但还没落盘的」。
  // 只扫磁盘是不够的：车道刚排出来时文件还不存在，下一轮 plan 会把同一个号再发一遍，
  // 两条车道交出 V27，Flyway 在合并后直接炸 —— 而且是在别人跑测试时才炸。
  const used = usedVersions();
  for (const l of Object.values(state.lanes)) {
    if (['abandoned', 'merged'].includes(l.state)) continue;
    const n = Number(/^V(\d+)$/.exec(l.migrationVersion ?? '')?.[1]);
    if (Number.isFinite(n)) used.add(n);
  }
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

  // 先把「排了车道但没真正派出去」的捞回来重派，再拿剩下的名额排新车道。
  // 没有这一步就会丢车道：plan 只建车道不派发，若某个 tick 在建完车道之后、
  // 派发之前崩了（或被会话上限挡住），这条车道会永远停在 running 且无人认领，
  // 之后每一轮 plan 都因为「已被占用」跳过它 —— 既不开发也不回收，整条流水线静默少一条。
  const session = flag('--session') === true ? null : (flag('--session') ?? null);
  const orphaned = Object.values(state.lanes).filter(
    (l) => l.state === 'running' && (laneIsStale(l) || laneIsUnclaimed(l)),
  );
  for (const l of orphaned) {
    l.sessionId = session;
    l.heartbeatAt = new Date().toISOString();
    l.reDispatched = (l.reDispatched ?? 0) + 1;
  }

  const { picked, serviceBlocked } = pickModules(count - orphaned.length, ledger, state);
  const base = git(['rev-parse', state.integrationBranch]);
  const planned = [];

  // --dry-run：只算不建。用来回答「为什么 plan 排不出来」而不留 worktree 和分支。
  const dryRun = flag('--dry-run') === true;

  for (const row of picked) {
    const id = row.id;
    if (state.lanes[id] && ['running', 'ready'].includes(state.lanes[id].state)) continue;

    const branch = `feat/${id.replace(/\./g, '-')}`;
    const wt = laneWorktree(id);
    const version = nextFree();

    if (!dryRun) {
      // 车道分支已存在（上一次跑崩了留的）就直接复用，不重新切
      const branchExists = git(['rev-parse', '--verify', branch], ROOT, true);
      if (!branchExists) git(['branch', branch, base]);

      // worktree 已存在就直接复用
      if (!existsSync(wt)) {
        mkdirSync(WORKTREE_DIR, { recursive: true });
        git(['worktree', 'add', wt, branch]);
        sh('pnpm install --prefer-offline', wt);
      }
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
      sessionId: session,
      baseSha: base.slice(0, 7),
      createdAt: new Date().toISOString(),
      heartbeatAt: new Date().toISOString(),
      note: null,
    };
    // dry-run 也要把「本该排出来的」报出来 —— 只 push 不落盘。
    // 早先写成 `if (!dryRun) planned.push(...)`，结果 --dry-run 的 newlyPlanned 恒为空，
    // 看起来像「闸门把能干的活也挡掉了」，其实是自己把结果吞了（2026-10-07 实测）。
    planned.push(state.lanes[id]);
  }

  if (!dryRun) writeState(state);
  console.log(
    JSON.stringify(
      {
        ok: true,
        integration: state.integrationBranch,
        dryRun: dryRun || undefined,
        toDispatch: [...orphaned, ...planned].map((l) => ({
          id: l.id,
          name: l.name,
          stage: l.stage,
          l3: l.l3,
          blueprint: l.blueprint,
          branch: l.branch,
          worktree: l.worktree,
          migrationVersion: l.migrationVersion,
          redispatched: l.reDispatched ?? 0,
        })),
        newlyPlanned: planned.map((l) => l.id),
        serviceBlocked,
        note: planned.length === 0 && orphaned.length === 0 ? '没有可并行的未开发模块' : null,
      },
      null,
      2,
    ),
  );
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

  // 老调度器遗留的车道不会调用 lane.mjs land（它释放的是 lock.mjs），
  // 替它判断该不该落地，否则这部分交付记录会永远停在 running 上没人回收。
  const legacyLock = (() => {
    try {
      return JSON.parse(readFileSync(join(STATE_DIR, 'dev-task.json'), 'utf8'));
    } catch {
      return null;
    }
  })();
  // 只看已跟踪文件的改动：编排器自己那几个文件此刻在工作区里还是未跟踪状态，
  // 它们不该阻塞对在途模块的收口。
  const trackedDirty = git(['status', '--porcelain', '--untracked-files=no'], ROOT);
  for (const lane of Object.values(state.lanes)) {
    if (!lane.inPlace || lane.state !== 'running') continue;
    if (legacyLock?.module === lane.id) continue; // 还在跑，不碰
    if (trackedDirty) continue; // 主工作区有在途改动，不碰
    const sha = git(['rev-parse', 'HEAD'], ROOT);
    if (!sha || sha === lane.baseSha) continue; // 没有新提交，等于没开工
    lane.state = 'ready';
    lane.headSha = sha;
    lane.landedAt = new Date().toISOString();
    lane.landedBy = 'reap-legacy';
  }

  // 上一轮 merge 冲突被人工（或调度器）解掉并提交之后，车道仍是 conflict：
  // 脚本只在「本次 merge 失败」时写 conflict，从不回头确认「后来是否并进去了」。
  // 后果是 gc 不清理它的 worktree，下一轮 plan 又把它当未解决的冲突重新报一遍。
  // 判据：车道分支已经是集成分支的祖先 ⇒ 成果确实在集成头里，直接补记为 merged。
  const conflictUnresolved = [];
  for (const lane of Object.values(state.lanes)) {
    if (lane.state !== 'conflict') continue;
    const mergedAlready =
      lane.headSha &&
      git(['merge-base', '--is-ancestor', lane.headSha, state.integrationBranch], ROOT, true) === '';
    if (mergedAlready) {
      lane.state = 'merged';
      lane.mergedAt = lane.mergedAt ?? new Date().toISOString();
      lane.mergedBy = lane.mergedBy ?? 'reconcile-conflict-already-merged';
      delete lane.conflicts;
    } else {
      conflictUnresolved.push(lane.id);
    }
  }
  if (conflictUnresolved.length) {
    console.error(
      `⚠ 仍有未解决的并入冲突，请人工处理：${conflictUnresolved.join(', ')}` +
        `（` + `解决并提交后，下一次 merge-ready 会自动把它们补记为 merged）`,
    );
  }

  const ready = Object.values(state.lanes).filter((l) => l.state === 'ready');

  if (!ready.length) {
    // 补记改过状态就必须落盘，否则「没有待并入的车道」这条早退路径会把修正丢掉。
    writeState(state);
    console.log(JSON.stringify({ ok: true, merged: [], note: '没有待并入的车道' }, null, 2));
    process.exit(0);
  }

  // 只允许在集成分支上并入；主工作区此前可能停在某条车道分支上，先切回来。
  // 顺序很重要：编排器自己那几个文件在工作区里是「未跟踪但内容与集成头完全一致」的状态
  // （在途模块还没并入集成头），git checkout 会以「会被覆盖」拒绝。
  // 所以必须先把它们处理掉，再做干净度检查 —— 反过来做会永远卡住。
  const current = git(['rev-parse', '--abbrev-ref', 'HEAD']);
  const TOOLING = [
    'tools/module-progress/lane.mjs',
    'tools/module-progress/merge-union.mjs',
    'tools/module-progress/merge-union.itest.mjs',
    'tools/module-progress/PIPELINE.md',
  ];
  const untracked = (git(['ls-files', '--others', '--exclude-standard'], ROOT) ?? '')
    .split('\n')
    .filter(Boolean);
  for (const f of untracked) {
    if (!TOOLING.includes(f)) continue;
    const inBranch = (() => {
      try {
        return gitRaw(['show', `${state.integrationBranch}:${f}`]);
      } catch {
        return null;
      }
    })();
    const abs = join(ROOT, f);
    const onDisk = existsSync(abs) ? readFileSync(abs, 'utf8') : null;
    if (inBranch !== null && inBranch === onDisk) rmSync(abs);
    else console.error(`⚠ ${f} 未跟踪且内容与集成头不同，已保留，请人工确认`);
  }

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
      // 冲突留给人工/下一轮处理，先把这辆车道挪出 ready，避免反复重试。
      //
      // 但**必须立刻 abort**：不 abort 的话 MERGE_HEAD 残留、主工作区保持脏，
      // 于是 ① 下一个 tick 的 merge-ready 会在「主工作区不干净，拒绝切分支」处直接退出，
      // 并入通道彻底堵死；② 本循环里下一条车道会在同一棵冲突树上再 merge，
      // 报出与它自身毫无关系的级联冲突。2026-10-07 一晚被这个缺陷撂了两次。
      git(['merge', '--abort'], ROOT, true);
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
