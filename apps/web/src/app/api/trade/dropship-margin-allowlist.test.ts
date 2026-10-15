import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.6.2.1 代发差价与对账的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在前三组的旁边新加第四组</b>：本模块新增
 * {@code fulfillment/dropship-margin}，<b>没有并进</b>
 * {@code fulfillment/dropship}（配置）、{@code fulfillment/dropship-orders}（怎么下的）
 * 或 {@code fulfillment/dropship-tracking}（到哪了）——
 * 「改配置」「下一单」「切一次履约」「做一次对账」混在一个命名空间里，日志与权限都分不开。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/dropship-margin-model.ts', import.meta.url)),
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

/** 从模型源码里反向抽出**真实存在**的路径（写死一份清单只会验证「清单与白名单一致」）。 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/dropship-margin';
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
      'trade/fulfillment/dropship-margin/capabilities',
      'trade/fulfillment/dropship-margin/fee-rates',
      'trade/fulfillment/dropship-margin/fx-rates',
      'trade/fulfillment/dropship-margin/cycles',
      'trade/fulfillment/dropship-margin/facts',
      'trade/fulfillment/dropship-margin/facts/evaluate',
      'trade/fulfillment/dropship-margin/report',
      'trade/fulfillment/dropship-margin/alerts',
      'trade/fulfillment/dropship-margin/alerts/evaluate',
      `trade/fulfillment/dropship-margin/alerts/${ID}/acknowledge`,
      `trade/fulfillment/dropship-margin/alerts/${ID}/dismiss`,
      'trade/fulfillment/dropship-margin/reconciliations',
      `trade/fulfillment/dropship-margin/reconciliations/${ID}`,
      `trade/fulfillment/dropship-margin/reconciliations/${ID}/status`,
      'trade/fulfillment/dropship-margin/tasks',
      `trade/fulfillment/dropship-margin/tasks/${ID}/claim`,
      `trade/fulfillment/dropship-margin/tasks/${ID}/complete`,
      'trade/fulfillment/dropship-margin/events',
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/dropship-margin/fee-rates',
    'trade/fulfillment/dropship-margin/fx-rates',
    'trade/fulfillment/dropship-margin/cycles',
    'trade/fulfillment/dropship-margin/facts/evaluate',
    'trade/fulfillment/dropship-margin/alerts/evaluate',
    `trade/fulfillment/dropship-margin/alerts/${ID}/acknowledge`,
    `trade/fulfillment/dropship-margin/alerts/${ID}/dismiss`,
    'trade/fulfillment/dropship-margin/reconciliations',
    `trade/fulfillment/dropship-margin/reconciliations/${ID}/status`,
    `trade/fulfillment/dropship-margin/tasks/${ID}/claim`,
    `trade/fulfillment/dropship-margin/tasks/${ID}/complete`,
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/dropship-margin/reconciliations'),
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
    // 拼错的同组路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/dropship-margin/fact',
    'trade/fulfillment/dropship-margin/alert',
    'trade/fulfillment/dropship-margin/fee-rate',
    'trade/fulfillment/dropship-margin/fx-rate',
    'trade/fulfillment/dropship-margin/reconciliation',
    'trade/fulfillment/dropship-margin/task',
    'trade/fulfillment/dropship-margin/cycle',
    // 本模块只到「注册」费率 / 汇率，不提供「费率来源规则库」（那属 5.2.3.1 未交付）
    'trade/fulfillment/dropship-margin/fee-rate-rules',
    // 没有利润报表端点：13.4.1.1 / 13.4.1.2 未交付，本模块不冒充下游
    'trade/fulfillment/dropship-margin/profit-reports',
    'trade/fulfillment/dropship-margin/profit-report',
    // 应付单本身不属本服务：12.1.2.1 在 finance，只落待办
    'trade/fulfillment/dropship-margin/payables',
    // alerts 只有集合读 + evaluate + 两个两段动作，没有单条详情
    `trade/fulfillment/dropship-margin/alerts/${ID}`,
    `trade/fulfillment/dropship-margin/alerts/${ID}/resolve`,
    // tasks 只有 claim / complete
    `trade/fulfillment/dropship-margin/tasks/${ID}/retry`,
    // facts 没有 {uuid} 详情，也没有别的写形状
    `trade/fulfillment/dropship-margin/facts/${ID}`,
    `trade/fulfillment/dropship-margin/facts/${ID}/recompute`,
    // report 只读
    `trade/fulfillment/dropship-margin/report/${ID}`,
    // fee-rates / fx-rates / cycles 只有集合读 + POST，没有单条详情
    `trade/fulfillment/dropship-margin/fee-rates/${ID}`,
    `trade/fulfillment/dropship-margin/fx-rates/${ID}`,
    `trade/fulfillment/dropship-margin/cycles/${ID}`,
    // reconciliations 的写形状只有 {uuid}/status，没有 {uuid}/pay 之类
    `trade/fulfillment/dropship-margin/reconciliations/${ID}/pay`,
    `trade/fulfillment/dropship-margin/reconciliations/${ID}/lines`,
    // events 只有集合读（append-only）
    `trade/fulfillment/dropship-margin/events/${ID}`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉
    'trade/fulfillment/dropship-margin/reconciliations/not-a-uuid',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /report 之类之外」的裸资源映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/dropship-margin 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/dropship-margin';
  const response = await GET(request(path), { params: { path: path.split('/') } });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**新加的是一组**，不是往 9.6.1.1 / 9.6.1.2 / 9.6.1.3 的既有组里并 ——
 * 所以这里反向钉住那三组仍然只放行它们自己的形状，且不被新组挤掉或污染。
 *
 * <p>尤其要钉住 9.6.1.3 自己那条断言：{@code dropship-tracking/reconciliations}
 * 必须仍然被拒 —— 对账属本模块的 {@code dropship-margin} 组，不属跟踪组。
 */
it('兄弟车道的 dropship / dropship-orders / dropship-tracking 组未被本车道挤掉或污染', async () => {
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
    // 9.6.1.3 代发履约跟踪与异常
    'trade/fulfillment/dropship-tracking/capabilities',
    'trade/fulfillment/dropship-tracking/trackings',
    `trade/fulfillment/dropship-tracking/trackings/${ID}/supplier-leg`,
    'trade/fulfillment/dropship-tracking/exceptions',
    'trade/fulfillment/dropship-tracking/tasks',
    // 9.3.2.2 路由 / 9.3.2.1 分仓 / 9.4.1.1 待发货
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/allocation/capabilities',
    'trade/fulfillment/pending-shipment/capabilities',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 是兄弟车道的形状，不该被本车道挤掉`).toBe(200);
  }

  // 反向：兄弟三组的正则**不得**吞掉本模块的路径（两组并列，不存在前缀包含关系）
  for (const path of [
    // 9.6.1.3 的白名单测试钉的就是这两条：对账不属跟踪命名空间
    'trade/fulfillment/dropship-tracking/reconciliations',
    'trade/fulfillment/dropship-tracking/report',
    'trade/fulfillment/dropship/dropship-margin',
    'trade/fulfillment/dropship-orders/report',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不属于兄弟车道的命名空间`).toBe(404);
  }
});
