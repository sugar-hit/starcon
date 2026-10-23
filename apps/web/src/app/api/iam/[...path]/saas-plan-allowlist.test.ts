import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './route';

/**
 * 17.7.1.1 套餐与权益的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：漏登记的路径在浏览器里静默 404，而 typecheck / lint /
 * vitest / Playwright **全部全绿**。判据是**真跑 proxy handler 断言 HTTP 状态码**
 * （拦截 = 404，放行 = 转发），不是眼看正则。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**本模块自己的页面源码**（{@code saas-plan-workspace.tsx}）反向抽出
 *       真实调用点，再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，
 *       真正会出事的是「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。尤其是
 *       {@code identity/saas-platform/roles}——那是**平台运营角色登记**的专用路径
 *       （服务端到服务端，要内部密钥），浏览器放行它等于让任意租户把自己升级成平台运营方，
 *       「租户不可访问」当场失效。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的（另有三条车道同时在本文件追加分支），
 * 本车道只**追加**独立元素，绝不收窄既有分支。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../../components/iam/saas-plan-workspace.tsx', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

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

/**
 * 从页面源码里反向抽出**真实存在**的调用点。
 *
 * <p>只抽字面量路径；模板字符串拼出来的路径（带 {uuid} / {code}）由下面的
 * 「白名单必须放行这些形状」显式列出——那样更清楚，也不会因为模板写法变化而漏抽。
 */
function calledPaths(): string[] {
  const paths = new Set<string>();
  const re = /api(?:<[^>]*>)?\(\s*[`'](identity\/saas-plans[^`']*)[`']/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(SOURCE)) !== null) {
    const literal = match[1];
    if (literal === undefined) continue;
    // 模板字符串（含 ${...}）不是可直接断言的形状，它们由下面的「本模块实现过的形状逐条被放行」
    // 显式列出——那里把 {code} / {uuid} 换成了真实取值。
    if (!literal.includes('${')) paths.add(literal);
  }
  return [...paths];
}

it('页面源码里真实存在的调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const paths = calledPaths();
  // 抽取条数 > 0：抽取器坏掉时静默全绿是最危险的失败模式。
  expect(paths.length).toBeGreaterThan(0);
  for (const path of paths) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `页面真的调用的 ${path} 必须被放行`).toBe(200);
  }
});

it('本模块实现过的形状逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const shapes = [
    'identity/saas-plans/capabilities',
    'identity/saas-plans/options',
    'identity/saas-plans/plans',
    'identity/saas-plans/plans/STARTER',
    'identity/saas-plans/plans/STARTER/entitlements',
    'identity/saas-plans/plans/STARTER/entitlements/SKU',
    'identity/saas-plans/subscriptions',
    `identity/saas-plans/subscriptions/${ID}`,
    `identity/saas-plans/subscriptions/${ID}/status`,
    `identity/saas-plans/subscriptions/${ID}/events`,
    'identity/saas-plans/usage',
    'identity/saas-plans/entitlement-check',
  ];
  for (const path of shapes) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 必须被放行`).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.anything(),
    );
  }
});

it('写动作也放行（开通订阅、处理到期、登记用量）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await POST(request('identity/saas-plans/subscriptions', 'POST'), {
    params: Promise.resolve({ path: ['identity', 'saas-plans', 'subscriptions'] }),
  });
  expect(response.status).toBe(200);
  const expired = await POST(request('identity/saas-plans/subscriptions/expire-due', 'POST'), {
    params: Promise.resolve({
      path: ['identity', 'saas-plans', 'subscriptions', 'expire-due'],
    }),
  });
  expect(expired.status).toBe(200);
  const removed = await DELETE(
    request('identity/saas-plans/plans/STARTER/entitlements/SKU', 'DELETE'),
    {
      params: Promise.resolve({
        path: ['identity', 'saas-plans', 'plans', 'STARTER', 'entitlements', 'SKU'],
      }),
    },
  );
  expect(removed.status).toBe(200);
});

it('刻意不放行的形状仍然 404', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const denied = [
    // 裸路径：本模块没有裸集合读
    'identity/saas-plans',
    // 订阅是事实，不提供物理删除
    `identity/saas-plans/subscriptions/${ID}/delete`,
    // 权益项下面没有更深的一层
    'identity/saas-plans/plans/STARTER/entitlements/SKU/extra',
    // 非 UUID / 非大写标识符的段
    'identity/saas-plans/not-a-shape',
    'identity/saas-plans/plans/starter',
    // 🔴 平台运营角色登记是服务端到服务端的专用路径：浏览器放行它等于让任意租户
    //    把自己升级成平台运营方，「租户不可访问」当场失效。
    'identity/saas-platform/roles',
  ];
  for (const path of denied) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 必须仍然 404`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤既有 identity 形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'identity/tenants',
    'identity/tenants/current',
    'identity/business-parameters',
    'identity/roles',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不得被本模块的分支吃掉`).toBe(200);
  }
});
