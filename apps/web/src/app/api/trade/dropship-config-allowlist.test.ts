import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.6.1.1 代发模式配置的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在两层结构里新加一组</b>：外层仓库级 `trade/` 段已有，
 * 内层已有 9.3.2.2 的 `fulfillment/routing` 组与 9.3.2.1 的 `fulfillment/allocation` 组。
 * 本模块新增的是 `fulfillment/dropship` 组，<b>没有并进既有两组</b> ——
 * 所以除了「我的形状被放行」，还必须钉住
 * 「routing / allocation 组仍然只放行它们自己的形状」与「裸路径仍然被拒」。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/dropship-config-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/dropship';
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/dropship/capabilities',
      'trade/fulfillment/dropship/shop-refs',
      'trade/fulfillment/dropship/supplier-refs',
      'trade/fulfillment/dropship/supplier-stocks',
      'trade/fulfillment/dropship/configs',
      `trade/fulfillment/dropship/configs/${ID}`,
      `trade/fulfillment/dropship/configs/${ID}/status`,
      `trade/fulfillment/dropship/configs/${ID}/lines`,
      'trade/fulfillment/dropship/evaluations',
      'trade/fulfillment/dropship/events',
    ]),
  );
});

it('维护配置、登记投影与评估的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/dropship/shop-refs',
    'trade/fulfillment/dropship/supplier-refs',
    'trade/fulfillment/dropship/supplier-stocks',
    'trade/fulfillment/dropship/configs',
    'trade/fulfillment/dropship/configs',
    `trade/fulfillment/dropship/configs/${ID}/status`,
    `trade/fulfillment/dropship/configs/${ID}/lines`,
    'trade/fulfillment/dropship/evaluations',
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), { params: { path: segments } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/dropship/evaluations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.6.1.2 代发单与供应商下单、10 物流、12 财务都未交付。
    'trade/fulfillment/dropship/dropship-orders',
    'trade/fulfillment/dropship/purchase-orders',
    'trade/fulfillment/dropship/shipments',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/dropship/dropship',
    'trade/fulfillment/dropship/shop-ref',
    'trade/fulfillment/dropship/supplier-stock',
    'trade/fulfillment/dropship/events/' + ID,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/fulfillment/dropship/configs/not-a-uuid',
    // 输入投影表没有单条详情（集合读 + POST 维护就是全部形状）。
    `trade/fulfillment/dropship/shop-refs/${ID}`,
    `trade/fulfillment/dropship/supplier-refs/${ID}`,
    `trade/fulfillment/dropship/supplier-stocks/${ID}`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trade/fulfillment/dropship 列表」的映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/dropship 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/dropship';
  const response = await GET(request(path), { params: { path: path.split('/') } });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**新加的是一组**，不是往 9.3.2.2 / 9.3.2.1 的既有组里并 ——
 * 所以这里反向钉住那两组仍然只放行它们自己的形状，且不被新组挤掉或污染。
 */
it('兄弟车道的 fulfillment/routing 与 fulfillment/allocation 组未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    `trade/fulfillment/routing/decisions/${ID}/status`,
    'trade/fulfillment/allocation/capabilities',
    'trade/fulfillment/allocation/rules',
    `trade/fulfillment/allocation/allocations/${ID}`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `兄弟车道的 ${path} 被本车道挤掉了`).toBe(200);
  }
  // dropship 的形状不得从既有组漏过去（三组是分开的）。
  for (const path of [
    'trade/fulfillment/routing/dropship',
    'trade/fulfillment/routing/configs',
    'trade/fulfillment/allocation/dropship',
    'trade/fulfillment/allocation/configs',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不属于兄弟车道的组`).toBe(404);
  }
});

it('trade 出口真的转发到 8085（不是白名单放行后自己 404/503）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/dropship/configs';
  const response = await GET(request(path), { params: { path: path.split('/') } });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/dropship/configs'),
    expect.anything(),
  );
});
