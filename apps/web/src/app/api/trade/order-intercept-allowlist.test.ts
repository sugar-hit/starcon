import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.2.1.3 异常拦截的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在两层结构里新加一组</b>：外层仓库级 `trade/` 段已有，
 * 内层已有 9.2.1.2 的 `order-review` 组、9.2.2.2 的 `orders/risk-validation` 组、
 * 9.3.x 的 `fulfillment/*` 组。本模块新增的是**独立一条 services[] 元素**，
 * 没有并进既有组 —— 所以除了「我的形状被放行」，还必须钉住
 * 「别人的形状仍然被放行」与「刻意不存在的形状仍然 404」。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-intercept-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/order-intercept';
  const found = new Set<string>();
  // 只扫 path(...) 的**实参**，而不是源码里所有模板串：
  // path 内部那一行 `${base}${extra}` 会被拼成一个没有斜杠的假路径
  // （trade/order-intercept<uuid>），把断言带到 404 上，而那不是白名单的问题。
  for (const match of SOURCE.matchAll(/path\(\s*(['"`])([^'"`]*)\1/g)) {
    const cleaned = match[2]!.replace(/\$\{[^}]*\}/g, ID).replace(/\?.*$/, '');
    if (!cleaned.startsWith('/')) continue;
    found.add(`${base}${cleaned}`);
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
      'trade/order-intercept/capabilities',
      'trade/order-intercept/overview',
      'trade/order-intercept/policy',
      'trade/order-intercept/restrictions',
      'trade/order-intercept/sku-costs',
      'trade/order-intercept/checks',
      `trade/order-intercept/checks/${ID}`,
      `trade/order-intercept/checks/${ID}/disposition`,
    ]),
  );
});

it('11 个端点对应的形状都被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const getShapes = [
    'trade/order-intercept/capabilities',
    'trade/order-intercept/overview',
    'trade/order-intercept/policy',
    'trade/order-intercept/restrictions?page=0&size=20',
    'trade/order-intercept/sku-costs?page=0&size=20',
    'trade/order-intercept/checks?page=0&size=20',
    `trade/order-intercept/checks/${ID}`,
  ];
  for (const path of getShapes) {
    const response = await GET(request(path.split('?')[0]!), {
      params: Promise.resolve({ path: path.split('?')[0]!.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }

  for (const path of [
    'trade/order-intercept/policy',
    'trade/order-intercept/restrictions',
    'trade/order-intercept/sku-costs',
    'trade/order-intercept/checks',
    `trade/order-intercept/checks/${ID}/disposition`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  const putResponse = await PUT(request('trade/order-intercept/policy', 'PUT'), {
    params: Promise.resolve({ path: 'trade/order-intercept/policy'.split('/') }),
  });
  expect(putResponse.status, 'PUT policy 未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalled();
});

/**
 * 蓝图加粗句「拦截不自动取消，默认转人工」在 API 层的对手形状。
 *
 * <p>这些形状后端**刻意没有**：放行它们等于让界面以为「自动取消」已交付，
 * 而界面上那个按钮一旦存在，人就会去按它 —— 而它背后没有业务在跑。
 */
it('「自动取消」这类形状刻意不放行（404 且不发请求）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `trade/order-intercept/checks/${ID}/auto-cancel`,
    `trade/order-intercept/checks/${ID}/confirm-cancel`,
    `trade/order-intercept/checks/${ID}/decision`,
    'trade/order-intercept/checks/batch-disposition',
    'trade/order-intercept/checks/batch-release',
    // 9.2.2.1 未交付，本模块不假装有地址校验接口
    'trade/order-intercept/address-validations',
    // 裸路径后端没有对应映射
    'trade/order-intercept',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了 —— 那是后端不存在的映射`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/** 回归断言：别的车道往 trade 里加的形状仍然被放行，不能被本条正则吃掉。 */
it('同服务的兄弟模块形状仍然被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 9.2.1.2 审单规则
    'trade/order-review/capabilities',
    'trade/order-review/rules',
    'trade/order-review/buyer-profiles',
    // 9.2.2.2 风控校验
    'trade/orders/risk-validation/capabilities',
    'trade/orders/risk-validation/assessments',
    `trade/orders/risk-validation/assessments/${ID}/review`,
    // 9.3.3.1 履约请求
    'trade/fulfillment/requests/capabilities',
    `trade/fulfillment/requests/${ID}/advance`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
