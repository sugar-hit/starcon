import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 6.2.1.3 采购计划的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
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
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，
 * 绝不收窄既有分支。取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/purchase/purchase-plan-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/trade/${path}`, {
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
 * 'trade/purchase-plans'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/purchase-plans';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串整段去掉：它们是 query，不是路径段。
      .replace(/\$\{purchasePlanQuery\([^)]*\)\}/g, '')
      .replace(/\$\{purchasePlanLineQuery\([^)]*\)\}/g, '')
      .replace(/\$\{purchasePlanSummaryQuery\([^)]*\)\}/g, '')
      .replace(/\$\{purchasePlanCampaignQuery\([^)]*\)\}/g, '')
      .replace(/\$\{purchasePlanLockPolicyQuery\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // **导出走的是 fetch 的完整 URL**（`/api/trade/${base}/...`），比 api() 多一层
      // 前缀。这里把 `/api/<service>/` 剥掉 —— 代理看到的是 service 前缀**之后**的路径，
      // 不剥就会拿 `/api/trade/trade/purchase-plans/...` 去比对白名单，
      // 于是「导出这条路没放行」的错误被断言成「白名单漏了导出」而误报。
      .replace(/^\/api\/[a-z]+\//, '');
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
      'trade/purchase-plans/capabilities',
      'trade/purchase-plans/options',
      'trade/purchase-plans/summary',
      'trade/purchase-plans/campaigns',
      `trade/purchase-plans/campaigns/${ID}`,
      'trade/purchase-plans/lock-policies',
      `trade/purchase-plans/${ID}`,
      `trade/purchase-plans/${ID}/aggregate`,
      `trade/purchase-plans/${ID}/confirm`,
      `trade/purchase-plans/${ID}/export`,
      `trade/purchase-plans/${ID}/lines`,
      `trade/purchase-plans/${ID}/lines/${ID}`,
      `trade/purchase-plans/${ID}/lines/${ID}/adjust`,
      `trade/purchase-plans/${ID}/lines/${ID}/lock`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/purchase-plans',
    'trade/purchase-plans/campaigns',
    'trade/purchase-plans/lock-policies',
    `trade/purchase-plans/${ID}/confirm`,
    `trade/purchase-plans/${ID}/aggregate`,
    `trade/purchase-plans/${ID}/lines/${ID}/adjust`,
    `trade/purchase-plans/${ID}/lines/${ID}/lock`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/purchase-plans'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('更新与删除大促档期的形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const put = await PUT(request(`trade/purchase-plans/campaigns/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/purchase-plans/campaigns/${ID}`.split('/') }),
  });
  expect(put.status, `PUT campaigns/${ID} 未被放行`).toBe(200);

  const del = await DELETE(request(`trade/purchase-plans/campaigns/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/purchase-plans/campaigns/${ID}`.split('/') }),
  });
  expect(del.status, `DELETE campaigns/${ID} 未被放行`).toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块不提供删除计划：计划是业务凭据（行调整 / 锁定留痕都指向它）。
    `trade/purchase-plans/${ID}/delete`,
    // 锁库策略走版本化 upsert，没有按 id 单独读 / 改。
    `trade/purchase-plans/lock-policies/${ID}`,
    // 6.2.2.1 采购申请**未交付**：本模块只产出计划行，不代它建申请单。
    'trade/purchase-plans/purchase-requests',
    `trade/purchase-plans/${ID}/purchase-requests`,
    // 6.2.1.2 的建议命名空间不是本模块的（反向拦截项）。
    'trade/purchase-plans/replenishment-suggestions',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    `trade/purchase-plans/${ID}/lines/not-a-uuid`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/purchase-plans/campaign',
    'trade/purchase-plans/lock-policy',
    'trade/purchase-plans/summar',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件。并入时若取错一侧，会静默删掉已交付模块
 * 的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿，
 * 因为白名单只在浏览器端生效。所以必须正向钉住已放行的全部形状。
 */
it('不误伤其他车道已登记的 trade 分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 6.2.1.2 补货建议（本模块的直接上游）
    'trade/replenishment-suggestions/capabilities',
    'trade/replenishment-suggestions/suggestions',
    // 6.2.1.1 缺货与断货风险（同一 L3 锚点的兄弟模块，**不是本模块的**）
    'trade/stockout-risk/capabilities',
    // 6.1.2.3 供应商资质
    'trade/supplier-qualifications/capabilities',
    // 9.3.1.1 拆单
    'trade/fulfillment/split/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `trade/purchase-plans/${ID}/lines/${ID}/lock`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/trade/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
