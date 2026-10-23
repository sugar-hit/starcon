import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './route';

/**
 * 17.7.2.2 平台运维视图的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：漏登记的路径在浏览器里静默 404，而 typecheck / lint /
 * vitest / Playwright **全部全绿**。判据是**真跑 proxy handler 断言 HTTP 状态码**
 * （拦截 = 404，放行 = 转发），不是眼看正则 —— 本仓调度者两版静态扫描脚本都给出过
 * 错误结论。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**本模块自己的页面源码**（{@code platform-ops-workspace.tsx}）
 *       反向抽出真实调用点，再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单
 *       一致」，真正会出事的是「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。尤其是
 *       {@code identity/platform-ops/ingest/metrics} 与
 *       {@code identity/platform-ops/roles} —— 那是**服务端到服务端**的内部密钥路径
 *       （要 {@code X-Internal-Key}），浏览器放行它们等于让任意租户往别的租户写指标、
 *       或把自己升级成平台运营方。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**独立元素，
 * 绝不收窄既有分支。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../../components/iam/platform-ops-workspace.tsx', import.meta.url)),
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

/** 路由参数里只有路径段，查询串不在其中。 */
function segments(path: string) {
  return path.split('?')[0]!.split('/');
}

/**
 * 从页面源码里反向抽出**真实存在**的调用点。
 *
 * <p>只抽字面量路径；模板字符串拼出来的路径（带 {uuid} / {metricCode}）由下面的
 * 「本模块实现过的形状逐条被放行」显式列出 —— 那样更清楚，也不会因为模板写法变化
 * 而漏抽。
 */
function calledPaths(): string[] {
  const paths = new Set<string>();
  const re = /api(?:<[^>]*>)?\(\s*[`'](identity\/platform-ops[^`']*)[`']/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(SOURCE)) !== null) {
    const literal = match[1];
    if (literal === undefined) continue;
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
      params: Promise.resolve({ path: segments(path) }),
    });
    expect(response.status, `页面真的调用的 ${path} 必须被放行`).toBe(200);
  }
});

it('本模块实现过的形状逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const shapes = [
    'identity/platform-ops/capabilities',
    'identity/platform-ops/overview',
    'identity/platform-ops/tenants',
    'identity/platform-ops/metrics',
    'identity/platform-ops/alerts',
    'identity/platform-ops/thresholds',
    'identity/platform-ops/thresholds/ERROR_RATE',
    `identity/platform-ops/alerts/${ID}/acknowledge`,
    `identity/platform-ops/alerts/${ID}/resolve`,
    'identity/platform-ops/self/metrics',
  ];
  for (const path of shapes) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: segments(path) }),
    });
    expect(response.status, `${path} 必须被放行`).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.anything(),
    );
  }
});

it('写动作也放行（确认告警、闭环告警、改阈值）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const acknowledged = await POST(
    request(`identity/platform-ops/alerts/${ID}/acknowledge`, 'POST'),
    { params: Promise.resolve({ path: segments(`identity/platform-ops/alerts/${ID}/acknowledge`) }) },
  );
  expect(acknowledged.status).toBe(200);
  const resolved = await POST(request(`identity/platform-ops/alerts/${ID}/resolve`, 'POST'), {
    params: Promise.resolve({ path: segments(`identity/platform-ops/alerts/${ID}/resolve`) }),
  });
  expect(resolved.status).toBe(200);
  const updated = await PUT(request('identity/platform-ops/thresholds/ERROR_RATE', 'PUT'), {
    params: Promise.resolve({ path: segments('identity/platform-ops/thresholds/ERROR_RATE') }),
  });
  expect(updated.status).toBe(200);
});

it('刻意不放行的形状仍然 404', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const denied = [
    // 裸路径：本模块没有裸集合读
    'identity/platform-ops',
    // 🔴 指标接入是服务端到服务端的内部密钥路径：浏览器放行它等于让任意租户
    //    往别的租户写指标。
    'identity/platform-ops/ingest/metrics',
    // 🔴 平台运营角色登记同样是内部密钥路径：浏览器放行它等于让任意租户
    //    把自己升级成平台运营方。
    'identity/platform-ops/roles',
    // 指标没有按 id 取单条的端点
    `identity/platform-ops/metrics/${ID}`,
    // 告警只有「确认 / 闭环」两种子动作
    `identity/platform-ops/alerts/${ID}`,
    `identity/platform-ops/alerts/${ID}/delete`,
    // 逐租户健康摘要只有列表
    `identity/platform-ops/tenants/${ID}`,
    // 非 UUID / 非大写标识符的段
    'identity/platform-ops/not-a-shape',
    'identity/platform-ops/thresholds/error_rate',
  ];
  for (const path of denied) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: segments(path) }),
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
    'identity/platform-flags/settings',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: segments(path) }),
    });
    expect(response.status, `${path} 不得被本模块的分支吃掉`).toBe(200);
  }
});

it('放行时只转发会话令牌，不转发伪造的租户头与内部密钥', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  await GET(request('identity/platform-ops/overview'), {
    params: Promise.resolve({ path: ['identity', 'platform-ops', 'overview'] }),
  });
  const [, init] = fetcher.mock.calls[0]! as [string, RequestInit];
  const headers = init.headers as Record<string, string>;
  expect(headers['X-Internal-Key']).toBeUndefined();
  expect(headers['X-Tenant-Id']).toBeUndefined();
  expect(headers['Authorization']).toContain('Bearer ');
});
