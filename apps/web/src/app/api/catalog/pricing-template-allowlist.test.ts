import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.3.2.1 定价模板的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件最要紧的是最后一条：**共享白名单是累积并集**。本车道只新增一条
 * `catalog/` 元素（插在「5.2.3.1 平台费用」之后、「5.3.1.1 利润测算器」之前），
 * 兄弟车道的条目一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会**静默**删掉已交付模块的路由：
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
const MODULE = '/catalog/pricing-templates';

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
      'catalog/pricing-templates/capabilities',
      'catalog/pricing-templates/directory',
      'catalog/pricing-templates/sites',
      'catalog/pricing-templates/templates',
      `catalog/pricing-templates/templates/${ID}`,
      `catalog/pricing-templates/templates/${ID}/apply`,
      'catalog/pricing-templates/applications',
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/pricing-templates/templates',
    `catalog/pricing-templates/templates/${ID}`,
    `catalog/pricing-templates/templates/${ID}/apply`,
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
    expect.stringContaining(':8082/catalog/pricing-templates'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 本模块没有裸路径资源。
    'catalog/pricing-templates',
    // 5.3.2.2 价格规则未交付
    'catalog/pricing-templates/rules',
    // 本模块不建价目表、不改生效价：价目表是 5.1.1.2 的写入所有权，改价是 5.4.1.1 的。
    `catalog/pricing-templates/templates/${ID}/prices`,
    `catalog/pricing-templates/templates/${ID}/execute`,
    // 应用留痕是只读的（只追加 + 读取），没有写端点。
    'catalog/pricing-templates/applications/export',
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
    // 5.2.1.1 成本构成（本卷相邻模块）
    'catalog/sku-cost-compositions',
    'catalog/sku-cost-compositions/capabilities',
    // 5.2.2.1 汇率查询
    'catalog/exchange-rates',
    // 5.2.3.1 平台费用（**紧邻本条目的上一条**）
    'catalog/platform-fees/rules',
    // 5.3.1.1 利润测算器（**紧邻本条目的下一条**）
    'catalog/profit-calculator/capabilities',
    'catalog/profit-calculator/scenarios',
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

  // `capabilities` / `directory` / `sites` / `applications` / `templates` 的首字母
  // 都可能落在 [0-9a-f] 里 —— 若判别顺序写反，这几条会被当成「id=xxx 的那个东西」拼出去。
  for (const path of [
    'catalog/pricing-templates/capabilities',
    'catalog/pricing-templates/directory',
    'catalog/pricing-templates/sites',
    'catalog/pricing-templates/applications',
    'catalog/pricing-templates/templates',
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
