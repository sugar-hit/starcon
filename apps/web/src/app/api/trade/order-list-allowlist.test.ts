import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.1.1.1 订单列表的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「批量操作已经可用」，而本模块是<b>只读</b>的。</li>
 * </ul>
 *
 * <p>「拒绝」这一组对本模块尤其重要：本模块刻意**不提供任何写端点**，
 * 四条批量操作全部 available=false。如果白名单把 `sales-orders/list/bulk`
 * 之类放行了，界面就会以为点得动。
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，
 * 绝不收窄既有分支。上面「不误伤其他车道已登记的分支」一组就是为此存在。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-list-model.ts', import.meta.url)),
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
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'trade/sales-orders/list'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/sales-orders/list';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{salesOrderListQuery\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base) || raw === base) continue;
    found.add(raw);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/sales-orders/list',
      'trade/sales-orders/list/capabilities',
      'trade/sales-orders/list/filters',
    ]),
  );
});

it('放行时真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await GET(request('trade/sales-orders/list'), {
    params: { path: 'trade/sales-orders/list'.split('/') },
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sales-orders/list'),
    expect.objectContaining({ method: 'GET' }),
  );
});

it('未交付 / 本模块刻意不提供的形状仍然被拒', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块是**只读**的读投影：四条批量操作全部 available=false，
    // 没有任何写端点。放行这些形状会让界面以为点得动。
    'trade/sales-orders/list/bulk',
    'trade/sales-orders/list/bulk/review',
    'trade/sales-orders/list/bulk/note',
    'trade/sales-orders/list/bulk/cancel',
    'trade/sales-orders/list/bulk/dunning',
    'trade/sales-orders/list/export',
    // 9.1.1.2 详情 / 9.1.1.3 取消是并列的兄弟 L4，都未交付。
    `trade/sales-orders/list/${ID}`,
    `trade/sales-orders/list/${ID}/cancel`,
    // 非 UUID / 拼错的段不应被任何分支吃掉。
    'trade/sales-orders/listx',
    'trade/sales-orders/list/extra',
    // 交错写错会互相吃掉，必须反向钉住。
    'trade/sales-order/list',
    'trade/sales-orderss/list',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不该被放行（放行比 404 更糟：界面会以为它可用）`).toBe(404);
  }
});

/**
 * 本模块**没有任何写端点**（四条批量操作全部 available=false）。
 *
 * <p>⚠️ 白名单**按路径登记、不区分方法**（与 9.3.2.2 / 6.4.1.1 / 9.1.2.2 同一约定，
 * 见 fulfillment-routing-allowlist.test.ts 的说明）：同一个路径的 POST 只要 GET 放行了
 * 就必然放行。所以这里**不能**断言「POST 列表路径返回 404」—— 那是把 proxy 的
 * 设计约定当成缺陷来断言，写出来必红且没有意义。
 *
 * <p>真正该守的是：**白名单里不存在任何批量操作的路径**，所以界面上无论怎么点
 * 都不可能命中一个「看起来能用」的写形状。写端点的缺失由后端
 * {@code SalesOrderListIntegrationTest.本模块不提供任何写端点()} 守
 * （后端 POST 到列表路径 4xx、到 /bulk 直接 404）。
 */
it('白名单里不存在任何批量操作的写形状', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'trade/sales-orders/list/bulk',
    'trade/sales-orders/list/bulk/review',
    'trade/sales-orders/list/bulk/note',
    'trade/sales-orders/list/bulk/cancel',
    'trade/sales-orders/list/bulk/dunning',
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 不该被放行：写形状不存在`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的 trade 分支（取并不得取错一侧）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 9.3.2.2 履约路由
  for (const path of [
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/routing/switches',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `9.3.2.2 的 ${path} 被本车道误伤`).toBe(200);
  }
  // 6.4.1.1 加工单
  for (const path of [
    'trade/processing/capabilities',
    'trade/processing/orders',
    `trade/processing/orders/${ID}/status`,
    `trade/processing/orders/${ID}/components/issue`,
    'trade/processing/factories',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `6.4.1.1 的 ${path} 被本车道误伤`).toBe(200);
  }
  // 9.1.2.2 手工订单（本车道与之并列，不是覆盖）
  for (const path of [
    'trade/sales-orders',
    'trade/sales-orders/capabilities',
    `trade/sales-orders/${ID}`,
    `trade/sales-orders/${ID}/submit`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `9.1.2.2 的 ${path} 被本车道误伤`).toBe(200);
  }
});

it('/trade/orders 仍然 404 —— 该路径被 TradeTenancyIntegrationTest 预留给另一条车道', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const method of ['GET', 'POST'] as const) {
    const response =
      method === 'GET'
        ? await GET(request('trade/orders'), { params: { path: ['trade', 'orders'] } })
        : await POST(request('trade/orders', 'POST'), {
            params: { path: ['trade', 'orders'] },
          });
    expect(response.status).toBe(404);
  }
});
