import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 9.3.3.1 履约请求的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(new URL('../../../components/orders/fulfillment-request-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const WORK = '22222222-2222-2222-2222-222222222222';

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
 * {@code trade/fulfillment/requests}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/requests';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(params)} / ${requestId} / ${workOrderId} 分别处理：
      // 查询串整段去掉，id 换成 uuid。
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{(requestId|workOrderId)\}/g, ID)
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个读调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个读调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/requests/capabilities',
      'trade/fulfillment/requests/resumable',
      'trade/fulfillment/requests/timeouts',
      `trade/fulfillment/requests/${ID}`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/requests',
    'trade/fulfillment/requests/scan-timeouts',
    `trade/fulfillment/requests/${ID}/advance`,
    `trade/fulfillment/requests/${ID}/block`,
    `trade/fulfillment/requests/${ID}/retry`,
    `trade/fulfillment/requests/${ID}/cancel`,
    `trade/fulfillment/requests/${ID}/complete`,
    `trade/fulfillment/requests/${ID}/reservation`,
    `trade/fulfillment/requests/${ID}/reservation/ack`,
    `trade/fulfillment/requests/${ID}/outbound-order`,
    `trade/fulfillment/requests/${ID}/outbound-order/ack`,
    `trade/fulfillment/requests/${ID}/work-orders`,
    `trade/fulfillment/requests/${ID}/work-orders/${WORK}/status`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/requests'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 释放预占与异常处置入口都判给 9.3.3.2，本模块不提供。
    `trade/fulfillment/requests/${ID}/release`,
    `trade/fulfillment/requests/${ID}/exceptions`,
    // 运单主权在 9.4.1.1 / 8.3.4.2。
    `trade/fulfillment/requests/${ID}/waybill`,
    // 单段裸 id 之下多接一段未交付的子资源。
    `trade/fulfillment/requests/${ID}/events`,
    `trade/fulfillment/requests/${ID}/steps`,
    // work-orders 的 status 动作段只多接这一个。
    `trade/fulfillment/requests/${ID}/work-orders/${WORK}`,
    `trade/fulfillment/requests/${ID}/work-orders/${WORK}/status/extra`,
    // 字面量子路径之下多接一段。
    'trade/fulfillment/requests/timeouts/extra',
    'trade/fulfillment/requests/resumable/extra',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/fulfillment/requests/not-a-uuid',
    `trade/fulfillment/requests/${ID}/advance/extra`,
    // 末尾多一个斜杠。
    'trade/fulfillment/requests/',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * DELETE 的真实拦截点不在白名单，而在**后端**。
 *
 * <p>刻意写这条是因为很容易把两件事搞混：白名单是**按 path 判别、不看 HTTP 方法**的，
 * 所以 DELETE 打到本模块任一已放行形状仍会被转发（这里 stub 掉 fetch 后拿到 503）。
 * 真正的拦截有三层，本用例逐条钉住可观察的那部分：
 *
 * <ol>
 *   <li>后端契约里**一个 DELETE 映射都没有**（下面直接读 OpenAPI 快照断言）——
 *       转发过去也只会拿到 405/404。</li>
 *   <li>V152 第 11 节把 UPDATE/DELETE 从 {@code trade_app} 收回，
 *       即便有人补了映射也删不动留痕表。</li>
 * </ol>
 */
it('后端控制器里没有任何 @DeleteMapping：白名单按 path 判别，删除请求由后端挡', () => {
  // 刻意读**源码**而不是 OpenAPI 快照：快照是构建产物（backend/target/openapi），
  // 干净检出的 CI 上还不存在，而这条断言要能天天跑。
  const controller = readFileSync(
    fileURLToPath(
      new URL(
        '../../../../../../backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestController.java',
        import.meta.url,
      ),
    ),
    'utf8',
  );
  expect(
    controller.includes('@DeleteMapping'),
    '履约流程与事件流是凭据，控制器不得提供任何 DELETE 入口',
  ).toBe(false);
  // 留痕三表只授权 INSERT：迁移里必须真的把 UPDATE/DELETE 收回去
  const migration = readFileSync(
    fileURLToPath(
      new URL(
        '../../../../../../backend/services/trade/src/main/resources/db/migration/V152__trade_fulfillment_request.sql',
        import.meta.url,
      ),
    ),
    'utf8',
  );
  expect(migration).toContain('REVOKE UPDATE,DELETE');
});

it('DELETE 打过来会被转发 —— 白名单只看 path，所以拦截点必须写清楚在后端', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `trade/fulfillment/requests/${ID}`;
  await DELETE(request(path, 'DELETE'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(
    fetcher,
    '白名单是 path 白名单：这条断言锁住「不靠白名单挡 DELETE」这个事实，'
      + '免得有人以为改一行正则就能删掉凭据',
  ).toHaveBeenCalled();
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
    // 9.3.1.2 合单
    'trade/fulfillment/consolidation/capabilities',
    'trade/fulfillment/consolidation/consolidations',
    'trade/fulfillment/consolidation/consolidations/11111111-1111-1111-1111-111111111111',
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
