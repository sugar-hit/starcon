import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.5.1.1 时效分析的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在既有组旁边新起一组</b>：本模块新增
 * {@code fulfillment/timeliness}，<b>没有并进</b> shipment-records /
 * pending-shipment / dropship-tracking 任何一组。理由不是洁癖：时效分析是
 * **只读度量**模块，而 shipment-records 是「已发货事实」的登记处，把两者合在一支
 * 会让「在这个页面上改一笔就能改已发货事实」看起来成立。所以除了「我的形状被放行」，
 * 还必须钉住兄弟组仍然只放行它们自己的形状。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-timeliness-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/timeliness';
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/timeliness/capabilities',
      'trade/fulfillment/timeliness/report',
      'trade/fulfillment/timeliness/dimensions',
      'trade/fulfillment/timeliness/overdue',
      'trade/fulfillment/timeliness/events',
      'trade/fulfillment/timeliness/facts',
      `trade/fulfillment/timeliness/facts/${ID}`,
      `trade/fulfillment/timeliness/facts/${ID}/promise`,
      `trade/fulfillment/timeliness/facts/${ID}/delivery`,
      'trade/fulfillment/timeliness/sync',
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/timeliness/sync',
    `trade/fulfillment/timeliness/facts/${ID}/promise`,
    `trade/fulfillment/timeliness/facts/${ID}/delivery`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/timeliness'),
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
    // 本模块是只读度量 + 登记，**没有**任何修改事实的形状：
    // 时长由 DB 的生成列复算，谁都不许手改。
    'trade/fulfillment/timeliness/tracking',
    'trade/fulfillment/timeliness/carriers',
    'trade/fulfillment/timeliness/evaluate',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/timeliness/fact',
    'trade/fulfillment/timeliness/report/filters',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    `trade/fulfillment/timeliness/facts/not-a-uuid`,
    // facts/{id} 下只有 promise 与 delivery 两个两段形状。
    `trade/fulfillment/timeliness/facts/${ID}/status`,
    `trade/fulfillment/timeliness/facts/${ID}/cancel`,
    `trade/fulfillment/timeliness/facts/${ID}/delete`,
    // 凭据行 append-only：没有改 / 删形状
    `trade/fulfillment/timeliness/facts/${ID}/promise/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trade/fulfillment/timeliness 列表」的映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/timeliness 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/timeliness';
  const response = await GET(request(path), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 兄弟组不得被挤掉：插入本组时最容易犯的错是把外层括号或相邻组写坏，
 * 结果**别人的**路径开始 404 —— 而那属于另一条车道的事故。
 */
it('兄弟车道四组仍只放行它们自己的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/shipment-records/capabilities',
    'trade/fulfillment/shipment-records/board',
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/pending-shipment/board',
    'trade/fulfillment/dropship-tracking/capabilities',
    'trade/fulfillment/dropship-margin/capabilities',
    'trade/fulfillment/dropship-margin/report',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟组 ${path} 被本车道的插入挤掉了`).toBe(200);
  }
});

it('本模块的形状不得从兄弟组漏过去（两组的路径前缀不能互相吃）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // shipment-records 下**没有** timeliness 的任何形状，反之亦然
  for (const path of [
    'trade/fulfillment/shipment-records/timeliness',
    'trade/fulfillment/shipment-records/facts',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
});
