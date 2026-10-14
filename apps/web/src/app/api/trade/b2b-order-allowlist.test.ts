import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.7.1.1 批发订单与账期的 api-proxy 白名单**双向**断言。
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
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，
 * 绝不收窄既有分支。下面「不误伤其他车道已登记的分支」一组就是为此存在 ——
 * 取错一侧会静默删掉已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/b2b-order-model.ts', import.meta.url)),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'trade/b2b-orders'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/b2b-orders';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${b2bOrderQuery(...)} / ${b2bReceivableQuery(...)} 是查询串，整段去掉；
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
      'trade/b2b-orders/capabilities',
      'trade/b2b-orders',
      `trade/b2b-orders/${ID}`,
      `trade/b2b-orders/${ID}/confirm`,
      `trade/b2b-orders/${ID}/shipments`,
      `trade/b2b-orders/${ID}/invoice-requests`,
      'trade/b2b-orders/receivables',
      'trade/b2b-orders/customer-terms',
      'trade/b2b-orders/price-books',
      'trade/b2b-orders/price-quote',
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/b2b-orders',
    `trade/b2b-orders/${ID}/confirm`,
    `trade/b2b-orders/${ID}/shipments`,
    `trade/b2b-orders/${ID}/invoice-requests`,
    'trade/b2b-orders/price-quote',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/b2b-orders'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/** 上游投影的写入走 PUT（客户档案 upsert / 阶梯价整体替换）。 */
it('投影写入的 PUT 形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['trade/b2b-orders/customer-terms', 'trade/b2b-orders/price-books']) {
    const response = await PUT(request(path, 'PUT'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `PUT ${path} 未被放行`).toBe(200);
  }
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块刻意**没有**取消动作：账期单一旦确认，明细与落档就冻结了。
    `trade/b2b-orders/${ID}/cancel`,
    // 应收核销属于 12 财务的写入所有权，本服务不提供核销端点。
    `trade/b2b-orders/${ID}/receivables/settle`,
    'trade/b2b-orders/receivable-settlement',
    // 没有的子路径列表 / 行级 REST。
    `trade/b2b-orders/${ID}/lines`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/b2b-orders/not-a-uuid',
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/b2b-order',
    'trade/b2b-orderss',
    // 裸前缀：没有对应映射。
    'trade/b2b-orders/',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不占用 `/trade/orders`（已预留给另一条车道）。
 *
 * <p>该路径被 `TradeTenancyIntegrationTest` 断言 2 显式预留，本车道刻意把端点放在
 * `/trade/b2b-orders` 就是为了不占它。这里从代理侧再钉一遍 ——
 * 白名单放行了它，后端就会 404，而那条已通过的后端测试仍然全绿。
 */
it('不占用 /trade/orders（已预留给另一条车道）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const response = await GET(request('trade/orders'), {
    params: Promise.resolve({ path: ['trade', 'orders'] }),
  });
  expect(response.status).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件，trade/ 那一支是一层大 alternation。
 * 并入时若取错一侧，会静默删掉已交付模块的路由 —— 表现是页面 404，
 * 而 typecheck / lint / vitest / Playwright 全绿。
 */
it('不误伤 9.1.2.2 / 9.3.2.2 / 6.4.1.1 已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 9.1.2.2 手工订单 / 9.1.1 订单查询
    'trade/sales-orders',
    'trade/sales-orders/capabilities',
    `trade/sales-orders/${ID}`,
    `trade/sales-orders/${ID}/submit`,
    'trade/sales-orders/list/capabilities',
    'trade/sales-orders/list/filters',
    'trade/sales-orders/detail/capabilities',
    'trade/sales-orders/detail/fulfillment-reference',
    `trade/sales-orders/detail/${ID}`,
    // 9.3.2.2 履约路由 / 9.3.x 履约分配
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/allocation/capabilities',
    `trade/fulfillment/allocation/allocations/${ID}/status`,
    // 6.4.1.1 加工单（含 9.1.3.x BOM 绑定那一支）
    'trade/processing/capabilities',
    `trade/processing/orders/${ID}/status`,
    `trade/processing/orders/${ID}/bom-binding/lines`,
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
  const path = `trade/b2b-orders/${ID}/shipments`;
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
