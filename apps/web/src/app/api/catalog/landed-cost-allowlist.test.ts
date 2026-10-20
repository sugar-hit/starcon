import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.2.1.2 到岸成本的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件最要紧的是最后两条：
 *
 * <ul>
 *   <li><b>共享白名单是累积并集</b>：本车道只新增一条 `catalog/` 元素
 *       （紧跟在既有 `catalog/` 元素之后、`channel/` 元素之前），
 *       兄弟车道的条目一字未动 —— 但那必须被断言钉住，
 *       否则下一次并入取错一侧时会**静默**删掉已交付模块的路由：
 *       表现是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 *   <li><b>字面量子路径不能被 uuid 分支吃掉</b>：`capabilities` / `comparisons` /
 *       `parameters` 三个首字母都落在 [0-9a-f] 里（c/p 都在十六进制字母表里），
 *       判别顺序写反就会被当成「id=capabilities 的那张单」拼出去。
 * </ul>
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
const MODULE = '/catalog/landed-costs';

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path.replace(/^\//, '').replace(/\{[^}]+\}/g, ID);
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
      'catalog/landed-costs',
      'catalog/landed-costs/capabilities',
      'catalog/landed-costs/comparisons',
      'catalog/landed-costs/parameters',
      `catalog/landed-costs/${ID}`,
      `catalog/landed-costs/${ID}/components`,
      `catalog/landed-costs/${ID}/recompute`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/landed-costs',
    'catalog/landed-costs/parameters',
    `catalog/landed-costs/${ID}/recompute`,
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
    expect.stringContaining(':8082/catalog/landed-costs'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 没有「按 SKU 取到岸成本」这个资源：到岸成本的身份是**到岸成本单**，不是 SKU。
    'catalog/landed-costs/sku-costs',
    // 没有就地改一行分量的入口：重算走 recompute，参数走 parameters。
    `catalog/landed-costs/${ID}/components/DUTY`,
    // 没有批量计算。
    'catalog/landed-costs/batch',
    // 没有「删除一张到岸成本单」：作废走 status，不做物理删除。
    `catalog/landed-costs/${ID}/void`,
    // 没有「手工改一行合计」：合计由分量之和推导，手改就等于绕过那条纪律。
    `catalog/landed-costs/${ID}/lines`,
  ]) {
    // 🔴 负向样本只能用**本模块自己的前缀**下的形状：
    // 拿别的模块的路径当负样本，会在兄弟模块真的加了那个路径时误报，
    // 而真正的回归（本模块漏放行）反而测不出来。
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
    // 5.2.1.1 成本构成（**同一个 /pricing/cost 页面**的兄弟分区）
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

  // `capabilities` / `comparisons` / `parameters` 的首字母都落在 [0-9a-f] 里 ——
  // 若判别顺序写反，这三条会被当成「id=capabilities 的那张单」拼出去。
  for (const path of [
    'catalog/landed-costs/capabilities',
    'catalog/landed-costs/comparisons',
    'catalog/landed-costs/parameters',
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
