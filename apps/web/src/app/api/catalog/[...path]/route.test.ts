import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST, PUT } from './route';
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
