import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET } from './[...path]/route';
import { GET as WAREHOUSE_GET } from '../warehouse/[...path]/route';

/**
 * 1.1.2.3 履约健康的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 所以这里逐条钉住，而且判据是**真跑 proxy 断言 HTTP 状态码**
 * （拦截 = 404，放行 = 转发），不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>本车道在 trade 那一支里**新增一条独立的 `services[]` 元素**
 * （`fulfillment/health` 组），没有并进任何既有组。所以除了「我的形状被放行」，
 * 还必须钉住「兄弟组仍然只放行它们自己的形状」与「不该存在的形状仍然被拒」。
 */

const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/dashboard/fulfillment-health-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/trade/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的 trade 调用点。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/export const BASE = '([^']+)'/)?.[1] ?? 'trade/fulfillment/health';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${BASE}')) continue;
    const cleaned = raw
      .replace(/\$\{BASE\}/g, base)
      .replace(/\?.*$/, '')
      .replace(/\$\{[^}]*\}/g, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/health/capabilities',
      'trade/fulfillment/health/summary',
      'trade/fulfillment/health/red-flags',
    ]),
  );
});

it('三个形状真的转发到 trade 服务 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/health/capabilities',
    'trade/fulfillment/health/summary',
    'trade/fulfillment/health/red-flags',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/health/summary'),
    expect.objectContaining({ method: 'GET' }),
  );
});

it('出库时长那一格走 08 仓储作业自己的出口且被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await WAREHOUSE_GET(
    new NextRequest('http://localhost:3000/api/warehouse/warehouse/outbound-timeliness/report', {
      headers: { origin: 'http://localhost:3000', cookie: 'starcon_session=private-session' },
    }),
    { params: Promise.resolve({ path: ['warehouse', 'outbound-timeliness', 'report'] }) },
  );
  expect(response.status, '出库时效报表未被放行').toBe(200);
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 裸路径：后端没有「一条不存在的汇总列表接口」。
    'trade/fulfillment/health',
    // 本模块刻意没有写端点：卷 01 的红线是「工作台只读 + 跳转」。
    'trade/fulfillment/health/summary/override',
    // 红灯项不按 id 取单条，它们是清单里的一行。
    `trade/fulfillment/health/red-flags/${ID}`,
    // 没实现的形状。
    'trade/fulfillment/health/cards',
    'trade/fulfillment/health/shipments',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('前端模型与页面从不发起任何写请求', () => {
  // ⚠️ proxy 的 pattern **只按路径匹配，不看 HTTP 方法** —— 同一个 path 用 POST 进来
  // 一样会被转发。所以「本模块只读」这条保证落在**后端没有 @PostMapping/@PutMapping/@DeleteMapping**
  // 上（那样请求会得到 405），而不是落在白名单正则上。
  // 这里断言的真正不变量是：前端模型与页面**从不**发起写请求。
  expect(SOURCE).not.toMatch(/'DELETE'/);
  expect(SOURCE).not.toMatch(/'POST'/);
  expect(SOURCE).not.toMatch(/'PUT'/);
  expect(SOURCE).not.toMatch(/api<[^>]*>\([^)]*,\s*'(POST|PUT|DELETE)'/);
});

it('兄弟模块的组没有被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 9.4.1.1 待发货 / 9.4.1.2 发货记录 / 9.5.1.1 时效 / 9.6.1.3 代发跟踪 仍各自放行自己的形状。
  for (const path of [
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/shipment-records/capabilities',
    'trade/fulfillment/timeliness/capabilities',
    'trade/fulfillment/dropship-tracking/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟模块的 ${path} 被本车道挤掉了`).toBe(200);
  }
});
