#!/usr/bin/env node
/**
 * 功能蓝图进度台账同步器
 *
 * 做什么：
 *   1. 解析 docs/product/capability-blueprint/ 下 18 卷能力蓝图的「功能树」代码块，
 *      抽出全部 L4 模块（权威来源是蓝图，不是本脚本）。
 *   2. 与既有台账 module-progress.md 合并 —— 按模块 ID 保留人工维护的
 *      「状态」「证据」两列，蓝图里新增/删除的模块自动补进/移出台账。
 *   3. 回写台账并打印统计摘要。
 *
 * 为什么不手维护台账：
 *   465 个模块手抄一遍必然和蓝图漂移。蓝图是唯一真相源，台账只是叠加在上面的
 *   一层交付状态。两者不一致时以蓝图为准，本脚本负责把台账拉齐。
 *
 * 用法：
 *   node tools/module-progress/sync.mjs          # 同步台账
 *   node tools/module-progress/sync.mjs --check  # 只校验，不写文件（CI 用）
 *
 * 状态词表（不要新增，统计逻辑依赖它）：
 *   已交付  迁移 + API + 页面 + 测试齐全，且无 TODO 残留
 *   进行中  已有分支在做，但未收口
 *   未开发  未开工
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const BLUEPRINT_DIR = join(ROOT, 'docs', 'product', 'capability-blueprint');
const LEDGER = join(BLUEPRINT_DIR, 'module-progress.md');

/** 交付状态词表 —— 统计与校验都按这三个值判定 */
const STATUS = { DONE: '已交付', WIP: '进行中', TODO: '未开发' };
const VALID_STATUS = new Set(Object.values(STATUS));

/**
 * 卷 → 交付阶段。依据蓝图 README §8 分期表，作为**默认值**。
 * 跨期卷用下面的 MODULE_PHASE 逐模块覆盖。
 */
const VOLUME_PHASE = {
  '01': 'D7',
  '02': 'D1–D2',
  '03': 'D1–D2',
  '04': 'D5',
  '05': 'D6',
  '06': 'D6',
  '07': 'D3',
  '08': 'D3',
  '09': 'D4',
  10: 'D5',
  11: 'D4',
  12: 'D6',
  13: 'D7',
  14: 'D7',
  15: 'D7',
  16: 'D7',
  17: 'D1–D2',
  18: 'D7',
};

/**
 * 逐模块阶段覆盖 —— 阶段列必须反映**依赖就绪顺序**，不是卷归属。
 *
 * 错一次的后果很实：01 工作台是纯投影聚合层（蓝图原文「不写业务数据，只读投影」，
 * 上游是 09 订单 / 07 库存 / 12 财务 / 15 客户 / 17 审批）。按卷归属把它算进 D1–D2，
 * 调度就会先去建「我的待办」，而它要聚合的投影一个都还不存在 —— 只能造假数据。
 * 所以 01 整卷排到 D7，等数据源齐了再收口。
 */
const MODULE_PHASE = {
  // ── 02 渠道与店铺：映射缺失队列与常用回写要等 04 刊登与真实平台对接 ──
  '2.4.2.3': 'D5', // 映射缺失队列 —— 依赖 04 的 listing 映射
  '2.5.2.1': 'D5', // 常用回写操作 —— 依赖真实平台 API 集成
  '2.6.1.1': 'D5', // 平台消息接收记录
  '2.6.1.2': 'D5', // 去重视图
  '2.6.2.1': 'D5', // 增量补拉

  // ── 08 仓储作业：入库侧 D3，出库侧 D4 ──
  '8.3.1.1': 'D4',
  '8.3.1.2': 'D4', // 出库单
  '8.3.2.1': 'D4',
  '8.3.2.2': 'D4', // 拣货
  '8.3.3.1': 'D4',
  '8.3.3.2': 'D4', // 复核打包
  '8.3.4.1': 'D4',
  '8.3.4.2': 'D4', // 发货交接
  '8.4.1.1': 'D4',
  '8.4.2.1': 'D4', // 组装拆解
  '8.5.1.1': 'D4',
  '8.5.2.1': 'D4',
  '8.5.3.1': 'D4', // 作业效率与库位利用率

  // ── 09 订单履约：手工单 D4，平台同步与回传 D5 ──
  '9.1.2.1': 'D5', // 平台订单同步
  '9.4.2.1': 'D5', // 平台发货回传

  // ── 10 物流与头程：尾程 D5，头程与报关 D7 ──
  '10.4.1.1': 'D7',
  '10.4.1.2': 'D7', // 头程发运
  '10.4.2.1': 'D7',
  '10.4.2.2': 'D7', // 海外仓对接
  '10.5.1.1': 'D7',
  '10.5.1.2': 'D7',
  '10.5.1.3': 'D7', // 报关
  '10.5.2.1': 'D7', // 贸易单证

  // ── 11 售后与退货：售后闭环 D4，纠纷工单与分析 D7 ──
  '11.4.1.1': 'D7',
  '11.4.2.1': 'D7', // 平台纠纷 / 客诉工单
  '11.5.1.1': 'D7',
  '11.5.2.1': 'D7', // 退货与质量分析

  // ── 17 组织权限：主体 D1–D2，审批随单据 D4，账簿随财务 D6，套餐与平台参数 D7 ──
  '17.4.1.1': 'D4',
  '17.4.1.2': 'D4',
  '17.4.2.1': 'D4',
  '17.4.2.2': 'D4',
  '17.6.1.1': 'D6',
  '17.7.1.1': 'D7',
  '17.7.1.2': 'D7',
  '17.7.2.1': 'D7',
  '17.7.2.2': 'D7',
};

/** 从卷文件名与正文标题取系统名，如 07-inventory.md → 07 库存核算 */
function parseVolume(file) {
  const num = file.slice(0, 2);
  const raw = readFileSync(join(BLUEPRINT_DIR, file), 'utf8');
  const heading = raw.match(/^#\s+(\d{2})\s+(.+)$/m);
  return {
    file,
    num,
    system: heading ? heading[2].trim() : num,
    body: raw,
  };
}

/**
 * 抽出功能树里的 L2/L3/L4 节点。
 * 只在 ``` 代码块内匹配，避免正文里的交叉引用（如「下游 = 2.2.1.2」）被误当成模块。
 */
function extractTree(body) {
  const nodes = [];
  let l3Route = null;
  let l3Id = null;
  const line = /^[│\s]*[├└]──\s+(\d+(?:\.\d+){1,3})\s+(.+?)(?:\s+→\s+(\S+))?\s*$/;

  for (const raw of body.split('\n')) {
    const m = raw.match(line);
    if (!m) continue;
    const [, id, rest, route] = m;
    const depth = id.split('.').length;

    if (depth === 2) {
      l3Route = null;
      l3Id = null;
    } else if (depth === 3) {
      l3Id = id;
      l3Route = route ?? null;
    } else if (depth === 4) {
      // L4 自身带路由就用自己的，否则继承所属 L3 的锚点路由
      nodes.push({
        id,
        name: rest.trim(),
        volume: id.slice(0, 2),
        l3Id,
        route: route ?? l3Route,
      });
    }
  }
  return nodes;
}

/** 读既有台账，取回「状态」「证据」两列 */
function readExisting() {
  const map = new Map();
  let text;
  try {
    text = readFileSync(LEDGER, 'utf8');
  } catch {
    return map; // 首次生成
  }
  const row = /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|/gm;
  let m;
  while ((m = row.exec(text)) !== null) {
    const [, id, name, route, phase, status, evidence] = m;
    const s = (status ?? '').trim();
    // 状态非法（被手改坏）时不继承，回落到未开发，避免统计被污染
    map.set(id.trim(), {
      name: name.trim(),
      status: VALID_STATUS.has(s) ? s : STATUS.TODO,
      evidence: (evidence ?? '').trim(),
    });
  }
  return map;
}

function build() {
  const volumes = readdirSync(BLUEPRINT_DIR)
    .filter((f) => /^\d{2}-.*\.md$/.test(f))
    .sort();

  const existing = readExisting();
  const rows = [];

  for (const file of volumes) {
    const vol = parseVolume(file);
    const phase = VOLUME_PHASE[vol.num] ?? '未分期';
    for (const node of extractTree(vol.body)) {
      const prev = existing.get(node.id);
      rows.push({
        id: node.id,
        name: node.name,
        system: vol.system,
        volume: vol.num,
        phase: MODULE_PHASE[node.id] ?? phase,
        route: node.route ?? '—',
        status: prev?.status ?? STATUS.TODO,
        evidence: prev?.evidence ?? '',
      });
    }
  }

  // 蓝图里已删除但台账还留着的模块 —— 报出来，让人确认，不静默丢弃
  const inBlueprint = new Set(rows.map((r) => r.id));
  const orphans = [...existing.keys()].filter((id) => !inBlueprint.has(id));

  return { rows, orphans, volumeCount: volumes.length };
}

function render({ rows, orphans }) {
  const tally = { [STATUS.DONE]: 0, [STATUS.WIP]: 0, [STATUS.TODO]: 0 };
  for (const r of rows) tally[r.status] += 1;

  const bySystem = new Map();
  for (const r of rows) {
    if (!bySystem.has(r.volume)) {
      bySystem.set(r.volume, { system: r.system, phases: new Set(), total: 0, done: 0 });
    }
    const b = bySystem.get(r.volume);
    b.phases.add(r.phase);
    b.total += 1;
    if (r.status === STATUS.DONE) b.done += 1;
  }

  // 阶段汇总 —— 跨期卷（如 08 同时含 D3/D4）按卷聚合会失真，交付顺序看阶段
  const byPhase = new Map();
  for (const r of rows) {
    if (!byPhase.has(r.phase)) byPhase.set(r.phase, { total: 0, done: 0 });
    const b = byPhase.get(r.phase);
    b.total += 1;
    if (r.status === STATUS.DONE) b.done += 1;
  }

  const pct = (n) => (tally[STATUS.DONE] * 100) / rows.length;
  const line = '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |';
  const sep = '| --- | --- | --- | --- | --- | --- |';

  const body = rows
    .map(
      (r) =>
        `| ${r.id} | ${r.name} | ${r.route} | ${r.phase} | ${r.status} | ${r.evidence || '—'} |`,
    )
    .join('\n');

  const summary = [...bySystem.entries()]
    .map(
      ([v, b]) => `| ${v} | ${b.system} | ${[...b.phases].join(' / ')} | ${b.done} | ${b.total} |`,
    )
    .join('\n');

  const phaseSummary = [...byPhase.entries()]
    .sort(([a], [b]) => a.localeCompare(b, 'en'))
    .map(([p, b]) => `| **${p}** | ${b.done} | ${b.total - b.done} | ${b.total} |`)
    .join('\n');

  return `# 功能蓝图交付进度台账

> **本文件由 \`node tools/module-progress/sync.mjs\` 生成，请勿手工增删行。**
> 模块的 ID、名称、路由来自 [功能蓝图](README.md)（18 卷，465 个 L4 模块），
> 本脚本只负责同步。**「状态」「证据」两列是人工维护的，脚本按模块 ID 继承。**
>
> 状态词表只有三个值，统计逻辑依赖它，不要新增：
> \`已交付\`（迁移 + API + 页面 + 测试齐全，无 TODO 残留）· \`进行中\`（有分支在做，未收口）· \`未开发\`（未开工）。

## 总览

| 指标 | 数量 |
| --- | --- |
| 模块总数 | ${rows.length} |
| 已交付 | ${tally[STATUS.DONE]} |
| 进行中 | ${tally[STATUS.WIP]} |
| **未开发** | **${tally[STATUS.TODO]}** |
| 完成率 | ${pct().toFixed(1)}% |

## 分阶段进度（交付顺序看这里）

| 阶段 | 已交付 | 未开发 | 合计 |
| --- | --- | --- | --- |
${phaseSummary}

## 分系统进度

| 卷 | 系统 | 阶段 | 已交付 | 模块数 |
| --- | --- | --- | --- | --- |
${summary}

## 判定「已交付」的标准

一个模块标 \`已交付\` 当且仅当下面四件都成立，缺一件就是 \`未开发\` 或 \`进行中\`：

1. **迁移** — \`backend/services/<svc>/src/main/resources/db/migration/V*.sql\` 里有真实表结构，
   带 \`tenant_id NOT NULL\` + RLS 策略，不是注释里的设计。
2. **API** — 服务里有对应端点，走 jOOQ 显式 SQL，不是返回硬编码常量的桩。
3. **页面** — \`apps/web/src/app/**/page.tsx\` 是真实工作区，不是 \`DomainPlaceholder\`。
4. **测试** — \`cd backend && ./mvnw verify\` 绿；涉及租户隔离的必须实跑
   「两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户」三条断言。

**判定为 \`未开发\` 的典型形态**（见到就说明没做完，不许算进已交付）：

- \`DomainPlaceholder\` 占位页
- 硬编码数组 / 常量当数据源
- 服务里 \`TODO\` + \`throw new UnsupportedOperationException()\`
- 迁移文件存在但没有 RLS 策略
- 前端有页面但后端没有对应端点（或反之）

## 跨期说明

「阶段」列按**依赖就绪顺序**逐模块标注，不是卷归属。跨期卷的拆分如下：

- **01 工作台与门户 → 全部 D7**：它是纯投影聚合层（蓝图原文「不写业务数据，只读投影」），
  聚合 09 订单 / 07 库存 / 02 渠道 / 12 财务 / 15 客户 / 17 审批的投影。
  这些数据源没落地之前建「我的待办」只能造假数据，所以整卷排到最后收口。
- **02 渠道与店铺**：D1–D2 交付平台目录、授权连接、店铺站点、同步运行、平台回写；
  映射缺失队列（2.4.2.3）、常用回写操作（2.5.2.1）与 2.6 平台消息接入排 D5，
  因为它们依赖 04 刊登映射与真实平台 API 集成。
- **08 仓储作业**：D3 交付入库侧（仓库档案、库位、收货、上架）；8.3 出库 / 8.4 组装拆解 / 8.5 作业分析排 D4。
- **09 订单履约**：D4 交付手工单闭环；平台订单同步（9.1.2.1）与发货回传（9.4.2.1）排 D5。
- **10 物流与头程**：D5 交付尾程（承运商、运费、运单面单、轨迹交运、逆向退货）；
  10.4 头程与海外仓、10.5 报关清关排 D7。
- **11 售后与退货**：D4 交付售后单、退货授权与实收、退款换货；平台纠纷 / 客诉工单 / 分析排 D7。
- **17 组织权限**：D1–D2 交付租户、法人、部门、成员、角色、页面权限、数据范围、审计；
  17.4 审批流随单据排 D4，17.6.1.1 账簿与本位币随财务排 D6，17.7 套餐与平台参数排 D7。

## 模块明细

${line}
${sep}
${body}
${orphans.length ? `\n## 台账漂移告警\n\n以下 ID 在既有台账里但已不在蓝图中，请确认是否已删除：\n\n${orphans.map((o) => `- \`${o}\``).join('\n')}\n` : ''}`;
}

const check = process.argv.includes('--check');
const result = build();
const next = render(result);

if (check) {
  let current = '';
  try {
    current = readFileSync(LEDGER, 'utf8');
  } catch {
    /* 首次生成 */
  }
  if (current === next) {
    console.log('✓ 台账与蓝图一致');
    process.exit(0);
  }
  console.error('✗ 台账与蓝图不一致，运行：node tools/module-progress/sync.mjs');
  process.exit(1);
}

writeFileSync(LEDGER, next);
const tally = { 已交付: 0, 进行中: 0, 未开发: 0 };
for (const r of result.rows) tally[r.status] += 1;
console.log(
  `✓ 同步 ${result.volumeCount} 卷 / ${result.rows.length} 个 L4 模块\n` +
    `  已交付 ${tally.已交付} · 进行中 ${tally.进行中} · 未开发 ${tally.未开发}` +
    (result.orphans.length ? `\n  ⚠ 台账中有 ${result.orphans.length} 个孤儿 ID` : ''),
);
