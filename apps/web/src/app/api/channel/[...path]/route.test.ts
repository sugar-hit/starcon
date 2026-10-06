import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST } from './route';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/channel/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=real-session',
      'X-Tenant-Id': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

const OVERRIDE_ID = '3f1b1f0e-6a2b-4c1d-8e9f-0a1b2c3d4e5f';

it('枚举映射的读写路径都能到渠道服务，会话只认 HttpOnly cookie', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('[]'));
  vi.stubGlobal('fetch', fetcher);

  await GET(request('platforms/AMAZON/enums'), { params: { path: ['channel', 'platforms', 'AMAZON', 'enums'] } });
  expect(fetcher).toHaveBeenLastCalledWith(
    expect.stringContaining(':8083/channel/platforms/AMAZON/enums'),
    expect.objectContaining({
      method: 'GET',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer real-session' },
    }),
  );

  await POST(request('platforms/AMAZON/enums', 'POST'), {
    params: { path: ['channel', 'platforms', 'AMAZON', 'enums'] },
  });
  expect(fetcher).toHaveBeenLastCalledWith(
    expect.stringContaining(':8083/channel/platforms/AMAZON/enums'),
    expect.objectContaining({ method: 'POST' }),
  );

  await DELETE(request(`platforms/AMAZON/enums/${OVERRIDE_ID}`, 'DELETE'), {
    params: { path: ['channel', 'platforms', 'AMAZON', 'enums', OVERRIDE_ID] },
  });
  expect(fetcher).toHaveBeenLastCalledWith(
    expect.stringContaining(`:8083/channel/platforms/AMAZON/enums/${OVERRIDE_ID}`),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('渠道服务以外的路径不转发，未知路径必须 404', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  vi.stubGlobal('fetch', fetcher);

  // 多余路径段（枚举映射下只允许再挂一段 id）
  const deep = await GET(request(`platforms/AMAZON/enums/${OVERRIDE_ID}/extra`), {
    params: { path: ['channel', 'platforms', 'AMAZON', 'enums', OVERRIDE_ID, 'extra'] },
  });
  expect(deep.status).toBe(404);
  // 平台码不接受路径穿越式片段
  const traversal = await GET(request('platforms/AMAZON%2F..%2Fsecrets/enums'), {
    params: { path: ['channel', 'platforms', 'AMAZON%2F..%2Fsecrets', 'enums'] },
  });
  expect(traversal.status).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

it('授权向导的路径都能到渠道服务，平台回调按浏览器导航放行', async () => {
  // 每次调用都要一个新的 Response：body 只能读一次，复用同一个会让第二次之后走进 catch 分支
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const SESSION_ID = '9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d';
  const cases: readonly [string, string, string[]][] = [
    ['POST', 'oauth/sessions', ['channel', 'oauth', 'sessions']],
    ['GET', `oauth/sessions/${SESSION_ID}`, ['channel', 'oauth', 'sessions', SESSION_ID]],
    ['GET', `oauth/sessions/${SESSION_ID}/shops`, ['channel', 'oauth', 'sessions', SESSION_ID, 'shops']],
    ['POST', `oauth/sessions/${SESSION_ID}/confirm`, ['channel', 'oauth', 'sessions', SESSION_ID, 'confirm']],
    ['POST', `oauth/sessions/${SESSION_ID}/cancel`, ['channel', 'oauth', 'sessions', SESSION_ID, 'cancel']],
    ['GET', 'oauth/callback?code=abc&state=xyz', ['channel', 'oauth', 'callback']],
  ];
  for (const [method, path, segments] of cases) {
    const response =
      method === 'GET'
        ? await GET(request(path), { params: { path: segments } })
        : await POST(request(path, method), { params: { path: segments } });
    expect(response.status, `${method} ${path}`).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8083/channel/${path.split('?')[0]}`),
      expect.objectContaining({ method, headers: expect.objectContaining({ Authorization: 'Bearer real-session' }) }),
    );
  }
});

it('授权路径不允许多余段，回调的伪造租户头照常被丢弃', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  vi.stubGlobal('fetch', fetcher);

  const SESSION_ID = '9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d';
  const deep = await GET(request(`oauth/sessions/${SESSION_ID}/shops/extra`), {
    params: { path: ['channel', 'oauth', 'sessions', SESSION_ID, 'shops', 'extra'] },
  });
  expect(deep.status).toBe(404);

  await GET(request('oauth/callback'), { params: { path: ['channel', 'oauth', 'callback'] } });
  // 租户永远来自 identity 解析出的成员关系，请求头里那个伪造值不该出现在上游请求里
  const [, init] = fetcher.mock.calls.at(-1) as [string, RequestInit];
  expect(Object.keys(init.headers as Record<string, string>)).toEqual(['Content-Type', 'Authorization']);
});
