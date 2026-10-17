import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.2.1.2 审单规则的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(
    new URL('../../../components/orders/order-review-rule-model.ts', import.meta.url),
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
 * {@code trade/order-review}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/order-review';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{query\([^)]*\)\}/g, '')
      // 订单 id / 规则 id / 判定 id 三处分别换成同一个 uuid 形状。
      .replace(/\$\{(orderId|ruleId|evaluationId)\}/g, ID)
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
      'trade/order-review/capabilities',
      'trade/order-review/rules',
      `trade/order-review/rules/${ID}`,
      `trade/order-review/rules/${ID}/status`,
      'trade/order-review/buyer-profiles',
      `trade/order-review/orders/${ID}/review`,
      `trade/order-review/orders/${ID}/trial`,
      'trade/order-review/evaluations',
      `trade/order-review/evaluations/${ID}`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/order-review/rules',
    `trade/order-review/rules/${ID}/status`,
    `trade/order-review/orders/${ID}/review`,
    `trade/order-review/orders/${ID}/trial`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request(`trade/order-review/rules/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/order-review/rules/${ID}`.split('/') }),
  });
  await PUT(request('trade/order-review/buyer-profiles', 'PUT'), {
    params: Promise.resolve({ path: 'trade/order-review/buyer-profiles'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/order-review/orders'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.2.1.1 待审队列、9.2.1.3 异常拦截都未交付，9.2.1.2 不推进订单状态。
    'trade/order-review/orders',
    'trade/order-review/pending',
    'trade/order-review/blocks',
    'trade/order-review/bulk-review',
    // 判定是 append-only 留痕，没有事件流。
    `trade/order-review/evaluations/${ID}/events`,
    // 规则不提供删除：删掉会让历史命中链断链。
    `trade/order-review/rules/${ID}/delete`,
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/order-review/rule',
    'trade/order-review/evaluation',
    'trade/order-review/buyer-profile',
    // 单段裸路径之下多接一段未登记的子资源。
    `trade/order-review/rules/${ID}/conditions`,
    `trade/order-review/rules/${ID}/evaluate`,
    `trade/order-review/buyer-profiles/${ID}`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/order-review/rules/not-a-uuid',
    // orders/{uuid} 之下只接 review 与 trial 两个动作。
    `trade/order-review/orders/${ID}/reject`,
    `trade/order-review/orders/${ID}/cancel`,
    `trade/order-review/orders/not-a-uuid/review`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `order-review` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的概览接口」可用。
 */
it('裸路径 order-review 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/order-review';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * ⚠️ 白名单是**按路径**匹配的，管不了 HTTP 方法：同一个 path 上 DELETE 也会被转发。
 *
 * <p>所以「规则与判定留痕不可删」这件事**不能**靠白名单保证 —— 它由**后端**保证：
 * {@code OrderReviewRuleController} 没有任何 DeleteMapping，删除请求会落到后端拿 405/404。
 *
 * <p>判据取 **codegen 产物** {@code packages/api-contracts/src/trade.openapi.json}：
 * 那是后端真实端点的一份快照，将来若有人加了删除端点、忘了改这页文案，
 * 这条断言会先红 —— 它盯的是「删除端点存在」这个事实，不是某段注释。
 */
it('审单规则与判定留痕没有删除端点 —— 删除不是本模块提供的能力', () => {
  const spec = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL('../../../../../../packages/api-contracts/src/trade.openapi.json', import.meta.url),
      ),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, unknown>> };
  const reviewPaths = Object.keys(spec.paths).filter((p) => p.startsWith('/trade/order-review'));
  expect(reviewPaths.length, '契约里一条 order-review 路径都没有，判定本身失效了').toBeGreaterThan(0);
  const deletable = reviewPaths.filter((p) => spec.paths[p]?.delete);
  expect(deletable, '审单凭据可以被删掉了：判定留痕是客服向买家解释的依据')
    .toEqual([]);
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
    // 9.1.1.1 订单列表
    'trade/sales-orders/list',
    'trade/sales-orders/list/capabilities',
    // 9.3.2.2 履约路径决策
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
    // 9.3.1.2 合单
    'trade/fulfillment/consolidation/capabilities',
    'trade/fulfillment/consolidation/consolidations',
    `trade/fulfillment/consolidation/consolidations/${ID}`,
    // 9.3.2.1 分仓
    'trade/fulfillment/allocation/allocations',
    // 9.6.1.3 代发跟踪
    'trade/fulfillment/dropship-tracking/trackings',
    // 9.4.1.1 待发货
    'trade/fulfillment/pending-shipment/board',
    // 9.7.1.1 批发订单
    'trade/b2b-orders/receivables',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
