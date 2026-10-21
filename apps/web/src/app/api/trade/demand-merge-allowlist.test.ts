import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 6.2.2.2 需求合并与批量的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(new URL('../../../components/purchase/demand-merge-model.ts', import.meta.url)),
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
 * 'trade/demand-merges'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/demand-merges';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串整段去掉：它们是 query，不是路径段。
      .replace(/\$\{demandMergeQuery\([^)]*\)\}/g, '')
      .replace(/\$\{demandMergeDraftQuery\([^)]*\)\}/g, '')
      .replace(/\$\{demandMergeLineQuery\([^)]*\)\}/g, '')
      .replace(/\$\{demandMergeUnmergedQuery\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // **导出走的是 fetch 的完整 URL**（`/api/trade/${base}/...`），比 api() 多一层
      // 前缀。这里把 `/api/<service>/` 剥掉 —— 代理看到的是 service 前缀**之后**的路径，
      // 不剥就会拿 `/api/trade/trade/demand-merges/...` 去比对白名单。
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
      'trade/demand-merges',
      'trade/demand-merges/capabilities',
      'trade/demand-merges/options',
      'trade/demand-merges/print',
      `trade/demand-merges/${ID}`,
      `trade/demand-merges/${ID}/drafts`,
      `trade/demand-merges/${ID}/drafts/${ID}`,
      `trade/demand-merges/${ID}/lines`,
      `trade/demand-merges/${ID}/unmerged`,
      `trade/demand-merges/${ID}/quantity`,
      `trade/demand-merges/${ID}/supplier`,
      `trade/demand-merges/${ID}/finalize`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/demand-merges',
    'trade/demand-merges/print',
    `trade/demand-merges/${ID}/quantity`,
    `trade/demand-merges/${ID}/supplier`,
    `trade/demand-merges/${ID}/finalize`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/demand-merges'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('伪造的租户头与内部键不被转发到上游', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await POST(request(`trade/demand-merges/${ID}/quantity`, 'POST'), {
    params: Promise.resolve({ path: `trade/demand-merges/${ID}/quantity`.split('/') }),
  });
  expect(fetcher).toHaveBeenCalled();
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  const headers = new Headers(init.headers);
  expect(headers.get('X-Tenant-Id')).toBeNull();
  expect(headers.get('X-Internal-Key')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 6.3.1.2 采购单**未交付**：本模块只产出**采购单草稿**，不开采购单的形状。
    `trade/demand-merges/${ID}/convert`,
    `trade/demand-merges/${ID}/purchase-orders`,
    'trade/demand-merges/purchase-orders',
    // 草稿是业务凭据（6.3.1.2 要读它去建真正的采购单），后端没有删除端点。
    `trade/demand-merges/${ID}/delete`,
    // 审批结论由 17 组织（identity）裁定，本模块没有本地批准端点。
    `trade/demand-merges/${ID}/approve`,
    `trade/demand-merges/${ID}/reject`,
    // 本模块只提供「批量打印」，没有 CSV 导出端点。
    `trade/demand-merges/${ID}/export`,
    // 本模块没有「按 id 取单行」的端点（单行信息随详情与行清单一起返回）。
    `trade/demand-merges/${ID}/lines/${ID}`,
    // 非 UUID 段不应被放行。
    `trade/demand-merges/${ID}/lines/not-a-uuid`,
    `trade/demand-merges/not-a-uuid`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/demand-merges/capabilitie',
    'trade/demand-merges/option',
    'trade/demand-merges/prin',
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
    // 6.2.2.1 采购申请（本模块的直接上游，页面用它挑「已批申请单」）
    'trade/purchase-requests/capabilities',
    'trade/purchase-requests/options',
    'trade/purchase-requests',
    // 6.5.1.1 采购对账
    'trade/purchase-reconciliations/capabilities',
    // 6.2.1.3 采购计划
    'trade/purchase-plans/capabilities',
    // 6.2.1.2 补货建议
    'trade/replenishment-suggestions/capabilities',
    // 6.1.2.3 供应商资质
    'trade/supplier-qualifications/capabilities',
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
  const path = `trade/demand-merges/${ID}/quantity`;
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
