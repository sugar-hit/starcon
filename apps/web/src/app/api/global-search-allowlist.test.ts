import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST, DELETE } from './catalog/[...path]/route';

/**
 * 1.2.1.1 全局搜索框的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>本模块跨**五个**服务（catalog / trade / channel / warehouse / identity），
 * 所以一条用例把五个前缀都走一遍 —— 全局搜索少放行任何一个前缀，
 * 那个域就会在浏览器里静默消失，而界面看不出是「没权限」还是「没放行」。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：五个服务的检索端点与能力声明端点都要转发。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 四个业务服务的检索端点
 *       <b>只有 GET</b>（工作台只读 + 跳转是蓝图的硬约束），
 *       放行不存在的写端点只会让界面以为「全局搜索能改数据」。</li>
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

async function call(path: string, method: 'GET' | 'POST' | 'DELETE' = 'GET') {
  const handler = method === 'GET' ? GET : method === 'POST' ? POST : DELETE;
  return handler(request(path, method), { params: Promise.resolve({ path: path.split('/') }) });
}

it('五个服务的检索端点与能力声明端点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/global-search',
    'catalog/global-search/capabilities',
    'trade/global-search',
    'trade/global-search/capabilities',
    'channel/global-search',
    'channel/global-search/capabilities',
    'warehouse/global-search',
    'warehouse/global-search/capabilities',
    'identity/global-search/recent',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 最近搜索的登记与清空是 identity 侧仅有的两个写端点。
  expect((await call('identity/global-search/recent', 'POST')).status).toBe(200);
  expect((await call('identity/global-search/recent', 'DELETE')).status).toBe(200);
});

it('检索端点真的转发到各自的服务端口', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('catalog/global-search');
  await call('trade/global-search');
  await call('channel/global-search');
  await call('warehouse/global-search');
  await call('identity/global-search/recent');

  const targets = fetcher.mock.calls.map((call) => String(call[0]));
  expect(targets.some((url) => url.includes(':8082/catalog/global-search'))).toBe(true);
  expect(targets.some((url) => url.includes(':8085/trade/global-search'))).toBe(true);
  expect(targets.some((url) => url.includes(':8083/channel/global-search'))).toBe(true);
  expect(targets.some((url) => url.includes(':8084/warehouse/global-search'))).toBe(true);
  expect(targets.some((url) => url.includes(':8081/identity/global-search/recent'))).toBe(true);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 四个业务服务的检索端点**只有 GET**：放行写端点会让界面以为「全局搜索能改数据」。
    'catalog/global-search/reindex',
    'trade/global-search/reindex',
    'channel/global-search/reindex',
    'warehouse/global-search/reindex',
    // 拼错的子路径：本模块的子路径只有 capabilities 一个。
    'catalog/global-search/summary',
    'trade/global-search/summary',
    // identity 侧只有 recent 一个子路径。
    'identity/global-search',
    'identity/global-search/history',
    'identity/global-search/recent/all',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本模块的五条元素一律以 `global-search` 开头，其余既有元素的首 token 都与它不相交。
  // 这里把几条紧邻的既有形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    'catalog/skus',
    'catalog/parties',
    'channel/shops',
    'trade/purchase-reconciliations/capabilities',
    'auth/session',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
