import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 12.1.2.3 供应商对账的 api-proxy 白名单**双向**断言。
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
 * <p>🔴 负向样本**全部取自本模块自己的前缀**（`finance/supplier-reconciliation/...`）：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训 —— 那个前缀在同批里已经交付，
 * 断言它 404 就是**假红**。
 *
 * <p><b>不误伤其他车道已放行的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 finance/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/finance/supplier-reconciliation-model.ts', import.meta.url),
  ),
  'utf8',
);

/**
 * 后端控制器的路由前缀（真源）。
 *
 * <p>⚠️ 这一条断言是本文件存在的**主要理由**：白名单的首 token 与后端
 * {@code @RequestMapping} 必须**逐字一致**。两边差一个字母（`supplier-reconciliation`
 * vs `supplier-reconciliations`）时，代理剥掉 `finance/` 后拿到的路径与正则永远对不上，
 * 表现是**浏览器里每个请求都 404**，而 typecheck / lint / vitest / Playwright 全绿。
 * 这个坑本车道真的踩过一次，所以钉死。
 */
const CONTROLLER = readFileSync(
  fileURLToPath(
    new URL(
      '../../../../../../backend/services/finance/src/main/java/com/starcon/finance/SupplierReconciliationController.java',
      import.meta.url,
    ),
  ),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量），
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
      'finance/supplier-reconciliation',
      'finance/supplier-reconciliation/capabilities',
      'finance/supplier-reconciliation/options',
      'finance/supplier-reconciliation/import',
      `finance/supplier-reconciliation/${ID}`,
      `finance/supplier-reconciliation/${ID}/lines`,
      `finance/supplier-reconciliation/${ID}/differences`,
      `finance/supplier-reconciliation/${ID}/match`,
      `finance/supplier-reconciliation/${ID}/confirm`,
      `finance/supplier-reconciliation/${ID}/reject`,
      `finance/supplier-reconciliation/${ID}/differences/${ID}/explanations`,
      `finance/supplier-reconciliation/${ID}/differences/${ID}/resolve`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8087', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'finance/supplier-reconciliation/import',
    `finance/supplier-reconciliation/${ID}/match`,
    `finance/supplier-reconciliation/${ID}/confirm`,
    `finance/supplier-reconciliation/${ID}/reject`,
    `finance/supplier-reconciliation/${ID}/differences/${ID}/explanations`,
    `finance/supplier-reconciliation/${ID}/differences/${ID}/resolve`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8087/finance/supplier-reconciliation/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 负向样本**只用本模块自己的前缀**：
    //   「付款执行」（12.2.2.x）与「坏账核销」（12.1.1.3）**未交付** ——
    //   放行这些形状会让界面以为「这些功能已经可用」。
    `finance/supplier-reconciliation/${ID}/settle`,
    `finance/supplier-reconciliation/${ID}/pay`,
    `finance/supplier-reconciliation/${ID}/write-off`,
    `finance/supplier-reconciliation/${ID}/execute`,
    // 本模块的动作只有 import / match / confirm / reject / explanations / resolve。
    `finance/supplier-reconciliation/${ID}/not-a-shape`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'finance/supplier-reconciliation/not-a-uuid',
    `finance/supplier-reconciliation/${ID}/differences/not-a-uuid`,
    // 字面量段刻意不接 id 段。
    `finance/supplier-reconciliation/capabilities/${ID}`,
    `finance/supplier-reconciliation/options/${ID}`,
    `finance/supplier-reconciliation/import/${ID}`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'finance/supplier-reconciliation/reconciliations',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 白名单首 token 必须与后端 {@code @RequestMapping} **逐字一致**。
 *
 * <p>这是本文件最要紧的一条：两边差一个字母时，浏览器里每个请求都静默 404，
 * 而所有静态检查全绿 —— 只有把「后端真源」和「白名单」放在同一个断言里才拦得住。
 */
it('白名单首 token 与后端 @RequestMapping 逐字一致（单复数写错 = 浏览器静默 404）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const mapping = /@RequestMapping\("(\/finance\/[^"]+)"\)/.exec(CONTROLLER);
  expect(mapping, '没读到后端控制器的 @RequestMapping —— 断言本身失效了').not.toBeNull();
  const backendPrefix = mapping?.[1] ?? '';
  expect(backendPrefix).toBe('/finance/supplier-reconciliation');

  // 后端真源去掉 /finance 前缀后，必须正好是白名单放行的首 token。
  const ok = await GET(request('finance/supplier-reconciliation/capabilities'), {
    params: Promise.resolve({ path: 'finance/supplier-reconciliation/capabilities'.split('/') }),
  });
  expect(ok.status).toBe(200);

  // 反向钉住：复数写法**必须**被拒（后端没有这个路由，放行它只会让界面以为已交付）。
  const plural = await GET(request('finance/supplier-reconciliations/capabilities'), {
    params: Promise.resolve({ path: 'finance/supplier-reconciliations/capabilities'.split('/') }),
  });
  expect(plural.status, '复数前缀与后端路由不一致，必须 404').toBe(404);
});

/**
 * 不误伤其他车道已放行的分支（求并，不是二选一）。
 *
 * <p>本车道只新增一条 finance/ services 条目，但白名单是累积型共享文件：并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright
 * 全绿，因为白名单只在浏览器端生效。这里正向钉住每一支的代表形状，**含同服务其余各支**。
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
    // 同一条 finance/ 前缀的其余各支 —— 必须仍然放行。
    ['finance', 'finance/receivables'],
    ['finance', 'finance/receivables/capabilities'],
    ['finance', 'finance/receivables/policies'],
    ['finance', 'finance/receivable-aging/distribution'],
    ['finance', 'finance/receivable-aging/overdue'],
    ['finance', 'finance/payables'],
    ['finance', 'finance/payables/unpaid'],
    ['finance', 'finance/receipts/capabilities'],
    ['finance', 'finance/settlement/capabilities'],
    ['finance', 'finance/bad-debt/capabilities'],
    ['finance', 'finance/write-off/capabilities'],
    ['finance', 'finance/payment-plans/capabilities'],
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
  const path = `finance/supplier-reconciliation/${ID}/confirm`;
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
