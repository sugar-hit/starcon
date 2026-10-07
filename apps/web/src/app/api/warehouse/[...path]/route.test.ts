import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST } from './route';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET', origin = 'http://localhost:3000') {
  return new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
    method,
    headers: {
      origin,
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 7.1.1.1 库存总览：warehouse allowlist 必须放行总览与参照数据的全路径。
 *
 * <p>背景见 docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md：allowlist 是
 * 锚定正则，落不在分支里的路径一律被拦成 404，页面只进错误态，看起来像"后端没实现"。
 * 这组用例把总览用到的每条路由钉住，防止下次再加子路径时静默 404。
 */
it('warehouse 的全部路由被放行且转发到 warehouse 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'warehouse/inventory/overview',
    'warehouse/inventory/buckets',
    'warehouse/inventory/bucket-actions',
    'warehouse/inventory/capabilities',
    'warehouse/inventory/policies',
    `warehouse/inventory/policies/${id}`,
    'warehouse/warehouses',
    'warehouse/legal-entities',
    'warehouse/skus',
    'warehouse/quality-statuses',
    'warehouse/locations',
    'warehouse/lots',
    // 7.5.1.1 调拨单：列表 / 详情 / 分录，以及四个动作端点。
    // 动作路径必须逐个钉住 —— 少放行一个，页面上的按钮就会变成静默 404。
    'warehouse/inventory/transfers',
    `warehouse/inventory/transfers/${id}`,
    `warehouse/inventory/transfers/${id}/movements`,
    `warehouse/inventory/transfers/${id}/confirm`,
    `warehouse/inventory/transfers/${id}/ship`,
    `warehouse/inventory/transfers/${id}/receive`,
    `warehouse/inventory/transfers/${id}/close`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8084/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 阈值写入走 POST / DELETE，同样必须在白名单里
  expect(
    (await POST(request('warehouse/inventory/policies', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'policies'] },
    })).status,
  ).toBe(200);
  expect(
    (await DELETE(request(`warehouse/inventory/policies/${id}`, 'DELETE'), {
      params: { path: ['warehouse', 'inventory', 'policies', id] },
    })).status,
  ).toBe(200);
  // 7.3.1.2 效期与先进先出的七个形状：alerts / policies / policy/{skuId} 读，
  // policy 写（停用改配置而不是删行），allocation POST 试算，warehouses 与 lots/{skuId} 参照。
  for (const path of [
    'warehouse/inventory/expiry/alerts',
    'warehouse/inventory/expiry/policies',
    'warehouse/inventory/expiry/policy',
    `warehouse/inventory/expiry/policy/${id}`,
    'warehouse/inventory/expiry/warehouses',
    `warehouse/inventory/expiry/lots/${id}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  expect(
    (await POST(request('warehouse/inventory/expiry/allocation', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'expiry', 'allocation'] },
    })).status,
  ).toBe(200);
  expect(
    (await POST(request('warehouse/inventory/expiry/policy', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'expiry', 'policy'] },
    })).status,
  ).toBe(200);
  // 刻意**不在这里**断言 DELETE 被代理拒绝：代理只按路径形状放行、不看方法，
  // 「策略行不可删除」的两道真实防线是后端控制器没有 DELETE 形状（返回 405）
  // 与迁移里的 REVOKE DELETE，两者都由 InventoryExpiryIntegrationTest 实跑证明。
  // 7.5.1.2 在途库存：六个真实调用点逐个钉住。
  // 路径是从 inventory-in-transit-model.ts 里反向抽取的，不是照着正则抄的 ——
  // 少放行一个，前端就只在浏览器里静默 404（pnpm test 直连服务层测不出漏登记）。
  for (const path of [
    'warehouse/inventory/in-transit',
    'warehouse/inventory/in-transit/alerts',
    'warehouse/inventory/in-transit/netting',
    `warehouse/inventory/in-transit/${id}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  // 登记与两个写动作走 POST：同一路径两种方法，白名单只看路径形状不看方法，
  // 所以这里显式跑 POST 以证明写动作也真的被转发（而不是只放行了读）。
  for (const path of [
    'warehouse/inventory/in-transit',
    `warehouse/inventory/in-transit/${id}/arrive`,
    `warehouse/inventory/in-transit/${id}/discrepancy`,
  ]) {
    expect(
      (await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status,
    ).toBe(200);
  }
});

it('在途库存白名单：未交付的子路径仍被拒绝', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 刻意**不**放行的形状：后端 InventoryInTransitController 只有上面那六个端点。
  // 多一段 / 少一个动作后缀、以及看起来合理的推导路径（audit / reconcile / forecast）
  // 在后端都不存在，放行它们等于让界面以为已交付 —— 放行不该存在的路径比 404 更糟。
  for (const path of [
    `warehouse/inventory/in-transit/${'11111111-1111-1111-1111-111111111111'}/cancel`,
    `warehouse/inventory/in-transit/${'11111111-1111-1111-1111-111111111111'}/arrive/reverse`,
    'warehouse/inventory/in-transit/audit',
    'warehouse/inventory/in-transit/forecast',
    // alerts / netting 是字面量子路径，不能再往下挂一段。
    `warehouse/inventory/in-transit/alerts/${'11111111-1111-1111-1111-111111111111'}`,
  ]) {
    expect(
      (await GET(request(path), { params: { path: path.split('/') } })).status,
    ).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('warehouse 白名单外的路径被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 预占（7.2.1）与调整单（7.4.1.1）尚未交付，不应被代理放行。
  // transfer 曾在这份拒绝清单里，那是 7.5.1.1 交付前的状态；它已并入且前端在调
  // /transfers 这组路径，不放行等于调拨页面在浏览器里 404，故已移出。
  // 7.1.1.2 明细页的行内操作入口指向这些路由，入口契约就位但目标模块还没到 ——
  // 目标模块交付后再加，否则就是一个点下去 404 的死链。
  //
  // 注意 ledger 曾在这份拒绝清单里：那是 7.1.2 尚未交付时的状态，
  // 7.1.2.1 已交付并入后它的前端页面就在调这个路径，不放行等于页面在浏览器里 404，
  // 所以已移出拒绝清单（放行覆盖由上面「已放行」用例钉住）。
  for (const path of [
    'warehouse/inventory/reservations',
    'warehouse/inventory/adjustments',
    'warehouse/internal/access',
  ]) {
    expect(
      (await GET(request(path), { params: { path: path.split('/') } })).status,
    ).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('跨站写入被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  expect(
    (
      await POST(request('warehouse/inventory/policies', 'POST', 'https://attacker.example'), {
        params: { path: ['warehouse', 'inventory', 'policies'] },
      })
    ).status,
  ).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
