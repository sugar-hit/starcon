import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST, PUT } from './route';
afterEach(() => vi.unstubAllGlobals());
function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/iam/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=real-session',
      'X-Tenant-Id': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}
it('IAM 不暴露商品接口，真实会话仅由 HttpOnly cookie 传递', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  vi.stubGlobal('fetch', fetcher);
  expect(
    (await GET(request('catalog/products'), { params: { path: ['catalog', 'products'] } })).status,
  ).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
  await GET(request('auth/session'), { params: { path: ['auth', 'session'] } });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8081/auth/session'),
    expect.objectContaining({
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer real-session' },
    }),
  );
});
it('登录 token 不返回浏览器脚本，新 cookie 是应用级并删除旧 cookie', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ token: 'secret-token', memberships: [] }))),
  );
  const response = await POST(request('auth/login', 'POST'), {
    params: { path: ['auth', 'login'] },
  });
  expect(await response.json()).toEqual({ memberships: [] });
  expect(response.cookies.get('starcon_session')).toMatchObject({
    value: 'secret-token',
    httpOnly: true,
    sameSite: 'strict',
    path: '/',
  });
  expect(response.cookies.get('starcon_catalog_session')?.value).toBe('');
});
it('认证失效删除所有兼容会话 cookie', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })));
  const response = await GET(request('auth/session'), { params: { path: ['auth', 'session'] } });
  expect(response.cookies.get('starcon_session')?.value).toBe('');
  expect(response.cookies.get('starcon_catalog_session')?.value).toBe('');
});

/**
 * 17.1.1.1 租户列表：identity allowlist 必须放行 tenants 与 tenants/current。
 *
 * <p>同一个坑：allowlist 是锚定正则，没放进分支的路径一律 404，页面只会进错误态，
 * 看起来像「后端没实现」。这里把两个形状钉住。
 *
 * <p>同时钉住**反向**：`tenants/{uuid}` 必须仍然 404。按任意租户 id 读取不是本模块的能力，
 * 可切换集合只能由服务端从成员关系推导，放行了这条等于在浏览器侧开一个按 id 猜租户的口子。
 */
it('identity/tenants 的两个形状被放行，按 uuid 读取仍被拒绝', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of ['identity/tenants', 'identity/tenants/current']) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer real-session' },
      }),
    );
  }
  expect(
    (
      await PUT(request('identity/tenants/current', 'PUT'), {
        params: { path: ['identity', 'tenants', 'current'] },
      })
    ).status,
  ).toBe(200);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [`identity/tenants/${id}`, `identity/tenants/current/${id}`]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalledWith(
    expect.stringContaining(`:8081/identity/tenants/${id}`),
    expect.anything(),
  );
});

/**
 * 17.2.1.2 部门负责人：allowlist 必须放行已实现的四个子资源形状。
 *
 * <p>同一个坑：没放进白名单的路径一律 404，页面只会进错误态，看起来像「后端没实现」。
 *
 * <p>同时钉住**反向**：`leader/unknown` 必须仍然 404。白名单逐段列出而不是一条通配，
 * 就是为了不让「一个还不存在的子路径」看起来像已交付。
 */
it('department-leaders 与 departments/{id}/leader 的四个子资源被放行，未实现的子路径仍被拒绝', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const allowed = [
    'identity/department-leaders',
    `identity/departments/${id}/leader`,
    `identity/departments/${id}/leader/proxies`,
    `identity/departments/${id}/leader/proxies/${id}`,
    `identity/departments/${id}/leader/grants`,
    `identity/departments/${id}/leader/grants/${id}`,
    `identity/departments/${id}/approval-chain`,
  ];
  for (const path of allowed) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.anything(),
    );
  }
  for (const path of [
    `identity/departments/${id}/leader/unknown`,
    `identity/departments/${id}/leader/proxies/${id}/unknown`,
    `identity/departments/${id}/approval-chain/unknown`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
});
