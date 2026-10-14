import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.6.1.3 代发履约跟踪与异常的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在 9.6.1.1 / 9.6.1.2 两组的旁边新加第三组</b>：
 * 本模块新增 {@code fulfillment/dropship-tracking}，<b>没有并进</b>
 * {@code fulfillment/dropship} 或 {@code fulfillment/dropship-orders} ——
 * 「改配置」「下一单」「切一次履约」混在一个命名空间里，日志与权限都分不开。
 * 9.6.1.2 自己的白名单测试还明确钉住了 {@code dropship-orders/tracking}
 * 必须被拒，本组的存在正好解释了那条断言为什么成立。
 */

const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/orders/dropship-tracking-model.ts', import.meta.url),
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
  const base =
    SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/dropship-tracking';
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
      'trade/fulfillment/dropship-tracking/capabilities',
      'trade/fulfillment/dropship-tracking/policy',
      'trade/fulfillment/dropship-tracking/trackings',
      `trade/fulfillment/dropship-tracking/trackings/${ID}`,
      `trade/fulfillment/dropship-tracking/trackings/${ID}/supplier-leg`,
      `trade/fulfillment/dropship-tracking/trackings/${ID}/own-leg`,
      'trade/fulfillment/dropship-tracking/exceptions',
      'trade/fulfillment/dropship-tracking/exceptions/evaluate',
      `trade/fulfillment/dropship-tracking/exceptions/${ID}/dispose`,
      `trade/fulfillment/dropship-tracking/exceptions/${ID}/dismiss`,
      `trade/fulfillment/dropship-tracking/dispositions/${ID}/status`,
      'trade/fulfillment/dropship-tracking/tasks',
      `trade/fulfillment/dropship-tracking/tasks/${ID}/claim`,
      `trade/fulfillment/dropship-tracking/tasks/${ID}/complete`,
      'trade/fulfillment/dropship-tracking/events',
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/dropship-tracking/policy',
    'trade/fulfillment/dropship-tracking/trackings',
    `trade/fulfillment/dropship-tracking/trackings/${ID}/supplier-leg`,
    `trade/fulfillment/dropship-tracking/trackings/${ID}/own-leg`,
    'trade/fulfillment/dropship-tracking/exceptions/evaluate',
    `trade/fulfillment/dropship-tracking/exceptions/${ID}/dispose`,
    `trade/fulfillment/dropship-tracking/exceptions/${ID}/dismiss`,
    `trade/fulfillment/dropship-tracking/dispositions/${ID}/status`,
    `trade/fulfillment/dropship-tracking/tasks/${ID}/claim`,
    `trade/fulfillment/dropship-tracking/tasks/${ID}/complete`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), { params: { path: segments } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/dropship-tracking/trackings'),
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
    // 同组未交付：9.6.2.1 差价对账、10 物流、12 财务、11 售后都没有端点。
    'trade/fulfillment/dropship-tracking/reconciliations',
    'trade/fulfillment/dropship-tracking/settlements',
    'trade/fulfillment/dropship-tracking/refunds',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/dropship-tracking/tracking',
    'trade/fulfillment/dropship-tracking/exception',
    'trade/fulfillment/dropship-tracking/task',
    'trade/fulfillment/dropship-tracking/disposition',
    // exceptions 只有集合读 + 两个两段动作，没有单条详情。
    `trade/fulfillment/dropship-tracking/exceptions/${ID}`,
    // trackings 的两段形状只有 supplier-leg / own-leg。
    `trade/fulfillment/dropship-tracking/trackings/${ID}/deliver`,
    `trade/fulfillment/dropship-tracking/trackings/${ID}/cancel`,
    // tasks 只有 claim / complete，没有别的写形状。
    `trade/fulfillment/dropship-tracking/tasks/${ID}/retry`,
    // policy 是单例，没有单条详情。
    `trade/fulfillment/dropship-tracking/policy/${ID}`,
    // events 只有集合读（append-only）。
    `trade/fulfillment/dropship-tracking/events/${ID}`,
    // dispositions 只有两段 status 形状。
    `trade/fulfillment/dropship-tracking/dispositions/${ID}`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/fulfillment/dropship-tracking/trackings/not-a-uuid',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trackings 之外」的裸资源映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/dropship-tracking 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/dropship-tracking';
  const response = await GET(request(path), { params: { path: path.split('/') } });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**新加的是一组**，不是往 9.6.1.1 / 9.6.1.2 的既有组里并 ——
 * 所以这里反向钉住那两组仍然只放行它们自己的形状，且不被新组挤掉或污染。
 *
 * <p>尤其要钉住 9.6.1.2 自己那条断言：{@code dropship-orders/tracking}
 * 必须仍然被拒 —— 它不是本模块的路径（本模块在 dropship-tracking 组下）。
 */
it('兄弟车道的 dropship / dropship-orders / routing 组未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.6.1.1 代发模式配置
    'trade/fulfillment/dropship/capabilities',
    'trade/fulfillment/dropship/configs',
    `trade/fulfillment/dropship/configs/${ID}`,
    // 9.6.1.2 代发单与供应商下单
    'trade/fulfillment/dropship-orders/capabilities',
    'trade/fulfillment/dropship-orders/orders',
    `trade/fulfillment/dropship-orders/orders/${ID}/shipment`,
    'trade/fulfillment/dropship-orders/alerts',
    // 9.3.2.2 路由 / 9.3.2.1 分仓
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/allocation/capabilities',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 是兄弟车道的形状，不该被本车道挤掉`).toBe(200);
  }
  // 反向：兄弟两组的正则**不得**吞掉本模块的路径（两组并列，不存在前缀包含关系）
  for (const path of [
    // 9.6.1.2 自己的白名单测试钉的就是这一条：跟踪属于本模块，不属于代发单组
    'trade/fulfillment/dropship-orders/tracking',
    'trade/fulfillment/dropship/dropship-tracking',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不属于兄弟车道的命名空间`).toBe(404);
  }
});