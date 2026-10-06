import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST, PUT } from './route';
afterEach(() => vi.unstubAllGlobals());
function request(path: string, method = 'GET', origin = 'http://localhost:3000') {
  return new NextRequest(`http://localhost:3000/api/catalog/${path}`, {
    method,
    headers: {
      origin,
      cookie: 'starcon_catalog_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}
it('管理 API 与租户切换路由转发 identity，仅传服务端 cookie 身份', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'identity/roles',
    'identity/members',
    'identity/departments',
    'identity/children',
    'identity/permissions',
    'auth/memberships',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  for (const path of ['auth/switch', 'identity/owner-transfer'])
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  expect(
    (
      await PUT(request('identity/roles/abcdef', 'PUT'), {
        params: { path: ['identity', 'roles', 'abcdef'] },
      })
    ).status,
  ).toBe(200);
});
it('跨站写入和非白名单接口被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  expect(
    (
      await POST(request('identity/children', 'POST', 'https://attacker.example'), {
        params: { path: ['identity', 'children'] },
      })
    ).status,
  ).toBe(403);
  expect(
    (await GET(request('internal/access'), { params: { path: ['internal', 'access'] } })).status,
  ).toBe(404);
  expect(
    (
      await GET(request('identity/children/abc/members'), {
        params: { path: ['identity', 'children', 'abc', 'members'] },
      })
    ).status,
  ).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.1.3.1 规格轴：catalog allowlist 必须放行 spec-axis 全路径。
 *
 * <p>背景见 docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md：allowlist 是
 * 锚定正则，落不在分支里的路径一律被拦成 404 —— 页面只进错误态，看起来像「后端没实现」。
 * 现有测试只覆盖了 skus/export，所以 3.1.2.3 交付时漏放行没被发现。这组用例把
 * spec-axis 的每条路由钉住，防止下次再加子路径时静默 404。
 */
it('catalog/spec-axis 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/spec-axis/axes',
    'catalog/spec-axis/overview',
    `catalog/spec-axis/axes/${id}/options`,
    `catalog/spec-axis/spus/${id}`,
    `catalog/spec-axis/spus/${id}/axes`,
    'catalog/spec-axis/category-rules',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  const unbind = `catalog/spec-axis/spus/${id}/axes/22222222-2222-2222-2222-222222222222`;
  expect(
    (await DELETE(request(unbind, 'DELETE'), { params: { path: unbind.split('/') } })).status,
  ).toBe(200);
});

it('spec-axis 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  // allowlist 是精确路径：多一层未声明的子路径仍应 404，否则拼错的路径会被静默转发。
  for (const path of ['catalog/spec-axis/unknown', `catalog/spec-axis/axes/${id}/options/extra`]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.1.3.2 组合唯一性校验：catalog allowlist 必须放行 variant-combination 全路径。
 *
 * <p>同一个坑：allowlist 是锚定正则，没放进分支的路径一律 404，页面只会进错误态，
 * 看起来像"后端没实现"。这里把每条路由钉住。
 */
it('catalog/variant-combination 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const draftId = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/variant-combination/overview',
    `catalog/variant-combination/spus/${id}`,
    `catalog/variant-combination/spus/${id}/combinations`,
    `catalog/variant-combination/spus/${id}/drafts`,
    `catalog/variant-combination/spus/${id}/drafts/${draftId}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  for (const path of [
    `catalog/variant-combination/spus/${id}/check`,
    `catalog/variant-combination/spus/${id}/drafts`,
  ]) {
    expect((await POST(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  const discard = `catalog/variant-combination/spus/${id}/drafts/${draftId}`;
  expect(
    (await DELETE(request(discard, 'DELETE'), { params: { path: discard.split('/') } })).status,
  ).toBe(200);
});

it('variant-combination 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/variant-combination/unknown',
    `catalog/variant-combination/spus/${id}/drafts/22222222-2222-2222-2222-222222222222/extra`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
