import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 18.4.2.2 选品与需求预测 的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「已经能改价 / 能下单」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/ai/forecast/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 *
 * <p>⚠️ 同批另两条车道（18.4.3.1 的 {@code ai/usage}、18.5.1.1 的 {@code ai/templates}）
 * 与本模块**共用首 token {@code ai}** ⇒ 这里额外断言本车道的前缀**不吃**它们，
 * 且它们**不会**吃掉本车道的路径（求并，不是二选一）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/ai/ai-forecast-model.ts', import.meta.url)),
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
 * 'platform/ai/forecast'），所以抽取先把模板串**按 base 展开**再取路径段 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 *
 * <p>⚠️ 路径参数占位符用**完整 UUID 形状** —— 白名单的 id 段要求的就是那个形状，
 * 用别的东西会被判成 404（那会是一条**假红**）。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/ai/forecast';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`[^`]*?\$\{base\}\/([^`?]*)/g)) {
    const tail = match[1];
    if (!tail) continue;
    found.add(`${base}/${tail.replace(/\$\{[^}]*\}/g, '3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d')}`);
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
      'platform/ai/forecast/capabilities',
      'platform/ai/forecast/overview',
      'platform/ai/forecast/promotions',
      'platform/ai/forecast/history',
      'platform/ai/forecast/history/sync',
      'platform/ai/forecast/runs',
      'platform/ai/forecast/forecasts',
      'platform/ai/forecast/replenish-suggestions',
      'platform/ai/forecast/price-elasticity',
      'platform/ai/forecast/hot-items',
      'platform/ai/forecast/opportunities',
    ]),
  );
});

it('写动作被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/ai/forecast/promotions',
    'platform/ai/forecast/history',
    'platform/ai/forecast/history/sync',
    'platform/ai/forecast/runs',
    'platform/ai/forecast/suggestions/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/adopt',
    'platform/ai/forecast/suggestions/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/reject',
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
    'platform/ai/forecast',
    // 未实现的子路径。
    'platform/ai/forecast/unknown',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/ai/forecast/promotions/not-a-uuid',
    'platform/ai/forecast/forecasts/not-a-uuid',
    // 多一段仍然 404。
    'platform/ai/forecast/history/sync/extra',
    // 🔴 **执行**（改价 / 建补货单）：归 5 定价与 6 采购，**本模块不执行**。
    //    放行它会让界面以为「点一下就能改价」。
    'platform/ai/forecast/execute',
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
    // 同服务的兄弟模块：首 token 与本模块（ai/forecast）不相交，必须仍然放行。
    ['platform.automation-rules', 'platform/automation-rules/capabilities'],
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
    ['platform.automation-rule-editor', 'platform/automation-rule-editor/capabilities'],
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

/**
 * 同批兄弟车道（18.4.3.1 / 18.5.1.1）与本模块**共用首 token `ai`**。
 *
 * <p>它们不在本分支上（并入后才存在），所以这里断言的是**形状互斥**：
 * 本车道的前缀**不吃** `ai/usage` 与 `ai/templates`，
 * 而 `ai/forecast` 的形状也不该被它们那两条误吃（并入时必须求并）。
 */
it('同批兄弟车道的 ai 前缀互不误吃', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['platform/ai/usage/capabilities', 'platform/ai/templates']) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    // 本分支上还没有那两条 ⇒ 404 是**正确**的；并入后它们会被各自的条目放行。
    // 这里断言的是「本车道的正则没有把它们误吃」。
    expect(response.status, `${path} 被本车道的 ai/forecast 条目误放行了`).toBe(404);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = 'platform/ai/forecast/runs';
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
