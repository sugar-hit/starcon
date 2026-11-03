import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 18.3.2.2 规则效果的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：后端**没有**的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「关掉规则」这个动作在这里已经交付。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/automation/effect/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/automations/automation-rule-effect-model.ts', import.meta.url),
  ),
  'utf8',
);

afterEach(() => vi.unstubAllGlobals());

/**
 * 模板字面量里的 id 占位符替换成**真 UUID 形状**。
 *
 * <p>🔴 不能用 `'id'` 之类的占位：本模块的白名单把 id 段钉死成
 * **完整 UUID 形状**（`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`），
 * 用非 UUID 占位会得到一个**本来就不该放行**的路径，断言会误判成「白名单坏了」。
 */
const UUID_PLACEHOLDER = '3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8';

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

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'platform/automation/effect'），所以抽取先把模板串**按 base 展开**再取路径段 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 *
 * <p>⚠️ 查询串**截掉**（白名单判的是 path，query 由代理单独带上）；
 * ⚠️ `${encodeURIComponent(id)}` 这类占位**展开成真 UUID 形状**。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/automation/effect';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`[^`]*?\$\{base\}([^`]*)`/g)) {
    const tail = match[1];
    if (tail === undefined) continue;
    // ⚠️ tail **自带前导斜杠**（模板是 `${base}/xxx`）⇒ 这里直接拼在 base 后面，
    //    不要再补一个斜杠（补了会变成 `effect//uuid`，那是个**本来就不该放行**的路径）。
    const path = tail
      .split('?')[0]!
      .replace(/\$\{[^}]*\}/g, UUID_PLACEHOLDER)
      .replace(/\/+$/, '');
    found.add(`${base}${path}`);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'platform/automation/effect/capabilities',
      'platform/automation/effect/overview',
      'platform/automation/effect/rules',
      `platform/automation/effect/rules/${UUID_PLACEHOLDER}`,
      'platform/automation/effect/runs',
      'platform/automation/effect/baselines',
      'platform/automation/effect/feedbacks',
      'platform/automation/effect/advices',
      'platform/automation/effect/advices/refresh',
    ]),
  );
});

it('处理反馈与建议的状态端点被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `platform/automation/effect/feedbacks/${UUID_PLACEHOLDER}/status`,
    `platform/automation/effect/advices/${UUID_PLACEHOLDER}/status`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});

it('写动作被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/automation/effect/baselines',
    'platform/automation/effect/feedbacks',
    'platform/automation/effect/advices',
    'platform/automation/effect/advices/refresh',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8089/${path}`),
      expect.objectContaining({ method: 'POST' }),
    );
  }
});

it('后端没有的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 未实现的子路径。
    'platform/automation/effect/unknown',
    // id 段必须是**完整 UUID 形状**：非 UUID 段不该被 uuid 分支吃掉。
    'platform/automation/effect/rules/not-a-uuid',
    // 多一段仍然 404。
    `platform/automation/effect/rules/${UUID_PLACEHOLDER}/not-a-shape`,
    // 🔴 **关闭规则**：归 18.3.1.1 规则档案，本模块**不做**关闭动作。
    //    放行它会让界面以为「在这里就能关掉规则」，而那必然 404。
    `platform/automation/effect/rules/${UUID_PLACEHOLDER}/disable`,
    `platform/automation/effect/rules/${UUID_PLACEHOLDER}/enable`,
    // 裸路径：后端没有「一条不存在的汇总端点」。
    'platform/automation/effect',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已放行的分支（求并，不是二选一）。
 *
 * <p>本车道只新增一条 platform/ services 条目，但白名单是累积型共享文件：并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright
 * 全绿，因为白名单只在浏览器端生效。这里正向钉住每一支的代表形状。
 */
it('不误伤其余服务已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const cases: [string, string][] = [
    ['catalog', 'catalog/kb/overview'],
    ['channel', 'channel/platforms'],
    ['warehouse', 'warehouse/inventory/overview'],
    ['trade', 'trade/processing/orders'],
    ['identity', 'identity/roles'],
    ['crm', 'crm/knowledge/capabilities'],
    ['finance', 'finance/receivables'],
    ['reporting', 'reporting/overview'],
    // 同服务的兄弟模块：首 token 与本模块（automation/effect）不相交，必须仍然放行。
    ['platform.automation-rules', 'platform/automation-rules/capabilities'],
    ['platform.automation-rule-editor', 'platform/automation-rule-editor/capabilities'],
    ['platform.automation-run', 'platform/automation/runs/capabilities'],
    ['platform.auto-approvals', 'platform/auto-approvals/capabilities'],
    ['platform.app', 'platform/apps'],
    ['platform.text', 'platform/text/capabilities'],
    ['platform.finance', 'platform/finance-software/capabilities'],
    ['platform.webhooks', 'platform/webhooks/capabilities'],
    ['platform.deliveries', 'platform/deliveries'],
    ['platform.api-catalog', 'platform/api-catalog/capabilities'],
    ['platform.io', 'platform/io/capabilities'],
    ['platform.data-migration', 'platform/data-migration/capabilities'],
    ['platform.external-integrations', 'platform/external-integrations/capabilities'],
    ['platform.call-monitor', 'platform/call-monitor/capabilities'],
    ['platform.payment-funds', 'platform/payment-funds/capabilities'],
  ];
  for (const [service, path] of cases) {
    const { proxy } = await import('../../../lib/api-proxy');
    const req = new NextRequest(`http://localhost:3000/api/${path}`, {
      headers: { cookie: 'starcon_session=x' },
    });
    const response = await proxy(req, {
      params: Promise.resolve({ path: path.split('/') }),
    } as never);
    expect(response.status, `${service} 的 ${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = 'platform/automation/effect/feedbacks';
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await GET(request('platform/automation/effect/capabilities'), {
    params: Promise.resolve({ path: 'platform/automation/effect/capabilities'.split('/') }),
  });

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
