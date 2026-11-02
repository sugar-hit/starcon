import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';
import { entriesBySource, matchingEntries, proxyEntries } from '../../../lib/api-proxy-entries';

/**
 * 18.4.2.3 异常诊断 的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「已经能改价 / 能补货 / 能停广告」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/ai/diagnosis/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红 ——
 * 本仓的 {@code ai} 命名空间是兄弟车道共用的（18.4.2.2 的 {@code ai/forecast}、
 * 18.4.3.1 的 {@code ai/usage}、18.5.1.1 的 {@code ai/templates}），
 * 它们交付后本断言就会变红。
 *
 * <p>⚠️ 同批其它车道与本模块**共用首 token {@code ai}** ⇒ 这里额外断言本车道的前缀**不吃**它们。
 * ⚠️ **这条断言在正则层做**（见该用例的注释）：在 HTTP 层它**不可表达** ——
 * 兄弟并入前它们的形状是 404、并入后是 200，同一语义状态码含义相反，
 * 拿状态码写就等于把断言绑死在「兄弟还没并入」这个分支拓扑上，并入即红。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/ai/ai-diagnosis-model.ts', import.meta.url)),
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
 * 'platform/ai/diagnosis'），所以抽取先把模板串**按 base 展开**再取路径段 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 *
 * <p>⚠️ 路径参数占位符用**完整 UUID 形状** —— 白名单的 id 段要求的就是那个形状，
 * 用别的东西会被判成 404（那会是一条**假红**）。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/ai/diagnosis';
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
      'platform/ai/diagnosis/capabilities',
      'platform/ai/diagnosis/overview',
      'platform/ai/diagnosis/events',
      'platform/ai/diagnosis/events/sync',
      'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
      'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/diagnose',
      'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/resolve',
      'platform/ai/diagnosis/runs/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
    ]),
  );
});

it('写动作被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/ai/diagnosis/events',
    'platform/ai/diagnosis/events/sync',
    'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/diagnose',
    'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/resolve',
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
    'platform/ai/diagnosis',
    // 未实现的子路径。
    'platform/ai/diagnosis/unknown',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/ai/diagnosis/events/not-a-uuid',
    'platform/ai/diagnosis/runs/not-a-uuid',
    // 多一段仍然 404。
    'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/extra',
    // 🔴 **执行处置动作**：补货归 6 采购、调价归 5 定价、停广告归 14 广告，
    //    本模块**只给建议、不执行**。放行它会让界面以为「点一下就能改价」。
    'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/execute',
    // 本模块没有「批量诊断」这回事。
    'platform/ai/diagnosis/events/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/diagnose/extra',
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
    // 同服务的兄弟模块：首 token 与本模块（ai/diagnosis）不相交，必须仍然放行。
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
 * 同批兄弟车道（18.4.2.2 / 18.4.3.1 / 18.5.1.1）与本模块**共用首 token `ai`**。
 *
 * <p>它们不在本分支上（并入后才存在），所以这里断言的是**形状互斥**：
 * 本车道的前缀**不吃** `ai/forecast` / `ai/usage` / `ai/templates`，
 * 而 `ai/diagnosis` 的形状也不该被它们那三条误吃（并入时必须求并）。
 */
it('同批兄弟车道的 ai 前缀互不误吃 —— 在正则层断言，与兄弟是否已并入无关', () => {
  const all = proxyEntries();
  const mine = entriesBySource(all, '^ai\\/diagnosis');
  expect(mine.length, '本模块应当恰好有一条独立条目').toBe(1);
  const minePattern = mine[0]!.pattern;

  // 🔴 **为什么这条断言必须在正则层做，不能用 HTTP 状态码做**：
  //   「本车道的条目不吃兄弟的形状」这条语义，在 HTTP 层是**不可表达**的 ——
  //   兄弟车道并入**之前**，兄弟的形状没有被任何条目放行 ⇒ 404；
  //   兄弟车道并入**之后**，它被**兄弟自己的条目**放行 ⇒ 200。
  //   同一个语义，并入前后 HTTP 状态码**含义相反** ⇒ 拿状态码写这条断言，
  //   等于把断言绑死在一个分支拓扑上：并入的那一刻它必红，而红的原因与本车道无关。
  //   正则层才是这条语义的**恒定**表达：无论兄弟在不在，本车道的 pattern 都不匹配它们。
  for (const path of [
    'platform/ai/forecast/capabilities',
    'platform/ai/forecast/overview',
    'platform/ai/usage/capabilities',
    'platform/ai/usage/quotas/check',
    'platform/ai/templates',
    'platform/ai/templates/capabilities',
  ]) {
    expect(
      minePattern.test(path.slice('platform/'.length)),
      `${path} 不该被本车道的 ai/diagnosis 条目吃掉`,
    ).toBe(false);
  }

  // 并入后（兄弟已交付）它们必须**各自被兄弟自己那一条**放行，且**恰好一条** ——
  // 这条断言钉住「求并，不是二选一」，也证明本车道没有把它们**遮蔽**掉。
  for (const path of [
    'platform/ai/forecast/capabilities',
    'platform/ai/usage/capabilities',
    'platform/ai/templates/capabilities',
  ]) {
    const matched = matchingEntries(path, all);
    expect(matched.length, `${path} 应当恰好被一条放行`).toBe(1);
    expect(matched[0]?.pattern.test(path.slice('platform/'.length)), `${path} 应当仍被放行`).toBe(
      true,
    );
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = 'platform/ai/diagnosis/events/sync';
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
