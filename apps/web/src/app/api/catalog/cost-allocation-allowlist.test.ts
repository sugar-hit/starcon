import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.2.1.3 成本分摊的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件最要紧的是最后两条：**共享白名单是累积并集**。本车道只新增一条
 * `catalog/` 元素（锚在 services[] 的最末尾），兄弟车道的条目一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会**静默**删掉已交付模块的路由：
 * 表现是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/catalog.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/catalog/cost-allocations';

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 🔴 版本号段是 **int**，不是 UUID —— 白名单里它也是 `\d+`。
    // 把所有占位符一律替换成 UUID 会造出一条**根本不存在的**路径（/revisions/<uuid>），
    // 然后用它的 404 去证明「白名单漏登记」—— 那是在自己骗自己。
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{revisionNo\}/g, '1')
      .replace(/\{[^}]+\}/g, ID);
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

  expect(sites).toEqual(
    expect.arrayContaining([
      'catalog/cost-allocations',
      'catalog/cost-allocations/capabilities',
      'catalog/cost-allocations/sources',
      `catalog/cost-allocations/${ID}`,
      `catalog/cost-allocations/${ID}/revisions`,
      `catalog/cost-allocations/${ID}/revisions/1`,
      `catalog/cost-allocations/${ID}/recalculate`,
      `catalog/cost-allocations/${ID}/diffs`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/cost-allocations',
    `catalog/cost-allocations/${ID}/revisions`,
    `catalog/cost-allocations/${ID}/recalculate`,
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/cost-allocations'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 明细行随明细与版本一起读，没有独立端点
    `catalog/cost-allocations/${ID}/lines`,
    // 历史不可变：分摊单不可删
    `catalog/cost-allocations/${ID}/delete`,
    // 没有批量分摊
    'catalog/cost-allocations/batch',
    // 版本号是 int，不是 UUID
    `catalog/cost-allocations/${ID}/revisions/abc`,
    // 5.2.1.1 成本构成不提供「就地分摊」入口
    `catalog/sku-cost-compositions/${ID}/allocations`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('🔴 兄弟车道的 catalog 白名单条目未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 其它车道**已交付**的 catalog 模块各挑一条真实 path —— 少一条都会让
  // 「本车道取错一侧静默删掉别人路由」这件事失去唯一的守门人。
  const siblings = [
    // 3.1.1.1 商品主数据
    'catalog/products',
    // 3.1.2.1 SKU 列表
    'catalog/skus',
    // 3.2.1.1 属性字典
    'catalog/attributes',
    // 3.1.4.1 品牌
    'catalog/brands',
    // 3.3.3 计量单位
    'catalog/unit-groups',
    // 3.1.3.1 规格轴
    'catalog/spec-axis/overview',
    // 3.6.1.1 往来单位
    'catalog/parties',
    // 3.6.2.1 供应商报价
    'catalog/supplier-quotes',
    // 6.1.1.1 供应商列表（采购视角）
    'catalog/supply/vendors',
    // 3.8.2.1 导出
    'catalog/export-jobs',
    // 11.4.2.2 客服话术
    'catalog/kb/rule-versions',
    // 5.2.1.1 成本构成（同 L3 的兄弟模块）
    'catalog/sku-cost-compositions',
    'catalog/sku-cost-compositions/capabilities',
  ];
  for (const path of siblings) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
  }
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `capabilities` 的首字母 `c` 落在 [0-9a-f] 里 —— 若判别顺序写反，
  // 它会被当成「id=capabilities 的那张分摊单」拼出去。
  for (const path of [
    'catalog/cost-allocations/capabilities',
    'catalog/cost-allocations/sources',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行`).not.toBe(404);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.anything(),
    );
  }
});
