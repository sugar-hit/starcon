import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST } from './route';
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
