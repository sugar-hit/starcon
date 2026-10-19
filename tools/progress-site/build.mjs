#!/usr/bin/env node
/**
 * starcon 交付进度看板 —— 数据构建脚本
 *
 * 数据源（全部是仓库内的事实来源，不手抄）：
 *   docs/product/capability-blueprint/module-progress.md  → 465 个 L4 模块的交付状态台账
 *   docs/product/capability-blueprint/0N-*.md              → 每个 L4 模块的 职责/输入/输出/关系
 *   docs/product/capability-blueprint/README.md            → 18 个系统的定位与主责服务
 *   backend/ apps/                                          → 工程现状（迁移/测试/页面）计数
 *
 * 用法：
 *   node tools/progress-site/build.mjs                 从当前工作树构建
 *   node tools/progress-site/build.mjs --docs-ref origin/main
 *                                                        从某个 git ref 构建
 *
 * --docs-ref 的意义：每日刷新时用 `git show <ref>:<path>` 直接读远端已提交的
 * 台账与蓝图，**完全不碰本地工作树**。这样刷新不依赖本地 git 状态 ——
 * 本地有未推送的提交、rebase 冲突、切分支，都不会让站点停止更新。
 *
 * 产物：tools/progress-site/dist/{index.html,styles.css,app.js,data.json}
 */

import {
  readFileSync,
  writeFileSync,
  readdirSync,
  statSync,
  mkdirSync,
  rmSync,
  cpSync,
} from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..', '..');
const BP_REL = 'docs/product/capability-blueprint';
const BP = join(ROOT, BP_REL);
const SRC = join(__dirname, 'src');
const DIST = join(__dirname, 'dist');

/* --docs-ref <git ref>：从 ref 读文档，而不是从工作树 */
const REF = (() => {
  const i = process.argv.indexOf('--docs-ref');
  return i >= 0 ? process.argv[i + 1] : null;
})();

const git = (args) =>
  execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 });

/** 读一个仓库相对路径的文档：ref 模式走 git show，否则读工作树 */
const readDoc = (relPath) =>
  REF ? git(['show', `${REF}:${relPath}`]) : readFileSync(join(ROOT, relPath), 'utf8');

const read = (p) => readFileSync(p, 'utf8');

/* ── 工具 ──────────────────────────────────────────────── */

function walk(dir, out = []) {
  let entries = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const e of entries) {
    if (e === 'node_modules' || e === '.git' || e === 'target' || e === '.next' || e === 'dist')
      continue;
    const full = join(dir, e);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const stripMd = (s = '') =>
  s
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/`(.+?)`/g, '$1')
    .replace(/^[\s\-*]+/, '')
    .trim();

/* ── 1. 解析交付状态台账 ───────────────────────────────── */

function parseLedger() {
  const text = readDoc(`${BP_REL}/module-progress.md`);
  const rows = new Map();

  for (const line of text.split('\n')) {
    if (!/^\|\s*\d+\.\d+\.\d+\.\d+\s*\|/.test(line)) continue;
    const cells = line
      .split('|')
      .slice(1, -1)
      .map((c) => c.trim());
    if (cells.length < 6) continue;
    const [id, name, route, phase, status, evidence] = cells;
    rows.set(id, {
      id,
      name,
      route: route === '—' || route === '' ? null : route,
      phase,
      status,
      evidence: evidence === '—' || evidence === '' ? null : evidence,
    });
  }
  return rows;
}

/* ── 2. 解析系统地图（README §4） ───────────────────────── */

function parseSystemMap() {
  const text = readDoc(`${BP_REL}/README.md`);
  const map = new Map();
  const start = text.indexOf('## 4. 系统地图');
  if (start === -1) return map;
  const end = text.indexOf('### 4.1', start);
  const slice = text.slice(start, end > 0 ? end : start + 8000);

  for (const line of slice.split('\n')) {
    if (!/^\|\s*\*?\*?\d{2}\s*\|/.test(line)) continue;
    const cells = line
      .split('|')
      .slice(1, -1)
      .map((c) => c.trim());
    if (cells.length < 6) continue;
    const [num, sys, tier, duty, service, volCell] = cells;
    const mm = num.match(/\d{2}/);
    if (!mm) continue;
    const vol = (volCell.match(/\(([^)]+)\)/) || [])[1];
    map.set(String(Number(mm[0])), {
      id: String(Number(mm[0])),
      name: stripMd(sys),
      tier: stripMd(tier),
      duty: stripMd(duty),
      service: stripMd(service),
      volumePath: vol ? `${BP_REL}/${vol}` : null,
    });
  }
  return map;
}

/* ── 3. 解析各卷蓝图：定位 / 边界 / L2 职责 / 功能树 / L4 详情 ── */

function parseVolume(volumePath) {
  const text = readDoc(volumePath);
  const out = { positioning: null, ownership: null, entry: null, does: null, notDoes: null };

  const grab = (re) => (text.match(re) || [])[1] || null;
  out.positioning = grab(/^> \*\*定位\*\*：(.+)$/m);
  out.ownership = grab(/^> \*\*后端归属\*\*：(.+)$/m);
  out.entry = grab(/^> \*\*前端入口\*\*：(.+)$/m);
  out.does = grab(/\*\*做\*\*：(.+)/);
  out.notDoes = grab(/\*\*不做\*\*：(.+)/);

  // L2 功能职责表
  out.features = [];
  const featStart = text.indexOf('## 功能职责');
  if (featStart > 0) {
    const featSlice = text.slice(
      featStart,
      text.indexOf('## 功能树', featStart) > 0
        ? text.indexOf('## 功能树', featStart)
        : featStart + 4000,
    );
    for (const line of featSlice.split('\n')) {
      if (!/^\|\s*\*\*/.test(line)) continue;
      const cells = line
        .split('|')
        .slice(1, -1)
        .map((c) => c.trim());
      if (cells.length < 3) continue;
      const mm = (cells[0] || '').match(/(\d+\.\d+)/);
      if (!mm) continue;
      out.features.push({
        id: mm[1],
        name: stripMd(cells[0]).replace(/^\d+\.\d+\s*/, ''),
        duty: stripMd(cells[1]),
        service: stripMd(cells[2]),
      });
    }
  }

  // 功能树 → L2/L3 名称与路由
  out.l2 = new Map();
  out.l3 = new Map();
  const treeStart = text.indexOf('## 功能树');
  if (treeStart > 0) {
    const treeEnd = text.indexOf('## 详细设计', treeStart);
    const treeSlice = text.slice(treeStart, treeEnd > 0 ? treeEnd : treeStart + 12000);
    for (const raw of treeSlice.split('\n')) {
      const line = raw.replace(/^[│├└─\s]+/, '').trim();
      const m = line.match(/^(\d+(?:\.\d+){1,2})\s+(.+?)(?:\s+→\s+(\S+))?$/);
      if (!m) continue;
      const [, id, rest, route] = m;
      const name = stripMd(rest)
        .replace(/\s*→.*$/, '')
        .trim();
      if (id.split('.').length === 2) out.l2.set(id, { id, name, route: route || null });
      else out.l3.set(id, { id, name, route: route || null });
    }
  }

  // L4 详细设计
  out.modules = new Map();
  const detailStart = text.indexOf('## 详细设计');
  if (detailStart > 0) {
    const body = text.slice(detailStart);
    const chunks = body.split(/\n(?=###\s)/);
    for (const chunk of chunks) {
      const h = chunk.match(/^###\s+(\d+\.\d+\.\d+\.\d+)\s+(.+)$/m);
      if (!h) continue;
      const id = h[1];
      const name = stripMd(h[2]);
      const duty = stripMd((chunk.match(/\*\*职责\*\*：(.+)/) || [])[1] || '');
      const input = stripMd((chunk.match(/\*\*输入\*\*：(.+)/) || [])[1] || '');
      const output = stripMd((chunk.match(/\*\*输出\*\*：(.+)/) || [])[1] || '');
      const relation = stripMd((chunk.match(/\*\*关系\*\*：(.+)/) || [])[1] || '');
      out.modules.set(id, { id, name, duty, input, output, relation });
    }
  }
  return out;
}

/* ── 4. 工程现状计数 ──────────────────────────────────── */

function engineeringStats() {
  // 计数全部基于**文件名**，所以 ref 模式只要一份文件清单就够，不必读文件内容
  const relPaths = REF
    ? git(['ls-tree', '-r', '--name-only', REF]).split('\n').filter(Boolean)
    : walk(ROOT).map((f) => f.slice(ROOT.length + 1));

  const stats = {
    migrations: {},
    backendTestClasses: {},
    frontendTestFiles: 0,
    webPages: 0,
    adminPages: 0,
    totalMigrations: 0,
    totalBackendTests: 0,
  };

  for (const rel of relPaths) {
    const svcMatch = rel.match(
      /^backend\/services\/([^/]+)\/src\/main\/resources\/db\/migration\/V\d+.*\.sql$/,
    );
    if (svcMatch) {
      const svc = svcMatch[1];
      stats.migrations[svc] = (stats.migrations[svc] || 0) + 1;
      stats.totalMigrations += 1;
      continue;
    }
    if (/^backend\/services\/([^/]+)\/src\/test\/java\/.*Test\.java$/.test(rel)) {
      const svc = rel.match(/^backend\/services\/([^/]+)\//)[1];
      stats.backendTestClasses[svc] = (stats.backendTestClasses[svc] || 0) + 1;
      stats.totalBackendTests += 1;
      continue;
    }
    if (/\.test\.(ts|tsx)$/.test(rel)) {
      stats.frontendTestFiles += 1;
      continue;
    }
    if (/^apps\/web\/src\/app\/.*page\.tsx$/.test(rel)) {
      stats.webPages += 1;
      continue;
    }
    if (/^apps\/admin\/src\/app\/.*page\.tsx$/.test(rel)) {
      stats.adminPages += 1;
    }
  }
  return stats;
}

/* ── 5. 组装 ──────────────────────────────────────────── */

const ledger = parseLedger();
const systemMap = parseSystemMap();

const systems = [];
for (const [sid, meta] of [...systemMap.entries()].sort((a, b) => Number(a[0]) - Number(b[0]))) {
  const volume = meta.volumePath
    ? parseVolume(meta.volumePath)
    : { features: [], modules: new Map(), l2: new Map(), l3: new Map() };
  const modules = [];

  for (const [mid, row] of ledger.entries()) {
    if (!mid.startsWith(`${sid}.`)) continue;
    const detail = volume.modules.get(mid);
    const parts = mid.split('.');
    const l2Id = parts.slice(0, 2).join('.');
    const l3Id = parts.slice(0, 3).join('.');
    modules.push({
      ...row,
      duty: detail?.duty || '',
      input: detail?.input || '',
      output: detail?.output || '',
      relation: detail?.relation || '',
      l2: l2Id,
      l3: l3Id,
      l2Name: volume.l2.get(l2Id)?.name || '',
      l3Name: volume.l3.get(l3Id)?.name || '',
      l3Route: volume.l3.get(l3Id)?.route || volume.l2.get(l2Id)?.route || null,
    });
  }

  const delivered = modules.filter((m) => m.status === '已交付').length;
  const inProgress = modules.filter((m) => m.status === '进行中').length;
  const notStarted = modules.filter((m) => m.status === '未开发').length;
  const phases = [...new Set(modules.map((m) => m.phase))].sort(
    (a, b) => phaseOrder(a) - phaseOrder(b),
  );

  systems.push({
    id: sid,
    name: meta.name,
    tier: meta.tier,
    duty: meta.duty,
    service: meta.service,
    positioning: volume.positioning || '',
    ownership: volume.ownership || '',
    entry: volume.entry || '',
    does: volume.does || '',
    notDoes: volume.notDoes || '',
    features: volume.features,
    phases,
    total: modules.length,
    delivered,
    inProgress,
    notStarted,
    rate: modules.length ? Math.round((delivered / modules.length) * 1000) / 10 : 0,
    modules,
  });
}

function phaseOrder(p) {
  const m = String(p).match(/D(\d+)/);
  return m ? Number(m[1]) : 99;
}

const allModules = systems.flatMap((s) => s.modules);
const delivered = allModules.filter((m) => m.status === '已交付').length;
const inProgress = allModules.filter((m) => m.status === '进行中').length;
const notStarted = allModules.filter((m) => m.status === '未开发').length;

// 分阶段
const phaseMap = new Map();
for (const m of allModules) {
  if (!phaseMap.has(m.phase))
    phaseMap.set(m.phase, { phase: m.phase, delivered: 0, inProgress: 0, notStarted: 0, total: 0 });
  const p = phaseMap.get(m.phase);
  p.total += 1;
  if (m.status === '已交付') p.delivered += 1;
  else if (m.status === '进行中') p.inProgress += 1;
  else p.notStarted += 1;
}
const phases = [...phaseMap.values()].sort((a, b) => phaseOrder(a.phase) - phaseOrder(b.phase));

// 阶段说明（来自台账「跨期说明」）
const ledgerText = readDoc(`${BP_REL}/module-progress.md`);
const phaseNotes = [];
const noteStart = ledgerText.indexOf('## 跨期说明');
if (noteStart > 0) {
  const noteSlice = ledgerText.slice(noteStart, ledgerText.indexOf('## 模块明细', noteStart));
  for (const line of noteSlice.split('\n')) {
    const m = line.match(/^-\s+\*\*(.+?)\*\*：(.+)$/);
    if (m) phaseNotes.push({ subject: m[1], note: m[2].trim() });
  }
}

const data = {
  generatedAt: new Date().toISOString(),
  sourceRef: REF || 'working-tree',
  project: {
    name: 'starcon',
    tagline: '让跨境生意，有序抵达全球。',
    subtitle: '企业级跨境电商 ERP · 多平台订单 → 库存协同 → 履约调度 → 多币种财务结算',
  },
  summary: {
    total: allModules.length,
    delivered,
    inProgress,
    notStarted,
    rate: allModules.length ? Math.round((delivered / allModules.length) * 1000) / 10 : 0,
    systemCount: systems.length,
    featureCount: systems.reduce((n, s) => n + s.features.length, 0),
  },
  phases,
  phaseNotes,
  engineering: engineeringStats(),
  systems,
};

rmSync(DIST, { recursive: true, force: true });
mkdirSync(DIST, { recursive: true });
cpSync(SRC, DIST, { recursive: true });
writeFileSync(join(DIST, 'data.json'), JSON.stringify(data, null, 2));

console.log(
  `[progress-site] 模块 ${data.summary.total} · 已交付 ${delivered} · 进行中 ${inProgress} · 未开发 ${notStarted} · 完成率 ${data.summary.rate}% · 系统 ${systems.length} 个 · 来源 ${data.sourceRef} → ${join(DIST, 'data.json')}`,
);
