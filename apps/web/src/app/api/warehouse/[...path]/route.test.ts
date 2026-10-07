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
});

it('warehouse 白名单外的路径被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 库存流水/预占属 7.1.2 与 7.2.1，尚未交付，不应被代理放行。
  // 7.1.1.2 的明细只读端点已在白名单里，但它的行内操作入口指向的路由
  // （adjustments / transfer / ledger）仍**不**放行 —— 入口契约就位，
  // 目标模块交付后再加，否则就是一个点下去 404 的死链。
  for (const path of [
    'warehouse/inventory/ledger',
    'warehouse/inventory/reservations',
    'warehouse/inventory/adjustments',
    'warehouse/inventory/transfer',
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
