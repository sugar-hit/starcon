#!/usr/bin/env node
/**
 * 微服务闸「前端聚合层」分支 + 卷粒度上游就绪判定的回归测试
 *
 * 背景（2026-10-23 取证）：
 *
 * ① **卷 01 被永久误封（死代码分支）**
 *   `serviceGate()` 里有一条注释「主责不是服务标识符（如卷 01「前端聚合 +
 *   各服务投影」）→ 纯前端聚合层，照排」，但那条分支**永远进不去**：
 *   `readVolumeOwners` 取的是「后端主责服务」列按 '+' 拆开后的第一项，
 *   卷 01 的原文是「前端聚合 + 各服务投影」⇒ primary = '前端聚合'（非空）
 *   ⇒ `!primary` 为 false ⇒ 落到 `serviceInHead('前端聚合')`。
 *   而 `serviceInHead` 的正则 `/^[a-z][a-z0-9_-]*$/` 只认 ASCII 服务标识符，
 *   非 ASCII 一律 false ⇒ 卷 01 被判「主责服务不在集成头」⇒ 永久阻塞。
 *
 *   但卷 01 根本不需要后端服务 —— 蓝图 01 开头写死了：
 *     「后端归属：前端聚合层 + 各服务的只读投影（**不新增业务服务**）」
 *   于是 10 个批次（batch-54/55/56/57/64/65/68/70/71/72）都把卷 01 记成
 *   「服务缺口」，等着「把前端聚合服务并进集成头」—— 而它永远不会存在。
 *
 * ② **卷粒度上游对上游图失明**
 *   蓝图对上游有两种粒度：「上游 = 11.2.3.1」（L4）与「上游 = 09 订单、07 库存」
 *   （卷）。`readUpstreamMap` 原来只解 L4 ⇒ 卷 01 的上游图整卷失明 ⇒
 *   每个卷 01 模块的 upstream 都是空数组 ⇒ `every()` 恒真 ⇒ 一律判「上游已就绪」。
 *   于是闸门一旦放行卷 01，就会把「上游是 12 财务（0 交付）的模块」也排出去 ——
 *   正是微服务闸当初要消灭的那类浪费（一条车道 40 分钟 + 一个 worktree +
 *   一个 Flyway 版本号，产出为零）。
 *
 * ③ **serviceBlocked 会被上游过滤吃掉**
 *   serviceBlocked 原来只在「排到 tryTake 时才撞上闸」的卷上登记 ⇒
 *   同时被上游挡住的卷（如卷 13 reporting）整个从报告里消失 ⇒
 *   下一轮会误判成「服务缺口已清干净」。
 *
 * 这里起临时仓库，跑**真的 lane.mjs plan**，断言 8 件事：
 *   1. 主责是「前端聚合 + 各服务投影」的卷照排（不再被误封）
 *   2. 主责是真服务标识符、但服务不在集成头的卷照旧被挡
 *   3. 上游卷**整卷 0 交付** ⇒ 硬阻塞（那个上游事实上不存在）
 *   4. 上游卷**已交付过一部分** ⇒ 放行（解不出是哪个 L4，不能凭卷号误杀）
 *   5. 自引用不算依赖（上游写的是自己那个卷 ⇒ 不挡自己）
 *   6. L4 粒度上游仍然生效（新规则不破坏老规则）
 *   7. 同时被上游和服务缺口双挡的卷，仍要出现在 serviceBlocked 里
 *   8. 可落地的卷不会被误报进 serviceBlocked
 *
 * 用法：node tools/module-progress/lane-volume-upstream.itest.mjs
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'lane-volume-upstream-itest');
const LANE = join(ROOT, 'tools', 'module-progress', 'lane.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

/**
 * 造沙箱。
 * @param services 集成头里实际存在的服务目录名
 * @param owners   卷号 → 主责服务字符串（与真实 README 同格式）
 * @param modules  模块夹具：{ id, name, status, upstream }
 */
function makeSandbox(services, owners, modules) {
  if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
  mkdirSync(SANDBOX, { recursive: true });

  g(['init', '-q', '-b', 'main'], SANDBOX);
  g(['config', 'user.email', 'test@starcon.local'], SANDBOX);
  g(['config', 'user.name', 'test'], SANDBOX);

  const bp = join(SANDBOX, 'docs', 'product', 'capability-blueprint');
  mkdirSync(bp, { recursive: true });

  const rows = Object.entries(owners).map(
    ([v, svc]) =>
      `| ${v.padStart(2, '0')}  | 卷${v} | 核心业务域 | 测试 | ${svc} | [${v}](x.md) |`,
  );
  writeFileSync(
    join(bp, 'README.md'),
    [
      '| # | 系统 | 层级归类 | 一句话职责 | 后端主责服务 | 卷 |',
      '| --- | --- | --- | --- | --- | --- |',
      ...rows,
      '',
    ].join('\n'),
  );

  for (const s of services) {
    const d = join(SANDBOX, 'backend', 'services', s);
    mkdirSync(d, { recursive: true });
    writeFileSync(join(d, 'pom.xml'), '<project/>\n');
  }

  // 蓝图：每个模块一段，「关系」字段写成真实的两种粒度
  writeFileSync(
    join(bp, '01-test.md'),
    modules
      .map(
        (m) =>
          [
            `### ${m.id} ${m.name}`,
            '',
            '- **职责**：测试用',
            `- **关系**：上游 = ${m.upstream}`,
            '',
          ].join('\n'),
      )
      .join('\n'),
  );

  const row = (m) =>
    `| ${m.id} | ${m.name} | /${m.id.replace(/\./g, '/')} | D7 | ${m.status} | — |`;
  writeFileSync(
    join(bp, 'module-progress.md'),
    [
      '| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |',
      '| --- | --- | --- | --- | --- | --- |',
      ...modules.map(row),
      '',
    ].join('\n'),
  );

  g(['add', '-A'], SANDBOX);
  g(['commit', '-qm', 'fixtures'], SANDBOX);
  g(['branch', 'integration/module-dev'], SANDBOX);

  mkdirSync(join(SANDBOX, '.dev-agent'), { recursive: true });
  writeFileSync(
    join(SANDBOX, '.dev-agent', 'lanes.json'),
    JSON.stringify({ integrationBranch: 'integration/module-dev', lanes: {} }, null, 2),
  );

  // 与 lane-service-gate.itest.mjs 同样屏蔽 pnpm install（闸门逻辑一个字节没动）
  mkdirSync(join(SANDBOX, 'tools', 'module-progress'), { recursive: true });
  const src = readFileSync(LANE, 'utf8');
  const patched = src.replace(
    "sh('pnpm install --prefer-offline', wt);",
    'void wt; // itest: 屏蔽 pnpm install（闸门逻辑未改动）',
  );
  check(
    '沙箱补丁命中 pnpm install 那行',
    patched !== src,
    'lane.mjs 结构变了，需要同步更新本测试',
  );
  writeFileSync(join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'), patched);
}

function runPlan(laneCount = 8) {
  const r = spawnSync(
    'node',
    [
      join(SANDBOX, 'tools', 'module-progress', 'lane.mjs'),
      'plan',
      '--lanes',
      String(laneCount),
      '--dry-run',
    ],
    { cwd: SANDBOX, encoding: 'utf8', timeout: 60_000 },
  );
  return { status: r.status, out: r.stdout, err: r.stderr };
}

/* ── 场景 ─────────────────────────────────────────────────────────────── */

console.log('卷 01 前端聚合层放行 + 卷粒度上游就绪判定\n');

makeSandbox(
  ['warehouse'],
  {
    '01': '前端聚合 + 各服务投影', // 非服务标识符 ⇒ 纯前端聚合层 ⇒ 照排
    '02': 'warehouse',
    '03': 'warehouse',
    '04': 'warehouse',
    '05': 'warehouse',
    '06': 'catalog', // 服务不在集成头 ⇒ 服务缺口
    '07': 'catalog', // 服务不在集成头 **且** 上游卷 0 交付 ⇒ 双挡
  },
  [
    // 已交付的模块：让卷 09 / 17 变成「已交付过一部分」
    { id: '9.1.1.1', name: '卷九已交付', status: '已交付', upstream: '无' },
    { id: '17.1.1.1', name: '卷十七已交付', status: '已交付', upstream: '无' },
    // ① 主责是前端聚合层 ⇒ 照排
    { id: '1.1.1.1', name: '卷一前端聚合', status: '未开发', upstream: '各系统只读检索索引' },
    // ③ 上游卷 12 整卷 0 交付 ⇒ 硬阻塞
    { id: '2.1.1.1', name: '卷二上游卷零交付', status: '未开发', upstream: '12 财务' },
    // ④ 上游卷 17 已交付过一部分 ⇒ 放行
    { id: '3.1.1.1', name: '卷三上游卷部分交付', status: '未开发', upstream: '17 组织权限' },
    // ⑤ 自引用不算依赖 ⇒ 放行
    { id: '4.1.1.1', name: '卷四自引用', status: '未开发', upstream: '04 基础数据' },
    // ⑥ L4 粒度上游仍然生效 ⇒ 挡住
    { id: '5.1.1.1', name: '卷五L4上游', status: '未开发', upstream: '5.1.1.2' },
    { id: '5.1.1.2', name: '卷五L4上游的靶子', status: '未开发', upstream: '无' },
    // ② 服务缺口（catalog 不在集成头）⇒ 挡住
    { id: '6.1.1.1', name: '卷六缺服务', status: '未开发', upstream: '各系统只读索引' },
    // ⑦ 双挡：服务缺口 + 上游卷 0 交付 ⇒ 不排，但必须在 serviceBlocked 里
    { id: '7.1.1.1', name: '卷七双挡', status: '未开发', upstream: '12 财务' },
  ],
);

const r = runPlan();
check('plan 正常退出', r.status === 0, r.err.slice(0, 300));
const j = JSON.parse(r.out);

console.log('  排出来:', j.newlyPlanned.join(' ') || '(空)');
console.log('  serviceBlocked:', j.serviceBlocked.map((b) => `vol${b.volume}`).join(' ') || '(空)');
console.log('');

check(
  '① 主责是「前端聚合 + 各服务投影」的卷照排：1.1.1.1 必须出现',
  j.newlyPlanned.includes('1.1.1.1'),
  `got=${j.newlyPlanned.join(',')} —— 卷 01 又被误封了`,
);
check(
  '② 主责是真服务标识符但服务不在集成头的卷照旧被挡：6.1.1.1 必须缺席',
  !j.newlyPlanned.includes('6.1.1.1'),
  `got=${j.newlyPlanned.join(',')}`,
);
check(
  '③ 上游卷整卷 0 交付 ⇒ 硬阻塞：2.1.1.1（上游 12 财务）必须缺席',
  !j.newlyPlanned.includes('2.1.1.1'),
  `got=${j.newlyPlanned.join(',')}`,
);
check(
  '④ 上游卷已交付过一部分 ⇒ 放行：3.1.1.1（上游 17）必须出现',
  j.newlyPlanned.includes('3.1.1.1'),
  `got=${j.newlyPlanned.join(',')} —— 凭卷号误杀了能干的活`,
);
check(
  '⑤ 自引用不算依赖：4.1.1.1（上游 04 = 自己）必须出现',
  j.newlyPlanned.includes('4.1.1.1'),
  `got=${j.newlyPlanned.join(',')}`,
);
check(
  '⑥ L4 粒度上游仍然生效：5.1.1.1（上游 5.1.1.2 未交付）必须缺席',
  !j.newlyPlanned.includes('5.1.1.1'),
  `got=${j.newlyPlanned.join(',')}`,
);

const blockedVols = j.serviceBlocked.map((b) => b.volume);
check(
  '⑦ 双挡的卷 7 仍出现在 serviceBlocked 里（不能被上游过滤吃掉）',
  blockedVols.includes('7'),
  `blocked=${blockedVols.join(',')} —— 永久性服务缺口从报告里消失了`,
);
check(
  '⑦ 服务缺口卷 6 也出现在 serviceBlocked 里',
  blockedVols.includes('6'),
  `blocked=${blockedVols.join(',')}`,
);
check(
  '⑧ 可落地的卷不会被误报进 serviceBlocked',
  !blockedVols.includes('1') && !blockedVols.includes('3') && !blockedVols.includes('4'),
  `blocked=${blockedVols.join(',')}`,
);
check(
  '⑧ serviceBlocked 带原因与样本模块',
  j.serviceBlocked.every(
    (b) => typeof b.reason === 'string' && b.reason.length > 0 && b.sample.length > 0,
  ),
  JSON.stringify(j.serviceBlocked),
);

console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 条失败`}`);
process.exit(failures === 0 ? 0 : 1);
