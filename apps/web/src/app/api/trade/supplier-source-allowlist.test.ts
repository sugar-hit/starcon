import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 6.1.2.1 档口与货源池的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/supply/sources-model.ts', import.meta.url)),
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
 * <p>模型里的端点既有字面量（{@code 'trade/sources/options'}）也有模板串
 * （{@code `trade/sources/items/${itemId}/favorite`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'trade/sources';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    found.add(raw);
  }
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    const cleaned = raw
      // 平台段是**平台词表**（ALIBABA_1688 / YIWU_GOU），不是 uuid —— 先替换它，
      // 否则会被下面的通用 uuid 替换吃掉，抽出一个不存在的形状。
      .replace(/\$\{platform\}/g, 'ALIBABA_1688')
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
      'trade/sources/options',
      'trade/sources/overview',
      'trade/sources/capabilities',
      'trade/sources/stalls',
      `trade/sources/stalls/${ID}`,
      `trade/sources/stalls/${ID}/sync`,
      `trade/sources/stalls/${ID}/collections`,
      'trade/sources/items',
      `trade/sources/items/${ID}`,
      `trade/sources/items/${ID}/favorite`,
      `trade/sources/items/${ID}/sku-links`,
      `trade/sources/items/${ID}/sku-links/${ID}`,
      `trade/sources/sku/${ID}/sources`,
      'trade/sources/suppliers',
      'trade/sources/collector-endpoint/ALIBABA_1688',
    ]),
  );
});

it('写形状（POST / PUT / DELETE）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/sources/stalls',
    `trade/sources/stalls/${ID}/sync`,
    `trade/sources/stalls/${ID}/collections`,
    `trade/sources/items/${ID}/favorite`,
    `trade/sources/items/${ID}/sku-links`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request(`trade/sources/stalls/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/sources/stalls/${ID}`.split('/') }),
  });
  await PUT(request('trade/sources/collector-endpoint/ALIBABA_1688', 'PUT'), {
    params: Promise.resolve({
      path: 'trade/sources/collector-endpoint/ALIBABA_1688'.split('/'),
    }),
  });
  const deleted = await DELETE(request(`trade/sources/items/${ID}/favorite`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/sources/items/${ID}/favorite`.split('/') }),
  });
  expect(deleted.status).toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sources/stalls'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有「一条不存在的汇总列表接口」。
    'trade/sources',
    // 快照版本随详情一起返回，没有独立端点。
    `trade/sources/items/${ID}/snapshots`,
    // 货源快照是外部观测，改它等于篡改事实。
    `trade/sources/items/${ID}/update`,
    // 货源条目随档口详情一起返回。
    `trade/sources/stalls/${ID}/items`,
    // 比价就在 sku/{uuid}/sources 里，不另开一个形状。
    `trade/sources/sku/${ID}/compare`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    `trade/sources/items/not-a-uuid`,
    // 档口之下只接 sync 与 collections 两个动作。
    `trade/sources/stalls/${ID}/refresh`,
    `trade/sources/stalls/${ID}/collect`,
    // sku-links 之下只接 {linkId} 一段。
    `trade/sources/items/${ID}/sku-links/${ID}/history`,
    // 平台词表段不是任意文本。
    'trade/sources/collector-endpoint/alibaba-1688',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `sources` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的概览接口」可用。
 */
it('裸路径 sources 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/sources';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 判据取 **codegen 产物** {@code packages/api-contracts/src/trade.openapi.json}：
 * 那是后端真实端点的一份快照。白名单放行的形状必须与契约里的形状一一对应 ——
 * 白名单多放一个 = 浏览器里会转给一个不存在的端点。
 */
it('白名单放行的形状与后端契约一一对应（不多放、不少放）', () => {
  const spec = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL('../../../../../../packages/api-contracts/src/trade.openapi.json', import.meta.url),
      ),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, unknown>> };
  const contractPaths = Object.keys(spec.paths)
    .filter((p) => p.startsWith('/trade/sources'))
    .map((p) => p.replace('/trade/', 'trade/'))
    .sort();
  expect(contractPaths.length, '契约里一条 sources 路径都没有，判定本身失效了').toBeGreaterThan(0);

  // 契约里有的形状，白名单必须放行。
  const allowed = new Set([
    'trade/sources/capabilities',
    'trade/sources/options',
    'trade/sources/overview',
    'trade/sources/suppliers',
    'trade/sources/stalls',
    `trade/sources/stalls/{stallId}`,
    `trade/sources/stalls/{stallId}/sync`,
    `trade/sources/stalls/{stallId}/collections`,
    'trade/sources/items',
    `trade/sources/items/{itemId}`,
    `trade/sources/items/{itemId}/favorite`,
    `trade/sources/items/{itemId}/sku-links`,
    `trade/sources/items/{itemId}/sku-links/{linkId}`,
    `trade/sources/sku/{skuId}/sources`,
    'trade/sources/collector-endpoint/{platform}',
  ]);
  expect(new Set(contractPaths)).toEqual(allowed);
});

/**
 * 本车道往 `services[]` 里**新增了一条 trade/ 元素**（不并既有那条的超长 alternation），
 * 所以必须钉住「既有 trade 模块的入口没有被本条吃掉」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 trade 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.2.1.1 待审队列（本条插在它之后）
    'trade/order-pending-review/capabilities',
    'trade/order-pending-review/queue',
    // 9.2.2.2 风控校验（本条插在它之前）
    'trade/orders/risk-validation/capabilities',
    'trade/orders/risk-validation/signals',
    // 9.3.2.2 履约路径决策
    'trade/fulfillment/routing/capabilities',
    // 9.4.1.1 待发货
    'trade/fulfillment/pending-shipment/board',
    // 9.7.1.1 批发订单
    'trade/b2b-orders/receivables',
    // 6.4.1.1 加工单
    'trade/processing/capabilities',
    'trade/processing/factories',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
