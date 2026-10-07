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
 * 17.1.1.3 层级授权：identity allowlist 必须放行授权记录的读、判定与撤销三种形状。
 *
 * <p>判定端点 `check` 的第一段是字面量、`{id}` 那一支是 UUID，两者在正则里靠
 * `/check|\/[0-9a-f-]+` 的先后判别；这里把三种放行与一条反向断言一起钉住，
 * 少一条就等于白名单退化成了前缀匹配。
 *
 * <p>反向断言是 `hierarchy-authorizations/{uuid}/anything`：撤销只有 `/revoke`
 * 一个合法后缀，多一段必须仍然 404，否则代理层就成了一个能往任意子路径发 POST 的通道。
 */
it('identity/hierarchy-authorizations 的读、判定、撤销被放行，多余子路径仍被拒绝', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'identity/hierarchy-authorizations',
    'identity/hierarchy-authorizations/check',
    `identity/hierarchy-authorizations/${id}`,
    `identity/hierarchy-authorizations/${id}/revoke`,
  ]) {
    const method = path.endsWith('/revoke') || path.endsWith('/check') ? 'POST' : 'GET';
    const response =
      method === 'POST'
        ? await POST(request(path, 'POST'), { params: { path: path.split('/') } })
        : await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer real-session' },
      }),
    );
  }
  // 更新授权走 PUT，和读取同一个 {id} 形状。
  expect(
    (
      await PUT(request(`identity/hierarchy-authorizations/${id}`, 'PUT'), {
        params: { path: ['identity', 'hierarchy-authorizations', id] },
      })
    ).status,
  ).toBe(200);
  // 反向：撤销之外的后缀不放行。
  for (const path of [
    `identity/hierarchy-authorizations/${id}/restore`,
    'identity/hierarchy-authorizations/check/extra',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalledWith(expect.stringContaining(`/restore`), expect.anything());
});

/**
 * 17.2.2.3 离职与停用：identity allowlist 必须放行案卷、撤销与两张台账的动作形状。
 *
 * <p>这一支与其他前缀不同：它**没有**删除动作（停用而非删除），所以白名单里也刻意
 * 不放行任何 DELETE 语义路径 —— 少一条比多一条安全，多了等于在浏览器侧开一个删档的口子。
 *
 * <p>四类形状都要钉住：案卷本身、撤销、责任转移（登记/执行转移）、回收（登记/执行回收）。
 * 后两类各有一个动作后缀，多一层必须仍然 404，否则代理会退化成「往任意子路径发 POST」的通道。
 */
it('identity/offboarding 的案卷、撤销与两张台账形状被放行，其余后缀仍被拒绝', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const post = [
    'identity/offboarding',
    `identity/offboarding/${id}/revert`,
    `identity/offboarding/${id}/responsibility-items`,
    `identity/offboarding/${id}/responsibility-items/${id}/transfer`,
    `identity/offboarding/${id}/reclaim-items`,
    `identity/offboarding/${id}/reclaim-items/${id}/reclaim`,
  ];
  for (const path of post) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer real-session' },
      }),
    );
  }
  // 读形状：列表与详情
  for (const path of ['identity/offboarding', `identity/offboarding/${id}`]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  // 反向：动作后缀之外一律 404
  for (const path of [
    `identity/offboarding/${id}/restore`,
    `identity/offboarding/${id}/responsibility-items/${id}/restore`,
    `identity/offboarding/${id}/reclaim-items/${id}/reclaim/extra`,
    `identity/offboarding/${id}/responsibility-items/extra`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalledWith(expect.stringContaining('/restore'), expect.anything());
});
