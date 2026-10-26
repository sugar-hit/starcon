import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET } from './[...path]/route';

/**
 * 1.1.3.1 库存风险的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：本模块工作区里真实调用的每个路径都必须被放行。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**新增一条独立的
 * {@code services[]} 元素**，绝不收窄既有分支。取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

afterEach(() => vi.unstubAllGlobals());

function request(path: string) {
  return new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
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

it('本模块真实调用的每个路径都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const paths = [
    'warehouse/inventory-risk/capabilities',
    'warehouse/inventory-risk/projection',
  ];
  for (const path of paths) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 放行还不够：必须真的转发到 warehouse 服务（8084）。
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory-risk/projection'),
    expect.anything(),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有「一条不存在的汇总端点」，汇总在 projection 里。
    'warehouse/inventory-risk',
    // 后端没有子资源：投影是集合读，明细在行内。
    'warehouse/inventory-risk/projection/11111111-1111-1111-1111-111111111111',
    'warehouse/inventory-risk/detail',
    // 本模块是只读投影：没有写形状。
    'warehouse/inventory-risk/recompute',
    'warehouse/inventory-risk/refresh',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的 warehouse 分支。
 *
 * <p>白名单是多车道共用的**累积型**文件。并入时若取错一侧，会静默删掉已交付模块
 * 的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿，
 * 因为白名单只在浏览器端生效。所以必须正向钉住已放行的全部形状。
 */
it('不误伤其他车道已登记的 warehouse 分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 7.1.1.3 可用量
    'warehouse/inventory/sellable/skus',
    // 7.6.1.1 库龄
    'warehouse/inventory/aging/buckets',
    // 7.8.2.2 缺货与饥饿预警
    'warehouse/inventory/stockout-alerts/capabilities',
    // 7.5.1.2 在途
    'warehouse/inventory/in-transit',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});
