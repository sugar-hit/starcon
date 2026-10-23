import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 12.1.2.1 应付单管理的 api-proxy 白名单**双向**断言。
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
 * <p>🔴 负向样本**全部取自本模块自己的前缀**（`finance/payables/...` 与 `finance/prepayments/...`）：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训 —— 那个前缀在同批里已经交付，
 * 断言它 404 就是**假红**。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 finance/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/finance/payable-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/finance/${path}`, {
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base / prepaymentBase 是常量），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const bases = new Map<string, string>();
  for (const match of SOURCE.matchAll(/const (\w+) = '([^']+)'/g)) {
    const name = match[1];
    const value = match[2];
    if (!name || !value) continue;
    if (!value.startsWith('finance/')) continue;
    bases.set(name, value);
  }
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${')) continue;
    let cleaned: string = raw;
    for (const [name, value] of bases) {
      cleaned = cleaned.split('${' + name + '}').join(value);
    }
    cleaned = cleaned
      // 查询串插值（`${query({ ...params })}`）整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      // ⚠️ **枚举段**先于通用替换处理：`policies/{source}` 的段是**来源枚举名**
      //   （MANUAL / OTHER …），不是 uuid。
      .replace(/\$\{encodeURIComponent\(source\)\}/g, 'MANUAL')
      .replace(/\$\{encodeURIComponent\(obligationKind\)\}/g, 'PAYABLE')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (!cleaned.startsWith('finance/')) continue;
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith('finance/')) continue;
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
      'finance/payables/capabilities',
      'finance/payables/options',
      'finance/payables/unpaid',
      'finance/payables',
      `finance/payables/${ID}`,
      'finance/payables/policies',
      'finance/payables/policies/MANUAL',
      'finance/payables/import-purchase-arrivals',
      'finance/payables/import-first-mile',
      'finance/payables/import-ad-spend',
      `finance/payables/${ID}/void`,
      'finance/prepayments',
      `finance/prepayments/${ID}`,
      `finance/prepayments/${ID}/allocations`,
      `finance/prepayments/${ID}/void`,
    ]),
  );
});

it('写动作的 POST / PUT 形状被放行，且真的转发到 8087', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'finance/payables',
    'finance/payables/import-purchase-arrivals',
    'finance/payables/import-first-mile',
    'finance/payables/import-ad-spend',
    `finance/payables/${ID}/void`,
    'finance/prepayments',
    `finance/prepayments/${ID}/allocations`,
    `finance/prepayments/${ID}/void`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const putResponse = await PUT(request(`finance/payables/policies/MANUAL`, 'PUT'), {
    params: Promise.resolve({ path: 'finance/payables/policies/MANUAL'.split('/') }),
  });
  expect(putResponse.status, 'PUT policies/{source} 未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8087/finance/payables/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 负向样本**只用本模块自己的前缀**：
    //   「付款」（12.2.2.1）、「坏账核销」（12.1.1.3）、「发票」（12.6.2.1）、
    //   「供应商对账的财务侧确认」（12.1.2.3）、「平台结算」（12.3.1）
    //   **全部未交付** —— 放行这些形状会让界面以为「这些功能已经可用」。
    `finance/payables/${ID}/settle`,
    `finance/payables/${ID}/write-off`,
    `finance/payables/${ID}/invoice`,
    `finance/payables/${ID}/confirm`,
    'finance/payables/payments',
    'finance/payables/settlements',
    'finance/payables/ledger',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'finance/payables/not-a-uuid',
    `finance/payables/${ID}/not-a-shape`,
    // 字面量段刻意不接 id 段。
    `finance/payables/capabilities/${ID}`,
    `finance/payables/unpaid/${ID}`,
    `finance/payables/policies/${ID}`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'finance/payables/payables',
    'finance/payable',
    // 预付款：撤销抵扣属 12.2.2.1 域（未交付），物理删除一律没有。
    `finance/prepayments/${ID}/revoke-allocation`,
    `finance/prepayments/${ID}/delete`,
    'finance/prepayments/not-a-uuid',
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
 * 全绿，因为白名单只在浏览器端生效。这里正向钉住每一支的代表形状，**含 12.1.1.1 的应收支**。
 */
it('不误伤其余服务已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const cases: [string, string][] = [
    ['catalog', 'catalog/kb/overview'],
    ['channel', 'channel/platforms'],
    ['warehouse', 'warehouse/inventory/overview'],
    ['trade', 'trade/processing/orders'],
    ['trade', 'trade/b2b-orders/receivables'],
    ['identity', 'identity/roles'],
    ['crm', 'crm/knowledge/capabilities'],
    // 12.1.1.1 应收单管理（同一条 finance/ 前缀的另一支）—— 必须仍然放行。
    ['finance', 'finance/receivables'],
    ['finance', 'finance/receivables/capabilities'],
    ['finance', 'finance/receivables/policies'],
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
  const path = `finance/payables/${ID}/void`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/finance/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
