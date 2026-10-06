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
