import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 15.4.2.1 客服知识库的 api-proxy 白名单**双向**断言。
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
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件，
 * 且 catalog / warehouse 那两支有两层 alternation（内层分组 + 外层仓库级段）。
 * 本车道只**新增**一条 crm/ services 条目，不动既有六条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/crm/knowledge-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'crm/knowledge'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'crm/knowledge';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值（`${xxxQuery(params)}`）整段去掉 —— 它不是路径的一段。
      // 不先去掉的话 `${knowledgeEntryQuery(params)}` 会被后面的 id 替换规则
      // 变成一个 uuid，拼出 `entries/effective<uuid>` 这种**源码里并不存在**的形状，
      // 然后断言它「未被放行」——测试自己造出一个假失败。
      .replace(/\$\{[A-Za-z0-9_]*[Qq]uery\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
  // **裸 base 本身要排除**：它是前缀常量而不是端点，后端也没有裸路径这个映射
  // （下面有一条用例专门断言它被拒）。
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
      'crm/knowledge/capabilities',
      'crm/knowledge/scopes',
      'crm/knowledge/entries',
      'crm/knowledge/entries/effective',
      `crm/knowledge/entries/${ID}`,
      `crm/knowledge/entries/${ID}/versions`,
      `crm/knowledge/entries/${ID}/references`,
      `crm/knowledge/versions/${ID}/publish`,
      `crm/knowledge/versions/${ID}/close`,
      `crm/knowledge/versions/${ID}/references`,
    ]),
  );
});

it('review-due 被放行（查询串由模型拼在路径后面，代理只对路径部分判别）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // Next 的 [...path] 参数**不含**查询串 —— 白名单正则判别的永远是纯路径。
  // 这里照真实运行时构造：URL 带查询串，params.path 只给路径段。
  const path = 'crm/knowledge/review-due';
  const url =
    `http://localhost:3000/api/crm/${path}` +
    '?platformCode=AMAZON&policyChangedAt=2026-06-01T00%3A00%3A00%2B08%3A00';
  const req = new NextRequest(url, {
    headers: { cookie: 'starcon_session=private-session' },
  });
  const response = await GET(req, { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, 'review-due 未放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/crm/knowledge/review-due?platformCode=AMAZON'),
    expect.anything(),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8086', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'crm/knowledge/entries',
    `crm/knowledge/entries/${ID}/versions`,
    `crm/knowledge/versions/${ID}/publish`,
    `crm/knowledge/versions/${ID}/close`,
    `crm/knowledge/versions/${ID}/references`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8086/crm/knowledge/entries'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 同卷但未开发的模块：15.1.1.1 消息收件箱、15.4.1.1 工单、15.5.1.1 自动回复。
    'crm/knowledge/messages',
    'crm/knowledge/tickets',
    'crm/knowledge/auto-replies',
    // 裸路径：后端没有「一条不存在的列表接口」。
    'crm/knowledge',
    // 字面量段刻意不接 id 段（能力声明与聚合查询不按 id 寻址）。
    `crm/knowledge/scopes/${ID}`,
    `crm/knowledge/review-due/${ID}`,
    // 后端没有「按 id 取单条版本」这个端点。
    `crm/knowledge/versions/${ID}`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'crm/knowledge/entries/not-a-uuid',
    `crm/knowledge/entries/not-a-uuid/versions`,
    // 多一段：动作后缀各只认一个。
    `crm/knowledge/entries/${ID}/versions/publish`,
    `crm/knowledge/versions/${ID}/withdraw`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'crm/knowledge/entry',
    'crm/knowledge/scope',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
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
  const path = `crm/knowledge/versions/${ID}/publish`;
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
