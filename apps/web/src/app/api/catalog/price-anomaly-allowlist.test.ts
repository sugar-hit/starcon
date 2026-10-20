import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.5.2.1 异常告警的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件最要紧的是两条：
 *
 * <ol>
 *   <li><b>负向样本必须用自己前缀下的形状</b>。共享白名单是**累积并集**，
 *       兄弟车道本批可能同时交付 —— 拿兄弟车道的前缀当「未交付形状」要求 404，
 *       并入后必然假红（503 是转发到未启动的后端，404 才是白名单拒绝）。
 *   <li><b>兄弟车道的白名单条目未被本车道挤掉</b>。取错一侧会**静默删掉**别人已交付的路由：
 *       表现是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 * </ol>
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
const MODULE = '/catalog/price-anomaly-alerts';

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
      'catalog/price-anomaly-alerts/capabilities',
      'catalog/price-anomaly-alerts/observations',
      'catalog/price-anomaly-alerts/events',
      `catalog/price-anomaly-alerts/events/${ID}`,
      `catalog/price-anomaly-alerts/events/${ID}/acknowledge`,
      `catalog/price-anomaly-alerts/events/${ID}/resolve`,
      'catalog/price-anomaly-alerts/rules',
      'catalog/price-anomaly-alerts/notifications',
      'catalog/price-anomaly-alerts/detect',
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/price-anomaly-alerts/observations',
    'catalog/price-anomaly-alerts/rules',
    'catalog/price-anomaly-alerts/detect',
    `catalog/price-anomaly-alerts/events/${ID}/acknowledge`,
    `catalog/price-anomaly-alerts/events/${ID}/resolve`,
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
    expect.stringContaining(':8082/catalog/price-anomaly-alerts'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('🔴 未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 **每一条负向样本都在本模块自己的前缀下** ——
  //   兄弟车道的前缀本批可能同时交付，拿它们当「未交付形状」并入后必然假红。
  for (const path of [
    // 裸路径：后端没有「GET /catalog/price-anomaly-alerts」这个端点
    // （集合读是 /events 与 /observations）。
    'catalog/price-anomaly-alerts',
    // 5.5.1.1 价格历史未交付：它与本模块同属 L3「5.5 价格监控」，
    // 但端点命名空间不同 —— 本模块**刻意不放行**任何价格历史形状。
    'catalog/price-anomaly-alerts/price-history',
    'catalog/price-anomaly-alerts/history',
    // 不存在的事件动作：只有 acknowledge 与 resolve 两个。
    `catalog/price-anomaly-alerts/events/${ID}/escalate`,
    `catalog/price-anomaly-alerts/events/${ID}/ignore`,
    // 不存在的资源：观测是本模块的输入事实，不提供「按 id 取单条观测」。
    `catalog/price-anomaly-alerts/observations/${ID}`,
    // 不存在的批量形状：本模块**没有**批量登记观测这个端点。
    'catalog/price-anomaly-alerts/observations/batch',
    // 不存在的能力声明分段。
    'catalog/price-anomaly-alerts/capabilities/cost',
    // 不存在的规则分段：规则是一种异常类型一条。
    `catalog/price-anomaly-alerts/rules/${ID}`,
    // 不存在的事件转储：事件导出未实现。
    `catalog/price-anomaly-alerts/events/${ID}/export`,
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
    // 5.2.1.1 成本构成（与本模块同属 05 定价，**必须**仍在白名单里）
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

  // `capabilities` / `observations` / `events` / `rules` / `notifications` / `detect`
  // 的首字母都不落在 [0-9a-f] 里 —— 但本模块的 uuid 分支只出现在 `events/` 下面，
  // 所以这条断言是为了保证**判别顺序**没写反。
  for (const path of [
    'catalog/price-anomaly-alerts/capabilities',
    'catalog/price-anomaly-alerts/observations',
    'catalog/price-anomaly-alerts/events',
    'catalog/price-anomaly-alerts/rules',
    'catalog/price-anomaly-alerts/notifications',
    'catalog/price-anomaly-alerts/detect',
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
