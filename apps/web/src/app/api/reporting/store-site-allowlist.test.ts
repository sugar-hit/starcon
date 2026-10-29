import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 13.2.2.1 店铺与站点的 api-proxy 白名单**双向**断言。
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
 *       的���「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「店铺与站点拆解」已经可用。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `reporting/`（store-site）元素，13.1.1.1 的
 * `reporting/overview` 与 `reporting/projection`、13.4.2.2 的 `reporting/expense-variance`、
 * 13.1.1.2 的 `reporting/store-comparison`、1.1.2.1 的 `reporting/metric-cards`、
 * 1.1.2.2 的 `reporting/store-overview`、13.1.2.1 的 `reporting/targets`、
 * 13.2.1.1 的 `reporting/sales-trend`、13.2.2.2 的 `reporting/product-sales`
 * 一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 *
 * <p>🔴 **还要钉住「Next 路由层有出口」**：白名单断言是**直接调用 `proxy()`**，
 * 绕开了 Next 的路由匹配 —— 所以「白名单绿」**不等于**「浏览器里打得到」。
 * 本文件最后一条断言就是补这个洞的（reporting 此前**没有** `[...path]/route.ts`）。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL(
        '../../../../../../packages/api-contracts/src/reporting.openapi.json',
        import.meta.url,
      ),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/reporting/store-site';

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
      // 🔴 维度拆解报表：漏登记它，页面永远读不到报表
      'reporting/store-site',
      // 🔴 构成明细下钻：漏登记它，「每个汇总数字都能钻到构成明细」这条红线就断了
      'reporting/store-site/drilldown',
      'reporting/store-site/capabilities',
      'reporting/store-site/metrics',
      'reporting/store-site/refresh',
    ]),
  );
});

it('写动作真的转发到 8088（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/store-site/refresh', {
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
    expect.stringContaining(':8088/reporting/store-site'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('维度拆解报表的裸路径也被放行（它是 @GetMapping 在类根上的）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/store-site', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status, '裸路径未放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/store-site'),
    expect.anything(),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'reporting/store-site';
  for (const path of [
    // 顶层没有 id 段（报表按查询参数定位，不是按 id）
    `${base}/${ID}`,
    // 多一层仍然 404
    `${base}/capabilities/extra`,
    `${base}/drilldown/extra`,
    `${base}/metrics/extra`,
    `${base}/refresh/extra`,
    // 非本模块的子路径
    `${base}/not-a-shape`,
    // 🔴 店铺清单随报表的行一起给出，没有独立端点
    `${base}/shops`,
    // 🔴 渠道占比随响应里的 channelShares 给出，没有独立端点
    `${base}/channels`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.2.1.1 / 13.2.2.2 的 reporting 路由仍然放行', async () => {
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
    // 13.2.2.2 SKU/SPU/Listing
    'reporting/product-sales',
    'reporting/product-sales/capabilities',
    'reporting/product-sales/drilldown',
    'reporting/product-sales/metrics',
    'reporting/product-sales/refresh',
    'reporting/product-sales/spu-summary',
    'reporting/product-sales/variants',
    // 13.1.2.x 目标设定
    'reporting/targets',
    'reporting/targets/capabilities',
    'reporting/targets/metrics',
    // 13.1.2.x 店铺概览
    'reporting/store-overview/capabilities',
    'reporting/store-overview/shops',
    // 13.1.1.x 指标卡
    'reporting/metric-cards',
    'reporting/metric-cards/capabilities',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 store-site 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 店铺与站点拆解落在 reporting 服务，不在 trade / catalog / channel 上开第二条映射
  for (const path of ['trade/store-site', 'catalog/store-site', 'channel/store-site']) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});

it('🔴 reporting 在 Next 路由层有出口 —— 白名单绿不等于浏览器里能打到', () => {
  // ⚠️ **这一条是踩过的坑**：白名单（本文件上面的断言）与前端工作区都就位了，
  //   但 `app/api/` 下**只有** catalog / channel / crm / finance / iam / platform /
  //   trade / warehouse 八个服务有 `[...path]/route.ts` —— reporting 一直没有。
  //   ⇒ 浏览器里 `/api/reporting/...` 在 **Next 路由层**就 404，页面永远停在「读不到」，
  //   而上面那些断言是**直接调用 proxy()**（绕开 Next 路由）⇒ 两边都绿、页面却打不开。
  const routeFile = fileURLToPath(new URL('./[...path]/route.ts', import.meta.url));
  expect(
    existsSync(routeFile),
    'apps/web/src/app/api/reporting/[...path]/route.ts 不存在 ⇒ 浏览器里 /api/reporting/* 会在 Next 路由层 404',
  ).toBe(true);
});
