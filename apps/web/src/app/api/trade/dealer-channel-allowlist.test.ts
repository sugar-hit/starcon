import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.7.1.2 经销与分销管理的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：漏登记的路径在浏览器里静默 404，而 typecheck / lint /
 * vitest / Playwright 全绿，页面只进错误态，看起来像「后端没实现」。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**一个
 * `dealers` 分支，<b>不并进</b> b2b-orders / fulfillment / pending-shipment /
 * shipment-records / dropship-margin 任一组，也绝不收窄既有分支。下面
 * 「不误伤其他车道已登记的分支」一组就是为此存在 —— 取错一侧会静默删掉
 * 已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/dealer-channel-model.ts', import.meta.url)),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'trade/dealers'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/dealers';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${dealerAuthorizationQuery(...)} 之类是查询串，整段去掉；
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[a-zA-Z0-9]*Query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 裸 base 本身要排除：它是前缀常量而不是端点，后端没有裸路径这个映射
  // （下面有一条用例专门断言它被拒）。
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/dealers/capabilities',
      'trade/dealers/candidates',
      'trade/dealers/profiles',
      'trade/dealers/authorizations',
      `trade/dealers/authorizations/${ID}`,
      `trade/dealers/authorizations/${ID}/status`,
      'trade/dealers/quotas',
      'trade/dealers/quota-attainment',
      'trade/dealers/sales',
      'trade/dealers/rebates',
      'trade/dealers/rebates/accrue',
      'trade/dealers/diversion-alerts',
      `trade/dealers/diversion-alerts/${ID}/state`,
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/dealers/authorizations',
    'trade/dealers/quotas',
    'trade/dealers/sales',
    'trade/dealers/rebates/accrue',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/dealers'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/** 档案投影 upsert 与授权状态流转、预警处置走 PUT。 */
it('投影写入与状态流转的 PUT 形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/dealers/profiles',
    `trade/dealers/authorizations/${ID}/status`,
    `trade/dealers/diversion-alerts/${ID}/state`,
  ]) {
    const response = await PUT(request(path, 'PUT'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `PUT ${path} 未被放行`).toBe(200);
  }
});

it('未交付与不存在的形状仍然 404 —— 守卫不能是空转的', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 裸路径：后端没有 `GET /trade/dealers` 这个映射。
    'trade/dealers',
    // 事件流只在授权详情里给，没有独立的查询端点。
    'trade/dealers/events',
    // 配额没有单独的列表端点（走 quota-attainment）。
    'trade/dealers/quotas/evaluate',
    // 12 应付/返点未交付：核销属于 12 财务的写入所有权。
    'trade/dealers/rebates/settle',
    // 5.5.2 未交付：没有价格倒挂维度的重算入口。
    'trade/dealers/diversion-alerts/evaluate-price',
    // 不属于本模块的任何子路径。
    'trade/dealers/unknown-subresource',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被拦截 —— 放行不存在的端点会让界面以为已交付`).toBe(404);
  }
  // 拦截时不能有转发。
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的 trade 分支 —— 白名单必须求并', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 9.7.1.1 批发订单与账期（同一 L3 下的兄弟，本车道刻意**不并进**它那一组）
    'trade/b2b-orders',
    'trade/b2b-orders/capabilities',
    'trade/b2b-orders/receivables',
    'trade/b2b-orders/customer-terms',
    'trade/b2b-orders/price-quote',
    `trade/b2b-orders/${ID}`,
    `trade/b2b-orders/${ID}/confirm`,
    `trade/b2b-orders/${ID}/shipments`,
    `trade/b2b-orders/${ID}/invoice-requests`,
    // 9.1.1 / 9.1.2.2 订单
    'trade/sales-orders',
    `trade/sales-orders/${ID}`,
    // 9.3.2.2 履约路由 / 9.3 履约分配
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    // 9.6.1.3 代发跟踪 / 9.6.1.x 代发配置与代发单
    'trade/fulfillment/dropship-tracking/capabilities',
    'trade/fulfillment/dropship-orders/capabilities',
    'trade/fulfillment/dropship-margin/capabilities',
    // 10.2.1.1 待发货 / 10.1.1.1 运单 / 发货记录
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/shipment-records/capabilities',
    'trade/logistics/waybill-applications/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/dealers/sales';
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/trade/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
