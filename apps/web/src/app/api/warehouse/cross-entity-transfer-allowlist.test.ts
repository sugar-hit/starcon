import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST } from './[...path]/route';

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
 * 7.5.2.1 跨法人调拨：api-proxy 白名单必须放行本模块的**全部**调用点。
 *
 * <p>为什么单独一个文件而不是并进 route.test.ts：那份用例属于 7.1.1.1 / 7.5.1.1 等已交付车道，
 * 并进去就要改别人在维护的清单。这里只钉住本模块自己前端代码里真实出现的每一条路径。
 *
 * <p>白名单**只在浏览器端生效**，后端集成测试与 pnpm typecheck 都测不出来：
 * 漏登记 = 页面在浏览器里 404，而后端 200。所以这组断言是本模块唯一的前端防线。
 *
 * <p>两个历史坑逐条防住：
 *
 * <ul>
 *   <li>分支必须落在 {@code inventory/(?:…)} 组**内** —— 加在组外的条目匹配不到带前缀的路径；
 *   <li>每个子路径前都要有 {@code /} —— 漏一个 {@code /settlement/} 就静默失配。
 * </ul>
 */
it('跨法人调拨的全部调用点被放行且转发到 warehouse 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';

  // 逐条对应 legal-entity-transfer-model.ts 里真实出现的路径。
  for (const path of [
    'warehouse/inventory/cross-entity-transfers',
    `warehouse/inventory/cross-entity-transfers/${id}`,
    `warehouse/inventory/cross-entity-transfers/${id}/movements`,
    `warehouse/inventory/cross-entity-transfers/${id}/settlement`,
    // 两个独立确认端点：少放行一个，页面上的确认按钮就是静默 404。
    `warehouse/inventory/cross-entity-transfers/${id}/source-confirm`,
    `warehouse/inventory/cross-entity-transfers/${id}/target-confirm`,
    `warehouse/inventory/cross-entity-transfers/${id}/ship`,
    `warehouse/inventory/cross-entity-transfers/${id}/receive`,
    `warehouse/inventory/cross-entity-transfers/${id}/close`,
    `warehouse/inventory/cross-entity-transfers/${id}/cancel`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8084/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }

  // 全部写动作都是 POST。逐个钉住：动作端点漏登记的表现是「点一下就没反应」。
  for (const suffix of ['source-confirm', 'target-confirm', 'ship', 'receive', 'close', 'cancel']) {
    const path = `warehouse/inventory/cross-entity-transfers/${id}/${suffix}`;
    expect(
      (await POST(request(path, 'POST'), { params: Promise.resolve({ path: path.split('/') }) }))
        .status,
    ).toBe(200);
  }
  expect(
    (
      await POST(request('warehouse/inventory/cross-entity-transfers', 'POST'), {
        params: Promise.resolve({ path: ['warehouse', 'inventory', 'cross-entity-transfers'] }),
      })
    ).status,
  ).toBe(200);
});

it('跨法人调拨不因本模块而放行未交付模块或不存在的子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  // 7.2.1 预占已交付（2026-10-13，inventory_reservation 落 warehouse），
  // 「尚未交付因而必须 404」那条断言已随之失效，与 route.test.ts 同步移除。
  // 剩下的 7.4.1.1 调整单仍未交付，不能被本模块顺手放行；
  // 不存在的子路径同样必须 404 —— 放行一个没有端点的形状，
  // 会让界面以为那个能力已经交付，而它点下去只拿到 404。
  for (const path of [
    'warehouse/inventory/adjustments',
    `warehouse/inventory/cross-entity-transfers/${id}/not-a-real-action`,
    'warehouse/inventory/cross-entity-transfers/settlement',
  ]) {
    expect(
      (await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) })).status,
    ).toBe(404);
  }
  // 刻意**不**在这里断言 DELETE 被代理拒绝：代理只按路径形状放行、不看方法，
  // 本模块的 {id} 形状是已放行的，所以 DELETE 会被转发出去。
  // 「跨法人调拨不可物理删除」的两道真实防线是后端控制器没有 DELETE 形状（返回 405）
  // 与迁移里的 REVOKE DELETE，都由 LegalEntityTransferIntegrationTest 实跑证明。
  expect(fetcher).not.toHaveBeenCalled();
});
