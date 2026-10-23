import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 12.1.1.3 坏账与核销的 api-proxy 白名单**双向**断言。
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
 * <p>🔴 负向样本**全部取自本模块自己的前缀** `finance/bad-debt/...`：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训 —— 那个前缀在同批里已经交付，
 * 断言它 404 就是**假红**。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 finance/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/finance/bad-debt-model.ts', import.meta.url)),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'finance/bad-debt'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'finance/bad-debt';
  // ⚠️ 路径里的 id 段必须换成**真 UUID**：换成 'X' 会抽出「finance/bad-debt/X」这种
  //   非 UUID 形状，然后断言它被放行 —— 而它本来就**不该**被放行，
  //   断言会变成「白名单要放行一个不存在的形状」，是自欺欺人。
  const uuid = '00000000-0000-4000-8000-000000000001';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, uuid)
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
      'finance/bad-debt',
      'finance/bad-debt/capabilities',
      'finance/bad-debt/options',
      'finance/bad-debt/summary',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/evidence',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/evidence/00000000-0000-4000-8000-000000000001/remove',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/submit',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/approve',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/reject',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/write-off',
      'finance/bad-debt/00000000-0000-4000-8000-000000000001/void',
    ]),
  );
});

it('写动作被放行，且真的转发到 8087', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const id = '00000000-0000-4000-8000-000000000001';
  const response = await POST(request(`finance/bad-debt/${id}/approve`, 'POST'), {
    params: Promise.resolve({ path: `finance/bad-debt/${id}/approve`.split('/') }),
  });
  expect(response.status, 'POST approve 未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8087/finance/bad-debt/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('移除依据材料的动作被放行（POST · 软移除）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const id = '00000000-0000-4000-8000-000000000001';
  const evidenceId = '00000000-0000-4000-8000-000000000002';
  const path = `finance/bad-debt/${id}/evidence/${evidenceId}/remove`;
  const response = await POST(request(path, 'POST'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, 'POST evidence/remove 未被放行').toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '00000000-0000-4000-8000-000000000001';
  for (const path of [
    // 🔴 负向样本**只用本模块自己的前缀**（`finance/bad-debt/...`）：
    //   「批量一键核销」蓝图明令禁止；「核销（settle）」的写者恒为 12.2.1.2；
    //   「凭证（ledger）」属 12.5.2.1 —— **全部未交付**。
    //   放行这些形状会让界面以为「这些功能已经可用」。
    'finance/bad-debt/batch-write-off',
    'finance/bad-debt/batch',
    `finance/bad-debt/${id}/settle`,
    `finance/bad-debt/${id}/ledger`,
    `finance/bad-debt/${id}/evidence/${id}/upload`,
    // ⚠️ **裸路径 `finance/bad-debt` 是真形状**（列表 GET / 建单 POST），不能当负向样本。
    //   负向样本只用「本模块前缀 + 后端真的没有的子路径」。
    // 非 UUID 段不应被 uuid 分支吃掉。
    'finance/bad-debt/not-a-uuid',
    // 逐档端点不存在。
    `finance/bad-debt/${id}/evidence/not-a-uuid`,
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
 * <p>本车道只新增一条 finance/ services 条目，但白名单是累积型共享文件：并入时若取错一侧，
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
    // 卷 12 的其它三个模块：同一个 `finance/` 前缀下的**另一条**分支。
    ['finance', 'finance/receivables'],
    ['finance', 'finance/receivables/aging'],
    ['finance', 'finance/receivable-aging/distribution'],
    ['finance', 'finance/receivable-aging/buckets'],
    ['finance', 'finance/payables'],
    ['finance', 'finance/receipts'],
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
  const path = 'finance/bad-debt/00000000-0000-4000-8000-000000000001/approve';
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
