import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET } from './[...path]/route';

/**
 * 1.1.1.2 异常订单队列的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 而且 `api-proxy.ts` 是**多车道共用的累积型白名单**，取错一侧会静默删掉
 * 别人已交付的路由（表现同样是页面 404，而所有检查全绿）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是那几条<b>写形状</b> ——
 *       本模块是只读投影（卷 01：「工作台只读 + 跳转」），
 *       放行一个后端不存在的写入口，界面就会以为「批量标记忽略」已交付。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/dashboard/anomaly-order-workspace.tsx', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string) {
  return new NextRequest(`http://localhost:3000/api/trade/${path}`, {
    method: 'GET',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
  });
}

/**
 * 从源码里抽出真实调用点。
 *
 * <p>形如 `api<...>('trade/anomaly-orders/capabilities')` 的字面量，
 * 以及模板串里的 `trade/anomaly-orders/${orderId}` —— 后者把 {@code ${...}}
 * 换成 UUID 形状，query 部分截掉。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      .trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*'([^']*)'/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw.replace(/\?.*$/, '').trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('前端源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '前端源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/anomaly-orders/capabilities',
      'trade/anomaly-orders/options',
      `trade/anomaly-orders/${ID}`,
    ]),
  );
});

it('清单的裸路径也被放行（它就是集合读）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await GET(request('trade/anomaly-orders'), {
    params: Promise.resolve({ path: 'trade/anomaly-orders'.split('/') }),
  });
  expect(response.status).toBe(200);
});

it('跨服务读的两个端点也被放行（前端聚合层要读它们）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/buyer-blacklist/flags',
    'warehouse/inventory/sellable/skus',
  ]) {
    const response = await GET(new NextRequest(`http://localhost:3000/api/${path}`, {
      headers: { cookie: 'starcon_session=private-session' },
    }), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/anomaly-orders/not-a-uuid',
    // 🔴 「标记忽略」没有归属系统的写端点，工作台不写业务数据 ⇒ 这个形状不存在。
    `trade/anomaly-orders/${ID}/ignore`,
    // 批量写入口一律不存在：批量动作落到归属系统。
    'trade/anomaly-orders/bulk-ignore',
    'trade/anomaly-orders/bulk-transfer',
    // 汇总与选项都在子路径上，裸的 /summary 不存在。
    'trade/anomaly-orders/summary',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
