import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST, PUT } from './iam/[...path]/route';

/**
 * 1.2.2.1 常用页面收藏的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>三个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：本模块真实实现的 3 个形状都要转发；</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 放行后端不存在的映射比 404 更糟，
 *       界面会以为「已经可用」；</li>
 *   <li><b>不误伤</b>：相邻车道已交付的形状必须仍然放行（白名单是累积型文件，
 *       取错一侧就会静默删掉别人的路由）。</li>
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

/**
 * 请求路径按 `lib/api.ts` 的真实口径拼：`/api/iam/` + 服务前缀 + 子路径
 * （identity 服务的浏览器出口挂在 `iam` 路由下，`auth` 与 `identity` 两个首段都走它）。
 */
function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/iam/${path}`, {
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

async function call(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET') {
  const handler =
    method === 'GET' ? GET : method === 'POST' ? POST : method === 'PUT' ? PUT : DELETE;
  return handler(request(path, method), { params: Promise.resolve({ path: path.split('/') }) });
}

it('本模块真实实现的 3 个形状都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'identity/page-favorites',
    'identity/page-favorites/order',
    'identity/page-favorites/hidden',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
});

it('写形状也放行 —— 收藏、排序、取消收藏、清理隐藏项都要能改', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  expect((await call('identity/page-favorites', 'POST')).status).toBe(200);
  expect((await call('identity/page-favorites/order', 'PUT')).status).toBe(200);
  expect((await call('identity/page-favorites', 'DELETE')).status).toBe(200);
  expect((await call('identity/page-favorites/hidden', 'DELETE')).status).toBe(200);
});

it('请求真的转发到 identity 服务端口', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('identity/page-favorites');

  const targets = fetcher.mock.calls.map((call) => String(call[0]));
  expect(targets.some((url) => url.includes(':8081/identity/page-favorites'))).toBe(true);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块只有 order / hidden 两个子路径。
    'identity/page-favorites/not-a-shape',
    // 排序下面没有更深的一层。
    'identity/page-favorites/order/extra',
    // 清理动作没有子资源。
    'identity/page-favorites/hidden/extra',
    // 首 token 拼错一律 404（不放行模糊匹配）。
    'identity/page-favoritesx',
    // 另一套前缀不是本模块的形状。
    'identity/favorite-pages',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本模块的元素一律以 `page-favorites` 开头，其余既有元素的首 token 都与它不相交。
  // 这里把几条紧邻的既有 **identity** 形状一并断言，作为「求并后仍然全在」的证据。
  // ⚠️ 只断言 identity 的形状：本文件走的是 `iam` 出口，它只放行 `auth` 与 `identity`
  // 两个首段（其它服务的前缀在这里本来就 404，与本模块的白名单无关）；
  // 其它服务的形状由全量 allowlist 回归（`pnpm vitest run apps/web/src/app/api`）守着。
  for (const path of [
    'identity/roles',
    'identity/members',
    'identity/departments',
    // 1.2.2.2 快捷入口配置 —— 同一批的兄弟车道。
    'identity/shortcut-templates',
    'identity/shortcut-templates/effective',
    'identity/global-search/recent',
    'identity/saas-plans/plans',
    'identity/platform-flags/settings',
    'identity/platform-ops/overview',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
