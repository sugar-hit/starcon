import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './route';

/**
 * 17.7.1.2 用量统计的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：漏登记的路径在浏览器里静默 404，而 typecheck / lint /
 * vitest / Playwright **全部全绿**。判据是**真跑 proxy handler 断言 HTTP 状态码**
 * （拦截 = 404，放行 = 转发），不是眼看正则。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**本模块自己的页面源码**（{@code usage-stat-workspace.tsx}）反向抽出
 *       真实调用点，再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，
 *       真正会出事的是「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。</li>
 * </ul>
 *
 * <p>每组断言都配一条**已知可用的控制路径**（{@code identity/tenants}）：白名单整条坏掉时
 * 「全部 404」也会让放行断言变绿，没有控制路径就看不出这一点。
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的（另有车道同时在本文件追加分支），
 * 本车道只**追加**独立元素，绝不收窄既有分支。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../../components/iam/usage-stat-workspace.tsx', import.meta.url)),
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
 * <p>模板字符串只取 {@code ${}} 之前的字面量前缀（模板拼出来的查询参数不参与路径匹配），
 * 再把 {@code ?} 之后的 query string 去掉 —— 代理比对的是 {@code params.path}，不含 search。
 */
function calledPaths(): string[] {
  const paths = new Set<string>();
  const re = /api(?:<[^>]*>)?\(\s*[`'](identity\/saas-usage[^`']*)[`']/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(SOURCE)) !== null) {
    const literal = match[1];
    if (literal === undefined) continue;
    const shape = literal.split('${')[0]?.split('?')[0];
    if (shape) paths.add(shape);
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
    'identity/saas-usage/capabilities',
    'identity/saas-usage/options',
    'identity/saas-usage/report',
    'identity/saas-usage/trend',
    'identity/saas-usage/billing-basis',
    'identity/saas-usage/capacity',
    'identity/saas-usage/coverage',
    'identity/saas-usage/collections',
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
  // 控制路径：白名单整条坏掉时「全 404」也会让上面的断言变绿，这里证明代理本身是通的。
  const control = await GET(request('identity/tenants'), {
    params: Promise.resolve({ path: ['identity', 'tenants'] }),
  });
  expect(control.status, '已知可用的控制路径必须仍然放行').toBe(200);
});

it('写动作也放行（运行归集）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await POST(request('identity/saas-usage/collections', 'POST'), {
    params: Promise.resolve({ path: ['identity', 'saas-usage', 'collections'] }),
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenLastCalledWith(
    expect.stringContaining(':8081/identity/saas-usage/collections'),
    expect.anything(),
  );
});

it('刻意不放行的形状仍然 404', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const denied = [
    // 裸路径：本模块没有裸集合读
    'identity/saas-usage',
    // 非本模块的子路径
    'identity/saas-usage/not-a-shape',
    // 本模块的读出口下面没有更深的一层
    'identity/saas-usage/report/extra',
    // 归集批次没有单条详情端点
    `identity/saas-usage/collections/${ID}`,
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
    'identity/saas-plans/capabilities',
    'identity/saas-plans/plans',
    'identity/tenants',
    'identity/roles',
    'identity/business-parameters',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不得被本模块的分支吃掉`).toBe(200);
  }
});
