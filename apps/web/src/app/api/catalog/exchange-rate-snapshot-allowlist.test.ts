import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.2.2.2 汇率快照的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件最要紧的是最后一条：**共享白名单是累积并集**。本车道只新增一条
 * `catalog/` 元素（追加在 5.2.2.1 那条之后），兄弟车道的条目一字未动 —— 但那必须被断言钉住，
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
const MODULE = '/catalog/exchange-rate-snapshots';

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // `{code}` 是**规则代码**（如 JPY），不是 uuid —— 替换成 uuid 会让这条路径
    // 落进「规则代码字符集」之外，测出来的 404 是替身造出来的，不是真问题。
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{code\}/g, 'JPY')
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
      'catalog/exchange-rate-snapshots',
      'catalog/exchange-rate-snapshots/capabilities',
      'catalog/exchange-rate-snapshots/replay',
      'catalog/exchange-rate-snapshots/rounding-rules',
      'catalog/exchange-rate-snapshots/rounding-rules/initialize',
      'catalog/exchange-rate-snapshots/rounding-rules/JPY',
      `catalog/exchange-rate-snapshots/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/exchange-rate-snapshots',
    'catalog/exchange-rate-snapshots/replay',
    'catalog/exchange-rate-snapshots/rounding-rules',
    'catalog/exchange-rate-snapshots/rounding-rules/initialize',
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
    expect.stringContaining(':8082/catalog/exchange-rate-snapshots'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('不存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 快照是只追加的冻结事实：没有「导出」这个动作
    'catalog/exchange-rate-snapshots/export',
    // 没有「改写快照」这个动作（快照不可改写）
    `catalog/exchange-rate-snapshots/${ID}/update`,
    // 没有「删除快照」这个动作
    `catalog/exchange-rate-snapshots/${ID}/delete`,
    // 舍入规则下面没有子资源
    'catalog/exchange-rate-snapshots/rounding-rules/JPY/history',
    // 初始化之后再没有更深的动作
    'catalog/exchange-rate-snapshots/rounding-rules/initialize/again',
    // 5.2.2.1 的端点不在本模块的命名空间下
    'catalog/exchange-rate-snapshots/sources',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('🔴 兄弟车道与相邻模块的 catalog 白名单条目未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const siblings = [
    // 5.2.2.1 汇率查询（同 L3 兄弟，首 token 只差一个 -snapshots 后缀）
    'catalog/exchange-rates',
    'catalog/exchange-rates/capabilities',
    'catalog/exchange-rates/latest',
    'catalog/exchange-rates/sources',
    // 5.2.1.3 成本分摊
    'catalog/cost-allocations',
    'catalog/cost-allocations/capabilities',
    // 5.2.3.1 平台费用（集合根路径不存在，取它真实存在的两条）
    'catalog/platform-fees/capabilities',
    'catalog/platform-fees/rules',
    // 5.3.1.1 利润测算器
    'catalog/profit-calculator/capabilities',
    // 3.1.1.1 商品主数据
    'catalog/products',
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

  // `capabilities` / `replay` / `rounding-rules` 的首字母都落在 [0-9a-f] 里 ——
  // 若判别顺序写反，这三条会被当成「id=capabilities 的那个东西」拼出去。
  for (const path of [
    'catalog/exchange-rate-snapshots/capabilities',
    'catalog/exchange-rate-snapshots/replay',
    'catalog/exchange-rate-snapshots/rounding-rules',
    'catalog/exchange-rate-snapshots/rounding-rules/initialize',
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

it('放行本模块绝不代表放行兄弟模块的路径（首 token 不同就是不同）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `exchange-rates`（5.2.2.1）与 `exchange-rate-snapshots`（本模块）是两个 token：
  // 放行本模块不代表放行 5.2.2.1 的每一个形状，反之亦然。
  for (const path of [
    'catalog/exchange-rate-snapshots/sources',
    'catalog/exchange-rate-snapshots/fetch-log',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
