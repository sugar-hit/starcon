import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 15.2.1.3 差评处理的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** `crm/negative-reviews/...`：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训 —— 那些前缀在同批里已经交付，
 * 断言它 404 就是**假红**；更糟的是断言方向搞反时会让「已交付的路由被静默删掉」这个真事故**假绿**。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 crm/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/crm/negative-review-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const ID2 = '22222222-2222-2222-2222-222222222222';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/crm/${path}`, {
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'crm/negative-reviews'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'crm/negative-reviews';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值（`${xxxQuery(params)}`）整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[A-Za-z0-9_]*[Qq]uery\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base) || raw === base) continue;
    found.add(raw);
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
      'crm/negative-reviews',
      'crm/negative-reviews/capabilities',
      'crm/negative-reviews/shops',
      'crm/negative-reviews/stats',
      'crm/negative-reviews/candidates',
      'crm/negative-reviews/rules',
      `crm/negative-reviews/rules/${ID}`,
      `crm/negative-reviews/${ID}`,
      `crm/negative-reviews/${ID}/classify`,
      `crm/negative-reviews/${ID}/actions`,
      `crm/negative-reviews/${ID}/appeals`,
    ]),
  );
});

it('申诉子路径的四个动作都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const action of ['submit', 'submitted', 'outcome', 'cancel']) {
    const path = `crm/negative-reviews/${ID}/appeals/${ID2}/${action}`;
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
});

it('写动作的 POST / PUT 形状被放行，且真的转发到 8086', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['crm/negative-reviews', 'crm/negative-reviews/rules']) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  // 🔴 PUT 是**规则原地改**的入口（阈值 / 申诉配额 / 判定依据都靠它改）——
  //   漏放行它，运营就永远改不了规则，差评口径与申诉判定就永远钉死。
  const putResponse = await PUT(request(`crm/negative-reviews/rules/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `crm/negative-reviews/rules/${ID}`.split('/') }),
  });
  expect(putResponse.status, `PUT crm/negative-reviews/rules/${ID} 未被放行`).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8086/crm/negative-reviews/rules'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 负向样本**只用本模块自己的前缀**（`crm/negative-reviews/...`）：
    //   后端没有「重试申诉」这个映射（撤回 = cancel）。
    `crm/negative-reviews/${ID}/appeals/${ID2}/retry`,
    // 处理单是**事实**，不提供删除形状。
    `crm/negative-reviews/${ID}/delete`,
    // 本模块**没有导出**这个能力（蓝图没要求）。
    'crm/negative-reviews/export',
    // 标签不属本模块。
    `crm/negative-reviews/${ID}/tags`,
    // 字面量段刻意不接 id 段（能力声明不按 id 寻址）。
    `crm/negative-reviews/capabilities/${ID}`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'crm/negative-reviews/not-a-uuid',
    'crm/negative-reviews/rules/not-a-uuid',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 🔴 **11 售后未交付 ⇒ 不放行任何「转售后单据」形状**。
 *
 * <p>下游没交付就没有单据 id；放行一条售后单据路径会让界面以为
 * 「已转售后」真的发生过，而现场一张售后单都没建。
 */
it('11 售后未交付：任何「转售后单据」形状都 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    `crm/negative-reviews/${ID}/after-sales`,
    `crm/negative-reviews/${ID}/after-sales/${ID2}`,
    `crm/negative-reviews/${ID}/handoffs`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行（11 售后未交付）`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 首 token 相近但不同的形状必须 404。
 *
 * <p>🔴 这一格专门盯 `negative-reviews` 与兄弟车道的 `reviews` / `review-replies` /
 * `review-requests` / `review-rules` 互相不吃掉：这四条正则**首 token 都是 `review`**，
 * 而本模块的首 token 是 `negative` —— 它们都是 `^` 锚定的，**不会**吃掉
 * `negative-reviews/...`；反过来 `^negative-reviews...` 也不会吃掉 `reviews/...`。
 */
it('首 token 相近的兄弟车道路由不被本车道吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'crm/reviews',
    `crm/reviews/${ID}`,
    'crm/review-replies',
    'crm/review-requests',
    'crm/review-rules',
    'crm/reviews/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

/**
 * 不误伤其他车道已登记的分支（求并，不是二选一）。
 *
 * <p>本车道只新增一条 services 条目，但白名单是累积型共享文件：并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright
 * 全绿，因为白名单只在浏览器端生效。这里正向钉住每一支的代表形状。
 */
it('不误伤其余服务已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const cases: [string, string][] = [
    ['catalog', 'catalog/kb/overview'],
    ['catalog', 'catalog/bundles/overview'],
    ['channel', 'channel/platforms'],
    ['warehouse', 'warehouse/inventory/overview'],
    ['trade', 'trade/processing/orders'],
    ['identity', 'identity/roles'],
    // 同服务的前几条 crm 车道（15.4.2.1 客服知识库 / 15.2.2.1 索评 / 15.2.2.2 索评规则 /
    // 15.1.2.1 买家邮件 / 15.2.1.1 评价列表 / 15.2.1.2 评价回复）：本车道只**新增**一条元素，
    // 不能把它们的分支吃掉。
    ['crm', 'crm/knowledge/capabilities'],
    ['crm', 'crm/knowledge/entries'],
    ['crm', 'crm/review-requests/capabilities'],
    ['crm', 'crm/review-rules/capabilities'],
    ['crm', 'crm/buyer-emails/capabilities'],
    ['crm', 'crm/reviews/capabilities'],
    ['crm', 'crm/review-replies/capabilities'],
  ];
  for (const [service, path] of cases) {
    const { proxy } = await import('../../../lib/api-proxy');
    // cases 里的 path 已经带了服务前缀（'catalog/kb/overview'），
    // 直接按它分段即可 —— 再 prepend 一次会拼成 ['catalog','catalog','kb',...]
    // 然后报「被误伤」，那是测试自己造出来的假失败。
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
  const path = 'crm/negative-reviews';
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/crm/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
