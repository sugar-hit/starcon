import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 6.5.1.1 采购对账的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(
    new URL('../../../components/purchase/purchase-reconciliation-model.ts', import.meta.url),
  ),
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
 * 'trade/purchase-reconciliations'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/purchase-reconciliations';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串整段去掉：它们是 query，不是路径段。
      .replace(/\$\{reconciliationQuery\([^)]*\)\}/g, '')
      .replace(/\$\{query\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // **导出走的是完整 URL**（`/api/trade/${base}/...`），比 api() 多一层前缀。
      // 这里把 `/api/<service>/` 剥掉 —— 代理看到的是 service 前缀**之后**的路径。
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
      'trade/purchase-reconciliations/capabilities',
      'trade/purchase-reconciliations/options',
      `trade/purchase-reconciliations/${ID}`,
      `trade/purchase-reconciliations/${ID}/lines`,
      `trade/purchase-reconciliations/${ID}/discrepancies`,
      `trade/purchase-reconciliations/${ID}/discrepancies/${ID}/resolve`,
      `trade/purchase-reconciliations/${ID}/export`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/purchase-reconciliations',
    `trade/purchase-reconciliations/${ID}/deductions`,
    `trade/purchase-reconciliations/${ID}/send`,
    `trade/purchase-reconciliations/${ID}/confirmations`,
    `trade/purchase-reconciliations/${ID}/discrepancies`,
    `trade/purchase-reconciliations/${ID}/discrepancies/${ID}/resolve`,
    `trade/purchase-reconciliations/${ID}/reopen`,
    `trade/purchase-reconciliations/${ID}/close`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/purchase-reconciliations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 蓝图加粗句：「对账由采购发起、12 财务执行付款，避免采购擅自确认应付」。
    //    12 财务服务当前**不存在**，采购侧**没有**确认应付的端点 ⇒ 这三条必须 404。
    `trade/purchase-reconciliations/${ID}/payable`,
    `trade/purchase-reconciliations/${ID}/payments`,
    `trade/purchase-reconciliations/${ID}/confirm-payable`,
    // 6.4.2.1 质检单与本模块同批并行开发，此刻不存在 ⇒ 没有质检扣量这个映射。
    `trade/purchase-reconciliations/${ID}/quality-deductions`,
    // 对账单是要拿去和供应商对质的凭据，没有删除形状。
    `trade/purchase-reconciliations/${ID}/delete`,
    // 差异只能挂起（HELD）或解决（RESOLVED），没有删除形状。
    `trade/purchase-reconciliations/${ID}/discrepancies/${ID}/delete`,
    // 明细行在 `{uuid}/lines` 里，没有按行 id 的独立映射。
    `trade/purchase-reconciliations/${ID}/lines/${ID}`,
    // 后端没有批量导入 / 批量发起对账的端点。
    'trade/purchase-reconciliations/import',
    'trade/purchase-reconciliations/batch',
    // 非 UUID 段不应被 uuid 分支吃掉。
    `trade/purchase-reconciliations/${ID}/lines/not-a-uuid`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/purchase-reconciliations/capabilitie',
    'trade/purchase-reconciliations/option',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 判据取 **codegen 产物** {@code packages/api-contracts/src/trade.openapi.json}：
 * 那是后端真实端点的一份快照。白名单放行的形状必须与契约里的形状一一对应 ——
 * 白名单多放一个 = 浏览器里会转给一个不存在的端点。
 */
it('白名单放行的形状与后端契约一一对应（不多放、不少放）', () => {
  const spec = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL('../../../../../../packages/api-contracts/src/trade.openapi.json', import.meta.url),
      ),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, unknown>> };
  const contractPaths = Object.keys(spec.paths)
    .filter((p) => p.startsWith('/trade/purchase-reconciliations'))
    .map((p) => p.replace('/trade/', 'trade/'))
    .sort();
  expect(
    contractPaths.length,
    '契约里一条 purchase-reconciliations 路径都没有，判定本身失效了',
  ).toBeGreaterThan(0);

  const allowed = new Set([
    'trade/purchase-reconciliations',
    'trade/purchase-reconciliations/capabilities',
    'trade/purchase-reconciliations/options',
    `trade/purchase-reconciliations/{id}`,
    `trade/purchase-reconciliations/{id}/lines`,
    `trade/purchase-reconciliations/{id}/deductions`,
    `trade/purchase-reconciliations/{id}/send`,
    `trade/purchase-reconciliations/{id}/confirmations`,
    `trade/purchase-reconciliations/{id}/discrepancies`,
    `trade/purchase-reconciliations/{id}/discrepancies/{discrepancyId}/resolve`,
    `trade/purchase-reconciliations/{id}/reopen`,
    `trade/purchase-reconciliations/{id}/close`,
    `trade/purchase-reconciliations/{id}/export`,
  ]);
  expect(new Set(contractPaths)).toEqual(allowed);
});

/**
 * 本车道往 `services[]` 里**新增了一条 trade/ 元素**（不并既有那条的超长 alternation），
 * 所以必须钉住「既有 trade 模块的入口没有被本条吃掉」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 trade 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.2.1.2 审单规则（本条插在它**之前**）
    'trade/order-review/capabilities',
    // 6.2.1.3 采购计划（同一 L3 的兄弟模块，根路径不同）
    'trade/purchase-plans/capabilities',
    // 6.3.2.2 到货与逾期（本模块的对账基数来源）
    'trade/purchase-arrivals/capabilities',
    // 6.2.1.2 补货建议
    'trade/replenishment-suggestions/capabilities',
    // 9.3.1.1 拆单
    'trade/fulfillment/split/capabilities',
    // 11.2.3.3 供应商索赔（本模块的索赔来源）
    'trade/supplier-claims/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
