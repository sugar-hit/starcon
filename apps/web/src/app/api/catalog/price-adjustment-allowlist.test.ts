import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 5.4.1.1 调价单的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>三个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从**本模块自己生成的契约**里反向抽出真实 path 再逐条断言被放行；</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 {@code /sync}（5.4.2.1 未交付）
 *       与 {@code /approve}（审批结论由 17 组织那边的人做出，本模块**没有**本地批准端点：
 *       凭空放行它等于把审批流本地化，而 identity 那边会什么都不知道）；</li>
 *   <li><b>不误伤</b>：相邻车道已交付的形状必须仍然放行 —— 这是<b>求并安全</b>的证据。
 *       本条元素是<b>新增一条 services[] 元素</b>而不是并进既有那条超长 alternation，
 *       就是为了让 git 求并时不可能把别人的分支吃掉。</li>
 * </ul>
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/catalog.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/catalog/price-adjustments';

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path.replace(/^\//, '').replace(/\{[^}]+\}/g, ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

function callProxy(fullPath: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return proxy(new NextRequest(`http://localhost:3000/api/${fullPath}`, init), {
    params: Promise.resolve({ path: fullPath.split('/') }),
  } as never);
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  expect(sites).toEqual(
    expect.arrayContaining([
      'catalog/price-adjustments',
      'catalog/price-adjustments/capabilities',
      'catalog/price-adjustments/current-price',
      'catalog/price-adjustments/writebacks',
      `catalog/price-adjustments/writebacks/${ID}/ack`,
      `catalog/price-adjustments/${ID}`,
      `catalog/price-adjustments/${ID}/submit`,
      `catalog/price-adjustments/${ID}/execute`,
      `catalog/price-adjustments/${ID}/reject`,
      `catalog/price-adjustments/${ID}/approval/refresh`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/price-adjustments',
    `catalog/price-adjustments/${ID}/submit`,
    `catalog/price-adjustments/${ID}/execute`,
    `catalog/price-adjustments/${ID}/reject`,
    `catalog/price-adjustments/${ID}/approval/refresh`,
    `catalog/price-adjustments/writebacks/${ID}/ack`,
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/price-adjustments'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // ⚠️ 5.4.2.1 平台价格同步**未交付**：本模块只产出「待回写」事实，
    // 放行 /sync 等于渲染一个冒充「已回写平台」的按钮。
    `catalog/price-adjustments/${ID}/sync`,
    // ⚠️ 审批结论由 17 组织那边的人做出：本模块**没有**「本地批准」端点 ——
    // 凭空放行它等于把审批流本地化，而 identity 那边会什么都不知道。
    `catalog/price-adjustments/${ID}/approve`,
    // 本模块不导出 CSV（蓝图的输出是调价单，不是导出件）。
    `catalog/price-adjustments/${ID}/export`,
    // 审批规则**归 17 组织**（identity 的 /identity/approval-rules/**），
    // 本模块只做求值，不代它开配置形状。
    'catalog/price-adjustments/approval-rules',
    // 单条待回写事实没有读端点（只有集合与回执）。
    `catalog/price-adjustments/writebacks/${ID}`,
    // 单字符不是 uuid。
    'catalog/price-adjustments/c',
    // 拼错的字面量子路径。
    'catalog/price-adjustments/currentprice',
    // 拼错的动作段。
    `catalog/price-adjustments/${ID}/submits`,
    // approval 后面只能跟 refresh。
    `catalog/price-adjustments/${ID}/approval`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('🔴 兄弟车道的 catalog 白名单条目未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 其它车道**已交付**的 catalog 模块各挑一条真实 path —— 少一条都会让
  // 「本车道取错一侧静默删掉别人路由」这件事失去唯一的守门人。
  const siblings = [
    // 3.1.1.1 商品主数据
    'catalog/products',
    // 3.1.2.1 SKU 列表
    'catalog/skus',
    // 5.2.1.1 成本构成
    'catalog/sku-cost-compositions',
    // 5.3.1.1 利润测算器
    'catalog/profit-calculator/capabilities',
    // 5.3.1.2 批量测算
    'catalog/batch-calculations/capabilities',
    // 5.3.2.1 定价模板
    'catalog/pricing-templates/capabilities',
    // 5.3.2.2 价格规则
    'catalog/pricing-rules/capabilities',
    // 5.4.1.2 批量调价（**与本模块共用 L3 锚点 5.4.1 的兄弟**）
    'catalog/batch-price-adjustments/capabilities',
    `catalog/batch-price-adjustments/${ID}`,
    // 5.5.2.1 异常告警（价格观测台账 —— 本模块的当前价来源之一）
    'catalog/price-anomaly-alerts/capabilities',
    'catalog/price-anomaly-alerts/observations',
  ];
  for (const path of siblings) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
  }
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `capabilities` / `current-price` / `writebacks` 的首字母都可能落在 [0-9a-f] 里 ——
  // 若判别顺序写反，这几条会被当成「id=xxx 的那个东西」拼出去。
  for (const path of [
    'catalog/price-adjustments/capabilities',
    'catalog/price-adjustments/current-price',
    'catalog/price-adjustments/writebacks',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行`).not.toBe(404);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.anything(),
    );
  }
});
