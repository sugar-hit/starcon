import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 13.1.2.2 达成追踪 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「达成追踪」已经可用。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `reporting/`（target-attainment）元素，
 * 13.1.1.1 的 `reporting/overview` 与 `reporting/projection`、13.4.2.2 的
 * `reporting/expense-variance`、13.1.1.2 的 `reporting/store-comparison`、
 * 13.2.1.1 的 `reporting/sales-trend`、1.1.2.1 的 `reporting/metric-cards`、
 * 1.1.2.3 的 `reporting/store-overview` **一字未动** —— 但那必须被断言钉住。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/reporting.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/reporting/target-attainment';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/reporting/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `reporting/`。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path.replace(/^\//, '').replace('{id}', ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

function callProxy(fullPath: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return proxy(new NextRequest(`http://localhost:3000/api/${fullPath}`, init), {
    params: Promise.resolve({ path: fullPath.split('/') }),
  } as never);
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      // 🔴 达成追踪看板：漏登记它，页面永远读不到达成率表
      'reporting/target-attainment/board',
      // 🔴 排名（人员 / 产品维度）
      'reporting/target-attainment/ranking',
      // 🔴 已落快照（下游 17 绩效核算 / 1 工作台预警读的就是它）
      'reporting/target-attainment/snapshots',
      // 🔴 能力声明
      'reporting/target-attainment/capabilities',
      // 🔴 评估并落快照（POST）
      'reporting/target-attainment/evaluate',
    ]),
  );
});

it('写动作真的转发到 8088（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/target-attainment/evaluate', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST 评估落快照未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/target-attainment/evaluate'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）。
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付的形状仍然 404（放行不该存在的路径比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 顶层没有 id 段（达成追踪按查询参数定位，不是按 id）。
    `reporting/target-attainment/${ID}`,
    // 多一层仍然 404。
    'reporting/target-attainment/unknown-sub',
    // 裸路径**刻意不放行**：后端没有「一条不存在的汇总列表接口」。
    'reporting/target-attainment',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：已交付的 reporting 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 13.1.1.1 经营总览
    'reporting/overview',
    'reporting/overview/capabilities',
    'reporting/overview/metrics',
    'reporting/overview/trend',
    'reporting/overview/drilldown',
    // 13.1.1.1 投影管道
    'reporting/projection/events',
    'reporting/projection/refresh',
    'reporting/projection/rebuild',
    // 13.4.2.2 费用差异
    'reporting/expense-variance/capabilities',
    'reporting/expense-variance/variance',
    'reporting/expense-variance/trend',
    'reporting/expense-variance/recommendations',
    'reporting/expense-variance/by-sku',
    'reporting/expense-variance/refresh',
    // 13.1.1.2 多店对比
    'reporting/store-comparison',
    'reporting/store-comparison/capabilities',
    'reporting/store-comparison/metrics',
    'reporting/store-comparison/shops',
    'reporting/store-comparison/refresh',
    // 13.2.1.1 销量趋势
    'reporting/sales-trend',
    'reporting/sales-trend/capabilities',
    'reporting/sales-trend/comparison',
    'reporting/sales-trend/events',
    'reporting/sales-trend/metrics',
    'reporting/sales-trend/refresh',
    // 13.1.2.1 目标设定
    'reporting/targets',
    'reporting/targets/capabilities',
    'reporting/targets/metrics',
    'reporting/targets/owners',
    'reporting/targets/current-period',
    'reporting/targets/decomposition',
    `reporting/targets/${ID}`,
    `reporting/targets/${ID}/baseline`,
    // 1.1.2.1 核心指标卡
    'reporting/metric-cards',
    'reporting/metric-cards/capabilities',
    // 1.1.2.3 店铺概览
    'reporting/store-overview',
    'reporting/store-overview/capabilities',
    'reporting/store-overview/shops',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 target-attainment 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'finance/target-attainment',
    'catalog/target-attainment',
    'channel/target-attainment',
    'identity/target-attainment',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});
