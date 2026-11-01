import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 18.3.1.2 规则编辑器的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「已经能执行规则」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/automation-rule-editor/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/automations/automation-rule-editor-model.ts', import.meta.url),
  ),
  'utf8',
);

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

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'platform/automation-rule-editor'），所以抽取先把模板串**按 base 展开**再取路径段 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 *
 * <p>⚠️ 路径参数占位符用**点分事件类型**（{@code order.created}）而不是 UUID ——
 * 事件注册表里登记的事件类型字面量都是点分的，用 UUID 当占位符会被
 * 「至少一个点」的形状要求判成 404（那会是一条**假红**）。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/automation-rule-editor';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`[^`]*?\$\{base\}\/([^`?]*)/g)) {
    const tail = match[1];
    if (!tail) continue;
    found.add(`${base}/${tail.replace(/\$\{[^}]*\}/g, 'order.created')}`);
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
      'platform/automation-rule-editor/capabilities',
      'platform/automation-rule-editor/event-schemas',
      'platform/automation-rule-editor/event-schemas/order.created',
      'platform/automation-rule-editor/action-catalog',
      'platform/automation-rule-editor/api-catalog',
      'platform/automation-rule-editor/save',
      'platform/automation-rule-editor/test',
      'platform/automation-rule-editor/test-runs',
    ]),
  );
});

it('写动作被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/automation-rule-editor/save',
    'platform/automation-rule-editor/test',
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
    // 裸路径：后端**没有**「一条不存在的汇总端点」。
    'platform/automation-rule-editor',
    // 未实现的子路径。
    'platform/automation-rule-editor/unknown',
    // 🔴 **执行规则**：18.3.2 执行引擎**尚未交付** ⇒ 后端**没有**这个端点。
    //    放行它会让界面以为「点一下就能跑规则」，而那必然 404。
    'platform/automation-rule-editor/execute',
    // 多一段仍然 404。
    'platform/automation-rule-editor/test-runs/extra',
    // 事件类型是**点分**的，这一段不是事件类型。
    'platform/automation-rule-editor/event-schemas/unknown',
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
    // 同服务的兄弟模块：首 token 与本模块（automation-rule-editor）不相交，必须仍然放行。
    ['platform.automation-rules', 'platform/automation-rules/capabilities'],
    ['platform.app', 'platform/apps'],
    ['platform.text', 'platform/text/capabilities'],
    ['platform.finance', 'platform/finance-software/capabilities'],
    ['platform.webhooks', 'platform/webhooks/capabilities'],
    ['platform.deliveries', 'platform/deliveries'],
    ['platform.api-catalog', 'platform/api-catalog/capabilities'],
    ['platform.io', 'platform/io/capabilities'],
    ['platform.data-migration', 'platform/data-migration/capabilities'],
    ['platform.external-integrations', 'platform/external-integrations/capabilities'],
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
  const path = 'platform/automation-rule-editor/save';
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
