import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.3.1.2 合单的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(new URL('../../../components/orders/order-consolidation-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const MEMBER = '22222222-2222-2222-2222-222222222222';

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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * {@code trade/fulfillment/consolidation}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/consolidation';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(...)} / ${consolidationId} / ${memberId} 分别处理：
      // 查询串整段去掉，id 换成 uuid。
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{(consolidationId|memberId)\}/g, ID)
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/consolidation/capabilities',
      'trade/fulfillment/consolidation/rule',
      'trade/fulfillment/consolidation/consolidations',
      `trade/fulfillment/consolidation/consolidations/${ID}`,
      `trade/fulfillment/consolidation/consolidations/${ID}/status`,
      `trade/fulfillment/consolidation/consolidations/${ID}/members/${ID}/writeback`,
      'trade/fulfillment/consolidation/writeback-tasks',
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/consolidation/candidates',
    'trade/fulfillment/consolidation/consolidations',
    `trade/fulfillment/consolidation/consolidations/${ID}/status`,
    `trade/fulfillment/consolidation/consolidations/${ID}/members/${MEMBER}/writeback`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request('trade/fulfillment/consolidation/rule', 'PUT'), {
    params: Promise.resolve({ path: 'trade/fulfillment/consolidation/rule'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/consolidation/consolidations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.3.3.1 履约请求、8.3.3.2 打包都未在本服务落地。
    'trade/fulfillment/consolidation/fulfillment-requests',
    'trade/fulfillment/consolidation/packing',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/consolidation/consolidation',
    'trade/fulfillment/consolidation/writeback',
    // DELETE 刻意不放行：合并单与事件流是履约凭据。
    `trade/fulfillment/consolidation/consolidations/${ID}/members`,
    // 单段裸 id 之下多接一段未交付的子资源。
    `trade/fulfillment/consolidation/consolidations/${ID}/events`,
    `trade/fulfillment/consolidation/consolidations/${ID}/writeback`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/fulfillment/consolidation/consolidations/not-a-uuid',
    // members/{id}/writeback 只多接这一个动作段。
    `trade/fulfillment/consolidation/consolidations/${ID}/members/not-a-uuid/writeback`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 根路径 `fulfillment/consolidation` 本身**必须被拒**：后端没有这个端点
 * （没有 Collection 映射在裸路径上），放行它会让界面以为「一条不存在的列表接口」可用。
 */
it('裸路径 fulfillment/consolidation 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/consolidation';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道往 `services[]` 里**新增了一条 trade/ 元素**（不并既有那条的超长 alternation），
 * 所以必须钉住「既有 trade 分支没被本条吃掉」——
 * 同一前缀下两条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 trade 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.1.1.1 订单列表
    'trade/sales-orders/list',
    'trade/sales-orders/list/capabilities',
    // 9.3.2.2 履约路径决策
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    'trade/fulfillment/routing/decisions/11111111-1111-1111-1111-111111111111',
    // 9.3.2.1 分仓
    'trade/fulfillment/allocation/allocations',
    // 9.7.1.1 批发订单
    'trade/b2b-orders/receivables',
    // 9.6.1.3 代发跟踪
    'trade/fulfillment/dropship-tracking/trackings',
    // 9.4.1.1 待发货
    'trade/fulfillment/pending-shipment/board',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
