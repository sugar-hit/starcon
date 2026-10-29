import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 13.2.1.1 销量趋势 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，而真正会出事
 *       的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「销量趋势」已经可用。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `reporting/`（sales-trend）元素，13.1.1.1 的
 * `reporting/overview` 与 `reporting/projection`、13.4.2.2 的 `reporting/expense-variance`、
 * 13.1.1.2 的 `reporting/store-comparison` 一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/reporting/sales-trend';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/reporting/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `reporting/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`reporting/` 这段服务前缀必须保留。
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
      // 🔴 趋势本体：漏登记它，页面永远读不到趋势
      'reporting/sales-trend',
      // 🔴 多维对比：漏登记它，店铺间 / 产品间对比那块永远是空的
      'reporting/sales-trend/comparison',
      // 🔴 异常日期标注：漏登记它，大促 / 缺货 / 断货挂不上
      'reporting/sales-trend/events',
      'reporting/sales-trend/capabilities',
      'reporting/sales-trend/metrics',
      'reporting/sales-trend/refresh',
    ]),
  );
});

it('写动作真的转发到 8088（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/sales-trend/refresh', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST refresh 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/sales-trend'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('趋势的裸路径也被放行（它是 @GetMapping 在类根上的）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/sales-trend', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status, '裸路径未放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/sales-trend'),
    expect.anything(),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'reporting/sales-trend';
  for (const path of [
    // 顶层没有 id 段（趋势按查询参数定位，不是按 id）
    `${base}/${ID}`,
    // 多一层仍然 404
    `${base}/capabilities/extra`,
    `${base}/comparison/extra`,
    `${base}/events/extra`,
    `${base}/metrics/extra`,
    // 非本模块的子路径
    `${base}/not-a-shape`,
    // 🔴 异常清单随趋势响应一起返回，没有独立端点
    `${base}/anomalies`,
    // 🔴 销量预警未交付（卷 13 的预警订阅是另一格）
    `${base}/alerts`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：13.1.1.1 / 13.4.2.2 / 13.1.1.2 的 reporting 路由仍然放行', async () => {
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
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 sales-trend 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 销量趋势落在 reporting 服务，不在 finance / catalog / trade 上开第二条映射
  for (const path of [
    'finance/sales-trend',
    'catalog/sales-trend',
    'trade/sales-trend',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});
