import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * 10.4.2.2 备货预报的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个页面在浏览器里 404，而 CI 全绿。这里**真跑代理处理器**
 * 并断言 HTTP 状态码 —— 这是唯一可信的判据：
 *
 * <ol>
 *   <li>逐条断言本模块真实实现的路径**被放行**（ALLOW）；</li>
 *   <li>逐条断言后端**不存在**的形状**仍被拦成 404**（BLOCK）——
 *       放行后端不存在的路径比 404 更糟：界面会以为功能已交付；</li>
 *   <li>把既有 channel 路由列为**回归基线** —— 本条目是**新增一条 services[] 元素**，
 *       不能挤掉别人已交付的路由（挤掉的表现是页面 404，而 typecheck / lint / vitest
 *       / Playwright 全部全绿）。</li>
 * </ol>
 *
 * <p>🔴 本模块与 10.4.2.1 海外仓连接**刻意不共用前缀**（`stock-forecasts` vs
 * `warehouse-providers`）：两者语义不同（备货预报是「把货备到那个仓去」，
 * 海外仓连接是「与服务商建立连接」），不共用端点、不共用表。
 */
const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

const ALLOW = [
  // ---- 10.4.2.2 备货预报（本车道） ----
  'stock-forecasts',
  'stock-forecasts/capabilities',
  'stock-forecasts/accounts',
  'stock-forecasts/specs',
  `stock-forecasts/specs/${UUID}/retire`,
  `stock-forecasts/${UUID}`,
  `stock-forecasts/${UUID}/lines`,
  `stock-forecasts/${UUID}/spec-check`,
  `stock-forecasts/${UUID}/validate`,
  `stock-forecasts/${UUID}/submit`,
  `stock-forecasts/${UUID}/cancel`,
  `stock-forecasts/${UUID}/availability`,
  `stock-forecasts/${UUID}/shipment-task`,
  `stock-forecasts/${UUID}/shipment-task/dispatch`,
  `stock-forecasts/${UUID}/shipment-task/retry`,
  `stock-forecasts/${UUID}/shipment-reports`,
  `stock-forecasts/${UUID}/receipts`,
  // ---- 既有 channel 路由：回归基线（本条目是新增元素，不许挤掉它们） ----
  'warehouse-providers',
  'warehouse-providers/connections',
  `warehouse-providers/accounts/${UUID}`,
  'warehouse-providers/sync-runs',
  'compliance-precheck/capabilities',
  'compliance-precheck/reports',
  'listing-templates',
  'listing-templates/capabilities',
  'handover-batches',
  'handover-batches/capabilities',
  'return-disposals/orders',
  'platforms',
  'shops',
  'webhook-dedup/summary',
];

const BLOCK = [
  // 拼写相近：首 token 必须逐字相等
  'stock-forecastz',
  'stock-forecast',
  // 动作段只认 dispatch / retry 两个
  `stock-forecasts/${UUID}/shipment-task/cancel`,
  `stock-forecasts/${UUID}/shipment-task/force`,
  // 没有单条回执端点：回执按预报单寻址
  `stock-forecasts/${UUID}/receipts/${UUID}`,
  // 平台货件接口直连未接通，放行它只会让界面以为已交付
  `stock-forecasts/${UUID}/platform-shipment`,
  // 预报单靠状态机收口，不靠物理删除
  `stock-forecasts/${UUID}/delete`,
  // 不存在的动作
  `stock-forecasts/${UUID}/force-submit`,
  // id 段必须是真的 UUID 形状
  'stock-forecasts/not-a-uuid',
  'stock-forecasts/not-a-uuid/validate',
  'stock-forecasts/specs/not-a-uuid/retire',
  // 不放行裸的 specs/{id}（后端没有「按 id 取单条规格」这个端点）
  `stock-forecasts/specs/${UUID}`,
];

describe('10.4.2.2 备货预报 api-proxy 白名单', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function callProxy(path: string) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })),
    );
    const { proxy } = await import('./api-proxy');
    const { NextRequest } = await import('next/server');
    const req = new NextRequest(`http://localhost/api/channel/${path}`, {
      headers: { cookie: 'starcon_session=test' },
    });
    return proxy(req, {
      params: Promise.resolve({ path: ['channel', ...path.split('/')] }),
    } as never);
  }

  it('放行清单非空（防抽取器坏掉）', () => {
    expect(ALLOW.length).toBeGreaterThan(10);
    expect(new Set(ALLOW).size).toBe(ALLOW.length);
  });

  it.each(ALLOW)('放行 %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} 被拦成 404（浏览器里整个页面会 404）`).not.toBe(404);
  });

  it.each(BLOCK)('仍然拦掉 %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} 不该被放行（后端没有这个形状）`).toBe(404);
  });
});
