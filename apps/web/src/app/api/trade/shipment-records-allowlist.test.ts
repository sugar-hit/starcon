import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.4.1.2 发货记录的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在既有组旁边新起一组</b>：本模块新增
 * {@code fulfillment/shipment-records}，<b>没有并进</b> pending-shipment /
 * dropship-tracking / dropship-orders / routing / allocation 任何一组。
 * 这一点不是洁癖：待发货最远到「已交接」且**从不产生已发货**（9.4.1.1 文件头
 * 第四节写明，提前置它会让迟发率算在一个从未发生的时刻上），
 * 把两者合在一支会让「待发货推进一下就成了发货记录」看起来成立。
 * 所以除了「我的形状被放行」，还必须钉住兄弟组仍然只放行它们自己的形状。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/shipment-records-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/shipment-records';
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
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/shipment-records/capabilities',
      'trade/fulfillment/shipment-records/board',
      'trade/fulfillment/shipment-records/performance',
      'trade/fulfillment/shipment-records/events',
      'trade/fulfillment/shipment-records/records',
      `trade/fulfillment/shipment-records/records/${ID}`,
      `trade/fulfillment/shipment-records/records/${ID}/tracking`,
      `trade/fulfillment/shipment-records/records/${ID}/visibility`,
      `trade/fulfillment/shipment-records/records/${ID}/callbacks`,
      `trade/fulfillment/shipment-records/records/${ID}/status`,
      'trade/fulfillment/shipment-records/sync',
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/shipment-records/sync',
    `trade/fulfillment/shipment-records/records/${ID}/tracking`,
    `trade/fulfillment/shipment-records/records/${ID}/visibility`,
    `trade/fulfillment/shipment-records/records/${ID}/callbacks`,
    `trade/fulfillment/shipment-records/records/${ID}/status`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), { params: { path: segments } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/shipment-records'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * 未交付的形状必须被拒：放行不该存在的路径比 404 更糟
 * —— 界面会以为存在一条并不存在的接口。
 */
it('未交付的形状仍然被拒', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 10.3 轨迹、9.4.2.1 / 2.5.1.1 平台回传、8.3.4.1 交接读取都未在本服务交付。
    // 本模块对这三样**只登记**，不抓取、不自动回传，所以没有对应的抓取形状。
    'trade/fulfillment/shipment-records/tracking',
    'trade/fulfillment/shipment-records/callbacks',
    'trade/fulfillment/shipment-records/performance/evaluate',
    'trade/fulfillment/shipment-records/handovers',
    'trade/fulfillment/shipment-records/carriers',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/shipment-records/record',
    'trade/fulfillment/shipment-records/board/filters',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    `trade/fulfillment/shipment-records/records/not-a-uuid`,
    // records/{id} 下只有这四个两段形状。
    `trade/fulfillment/shipment-records/records/${ID}/complete`,
    `trade/fulfillment/shipment-records/records/${ID}/cancel`,
    `trade/fulfillment/shipment-records/records/${ID}/delete`,
    // 凭据行 append-only：没有改 / 删形状
    `trade/fulfillment/shipment-records/records/${ID}/tracking/${ID}`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trade/fulfillment/shipment-records 列表」的映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/shipment-records 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/shipment-records';
  const response = await GET(request(path), { params: { path: path.split('/') } });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**新加的是一组**，不是往既有组里并 —— 所以这里反向钉住兄弟组
 * 仍然只放行它们自己的形状，且不被新组挤掉或污染。
 */
it('兄弟车道的既有分组未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.4.1.1 待发货（本 L3 兄弟，最容易被新组挤掉的一组）
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/pending-shipment/units',
    `trade/fulfillment/pending-shipment/units/${ID}/sla`,
    `trade/fulfillment/pending-shipment/batch-tasks/${ID}/execute`,
    'trade/fulfillment/pending-shipment/sync',
    // 9.6.1.3 代发跟踪 / 9.6.1.2 代发单 / 9.6.1.1 代发配置
    'trade/fulfillment/dropship-tracking/capabilities',
    `trade/fulfillment/dropship-tracking/trackings/${ID}/own-leg`,
    'trade/fulfillment/dropship-orders/capabilities',
    `trade/fulfillment/dropship-orders/orders/${ID}/dispatch`,
    'trade/fulfillment/dropship/capabilities',
    // 9.3.2.2 路由 / 9.3.2.1 分仓
    'trade/fulfillment/routing/capabilities',
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/allocation/capabilities',
    `trade/fulfillment/allocation/allocations/${ID}/status`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 是兄弟车道的形状，不该被本车道挤掉`).toBe(200);
  }
  // 反向：本模块的形状**不能**从兄弟组漏过去（两组并列，不存在前缀包含关系）
  for (const path of [
    'trade/fulfillment/pending-shipment/shipment-records',
    'trade/fulfillment/dropship-tracking/shipment-records',
    'trade/fulfillment/dropship/shipment-records',
    'trade/fulfillment/routing/shipment-records',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 属于本模块命名空间，不该走兄弟车道那一组`).toBe(404);
  }
});

/**
 * 9.4.1.1 待发货刻意**不产生已发货**，而本模块才是「已发货」的登记处。
 * 白名单上这两组必须各自独立：若本组被并进 pending-shipment，
 * 就会出现一条「把待发货推进一下就变成已发货」的路径，而那条路径不存在。
 */
it('本模块不提供把待发货直接推进成已发货的形状', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.4.1.1 的 shipment_event 是它自己的凭据表，不属于本模块
    'trade/fulfillment/pending-shipment/shipped',
    `trade/fulfillment/shipment-records/records/${ID}/ship`,
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行：已发货只能来自 8.3.4.1 已确认交接`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});
