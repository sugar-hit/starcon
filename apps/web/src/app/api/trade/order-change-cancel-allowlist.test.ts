import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.1.1.3 订单变更与取消的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是往既有 trade 段的 alternation 里追加一支</b>（`sales-orders/change/…`），
 * 不重排、不格式化已有分支 —— 所以除了「我的形状被放行」，还必须钉住
 * <b>9.1.1.1 / 9.1.1.2 / 9.1.2.2 / 9.3.2.1 / 9.3.2.2 / 6.4.1.1 已交付的形状一个都没被挤掉</b>。
 * 取错一侧的后果是静默删掉已交付路由：表现为页面 404，而 typecheck / lint / vitest 全绿。
 */

const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/orders/order-change-cancel-model.ts', import.meta.url),
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
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/sales-orders/change';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一支」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/sales-orders/change/capabilities',
      'trade/sales-orders/change/cancellations',
      `trade/sales-orders/change/cancellations/${ID}`,
      'trade/sales-orders/change/requests',
      'trade/sales-orders/change/platform-rules',
      'trade/sales-orders/change/fulfillment-progress',
    ]),
  );
});

it('建单与推进状态的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/sales-orders/change/requests',
    'trade/sales-orders/change/cancellations',
    'trade/sales-orders/change/platform-rules',
    'trade/sales-orders/change/fulfillment-progress',
    `trade/sales-orders/change/requests/${ID}/status`,
    `trade/sales-orders/change/cancellations/${ID}/status`,
    `trade/sales-orders/change/cancellations/${ID}/warehouse-tasks/${ID}/status`,
    `trade/sales-orders/change/writeback-tasks/${ID}/status`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sales-orders/change/cancellations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 11 售后与 9.3.3.1 未交付：本模块不提供它们的入口。
    `trade/sales-orders/change/aftersales`,
    `trade/sales-orders/change/fulfillment-requests`,
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/sales-orders/change/change',
    'trade/sales-orders/change/cancellation',
    'trade/sales-orders/change/platform-rule',
    // 单段裸 id 之下多接一段未交付的子资源。
    `trade/sales-orders/change/cancellations/${ID}/events`,
    `trade/sales-orders/change/cancellations/${ID}/lines`,
    `trade/sales-orders/change/requests/${ID}/components`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    `trade/sales-orders/change/cancellations/not-a-uuid`,
    `trade/sales-orders/change/requests/not-a-uuid`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trade/sales-orders/change 列表」的映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 sales-orders/change 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/sales-orders/change';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**往既有 trade alternation 里追加了一支**，不是新开一段也不是重排 ——
 * 所以这里逐条反验已交付车道的形状一个都没被挤掉或污染。
 */
it('兄弟车道已交付的形状未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.1.1.1 订单列表
    'trade/sales-orders/list/capabilities',
    'trade/sales-orders/list/filters',
    'trade/sales-orders/list',
    // 9.1.1.2 订单详情
    'trade/sales-orders/detail/capabilities',
    `trade/sales-orders/detail/${ID}`,
    'trade/sales-orders/detail/fulfillment-reference',
    // 9.1.2.2 手工订单
    `trade/sales-orders/${ID}`,
    `trade/sales-orders/${ID}/submit`,
    'trade/sales-orders',
    // 9.3.2.2 / 9.3.2.1
    'trade/fulfillment/routing/capabilities',
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/allocation/allocations',
    `trade/fulfillment/allocation/allocations/${ID}/status`,
    // 6.4.1.1 加工单
    'trade/processing/orders',
    `trade/processing/orders/${ID}/status`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `已交付车道的 ${path} 被本车道挤掉了`).toBe(200);
  }
  // change 的形状不得从别组漏过去（它是 sales-orders 下独立的一支）。
  for (const path of [
    `trade/sales-orders/list/change`,
    `trade/sales-orders/detail/change/${ID}`,
    'trade/fulfillment/routing/change',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不属于 change 组`).toBe(404);
  }
});

it('trade 出口真的转发到 8085（不是白名单放行后自己 404/503）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/sales-orders/change/cancellations';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sales-orders/change/cancellations'),
    expect.anything(),
  );
});
