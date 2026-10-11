import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.1.2.2 手工订单的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，
 * 绝不收窄既有分支。下面「不误伤其他车道已登记的分支」一组就是为此存在 ——
 * 取错一侧会静默删掉已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/manual-order-model.ts', import.meta.url)),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'trade/sales-orders'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/sales-orders';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${salesOrderQuery(...)} 是查询串，整段去掉；其余插值（通常是 id）换成 uuid。
      .replace(/\$\{salesOrderQuery\([^)]*\)\}/g, '')
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
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/sales-orders/capabilities',
      'trade/sales-orders',
      `trade/sales-orders/${ID}`,
      `trade/sales-orders/${ID}/submit`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['trade/sales-orders', `trade/sales-orders/${ID}/submit`]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sales-orders'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/** 草稿更新走 PUT（整体替换客户 / 行 / 地址 / 费用）。 */
it('草稿更新的 PUT 形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const path = `trade/sales-orders/${ID}`;
  const response = await PUT(request(path, 'PUT'), { params: { path: path.split('/') } });
  expect(response.status, `PUT ${path} 未被放行`).toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 同组未开发的兄弟模块：9.1.2.1 平台订单同步（D5）、9.2 审单。
    'trade/sales-orders/review-queue',
    'trade/sales-orders/import',
    // 9.1.2.2 刻意**没有**取消动作：取消属于 9.2 的驳回，草稿的处置是「不提交」。
    `trade/sales-orders/${ID}/cancel`,
    `trade/sales-orders/${ID}/approve`,
    // 裸路径：后端没有「一条不存在的子路径列表」。
    'trade/sales-orders/11111111-1111-1111-1111-111111111111/lines',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/sales-orders/not-a-uuid',
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/sales-order',
    'trade/sales-orderss',
    // 裸前缀：没有对应映射。
    'trade/sales-orders/',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * **`/trade/orders` 必须仍然 404**。
 *
 * <p>该路径被 `TradeTenancyIntegrationTest` 断言 2 显式预留给另一条车道，本车道刻意
 * 把端点放在 `/trade/sales-orders` 就是为了不占它。这里从代理侧再钉一遍 ——
 * 白名单放行了它，后端就会 404，而那条已通过的后端测试仍然全绿。
 */
it('不占用 /trade/orders（已预留给另一条车道）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const response = await GET(request('trade/orders'), { params: { path: ['trade', 'orders'] } });
  expect(response.status).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件，trade/ 那一支是两层 alternation
 * （外层 prefix 'trade/' + 内层含嵌套组的大正则）。并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest /
 * Playwright 全绿。所以必须正向钉住 9.3.2.2 与 6.4.1.1 已放行的全部形状。
 */
it('不误伤 9.3.2.2 与 6.4.1.1 已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 9.3.2.2 履约路由
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/mcf-authorizations',
    'trade/fulfillment/routing/cost-profiles',
    'trade/fulfillment/routing/stock-snapshots',
    'trade/fulfillment/routing/evaluations',
    'trade/fulfillment/routing/decisions',
    `trade/fulfillment/routing/decisions/${ID}`,
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/routing/switches',
    // 6.4.1.1 加工单（含 9.1.3.x BOM 绑定那一支）
    'trade/processing/capabilities',
    'trade/processing/orders',
    `trade/processing/orders/${ID}`,
    `trade/processing/orders/${ID}/status`,
    `trade/processing/orders/${ID}/progress`,
    `trade/processing/orders/${ID}/components/issue`,
    `trade/processing/orders/${ID}/bom-binding`,
    `trade/processing/orders/${ID}/bom-binding/lines`,
    'trade/processing/factories',
    `trade/processing/factories/${ID}`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `trade/sales-orders/${ID}/submit`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/trade/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: { path: path.split('/') } },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
