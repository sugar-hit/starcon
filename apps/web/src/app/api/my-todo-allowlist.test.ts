import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET as channelGet } from './channel/[...path]/route';
import { GET as crmGet } from './crm/[...path]/route';
import { GET as financeGet } from './finance/[...path]/route';
import { GET as iamGet } from './iam/[...path]/route';
import { GET as tradeGet } from './trade/[...path]/route';
import { GET as warehouseGet } from './warehouse/[...path]/route';

/**
 * 1.1.1.1 我的待办在 api-proxy 白名单里的**双向**断言。
 *
 * <p>本模块跨**六个**服务（trade / warehouse / channel / finance / crm / identity），
 * 所以一条用例把六个前缀都走一遍 —— 少放行任何一个前缀，
 * 那个域的待办就会在浏览器里**静默消失**，而界面看不出是「没权限」还是「没放行」。
 *
 * <p>🔴 **本车道没有改 {@code api-proxy.ts}**：八个上游端点**全部已在基线的 225 条
 * 白名单元素里放行**（本车道用脚本逐条实测命中，不是目测）。
 * 所以这个测试的职责是**守住既有放行不被别的车道取错一侧删掉**，
 * 而不是验证「本车道登记了什么」——
 * 取错一侧的后果同样是页面 404，而 typecheck / lint / vitest 全绿。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是那几条<b>写形状** ——
 *       蓝图 1.1.1.1 原文「**不写业务数据**，只读投影」，
 *       放行一个后端不存在的写入口，界面就会以为「我的待办页能直接处理待办」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
// 本文件在 `app/api/` 下（比 `app/api/iam/` 浅一层），所以是 `../../components/...`。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../components/dashboard/my-todo-workspace.tsx', import.meta.url)),
  'utf8',
);

const ID = '33333333-3333-3333-3333-333333333333';

afterEach(() => vi.unstubAllGlobals());

/** 浏览器出口目录名 → 代理 handler（六个服务各一份出口，共享同一份白名单）。 */
const ROUTES: Record<string, typeof tradeGet> = {
  trade: tradeGet,
  warehouse: warehouseGet,
  channel: channelGet,
  finance: financeGet,
  crm: crmGet,
  iam: iamGet,
};

/** 代理出口的目录名（identity 与 auth 共用 {@code iam/}）。 */
const OUTLET: Record<string, string> = {
  trade: 'trade',
  warehouse: 'warehouse',
  channel: 'channel',
  finance: 'finance',
  crm: 'crm',
  identity: 'iam',
  auth: 'iam',
};

function request(path: string) {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
    method: 'GET',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
  });
}

/**
 * 走对应服务的出口发一次 GET，返回 HTTP 状态码。
 *
 * <p>⚠️ **路径段里不能带查询串**：代理的 {@code path = params.path.join('/')}
 * 只拼路径段，查询串由 {@code request.nextUrl.search} 单独带上 ——
 * 把 {@code ?a=b} 塞进最后一段会让白名单拿到一个带查询串的路径，判定必然落空。
 *
 * <p>⚠️ {@code auth/} 与 {@code identity/} 共用 {@code iam/} 那个出口
 * （前端 {@code api()} 把它映射到 {@code /api/iam/}），所以按前缀映射到出口。
 */
async function call(path: string): Promise<number> {
  const [withoutQuery = ''] = path.split('?');
  const service = withoutQuery.split('/')[0] ?? '';
  const outlet = OUTLET[service];
  if (!outlet) throw new Error(`未知的服务前缀：${service}`);
  const handler = ROUTES[outlet];
  if (!handler) throw new Error(`未知的服务出口：${outlet}`);
  const response = await handler(request(path), {
    params: Promise.resolve({ path: withoutQuery.split('/') }),
  });
  return response.status;
}

/**
 * 从源码里抽出真实调用点。
 *
 * <p>形如 `api<...>('trade/fulfillment/timeliness/overdue?page=...')` 的字面量 ——
 * query 部分截掉（白名单判的是 path，query 由代理单独带上）。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      .trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*'([^']*)'/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw.replace(/\?.*$/, '').trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('前端源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '前端源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    expect(await call(path), `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
});

it('八个上游端点（蓝图的八类待办各一个）逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 09 订单：超期未发货 + 待审订单
    'trade/fulfillment/timeliness/overdue',
    'trade/order-pending-review/queue',
    // 07 库存：库存缺口 + 库存低于安全线
    'warehouse/inventory/stockout-alerts/stockouts',
    'warehouse/inventory/safety-stock/alerts',
    // 02 渠道：待处理同步失败
    'channel/sync-failures',
    // 12 财务：待核对账差异
    'finance/supplier-reconciliation',
    // 15 客户：待回复站内信
    'crm/message-inbox/messages',
    // 17 审批：待审批单据
    'identity/approval-todos',
    // 会话：**唯一**的租户与授权来源（前端不自己声明租户或数据范围）
    'auth/session',
  ]) {
    expect(await call(path), `${path} 未被放行`).toBe(200);
  }
});

it('查询串不影响放行判定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 筛选条件不会让路径落到白名单之外。
  expect(
    await call('crm/message-inbox/messages?readStatus=UNREAD&page=0&size=50'),
  ).toBe(200);
  expect(await call('warehouse/inventory/safety-stock/alerts?page=1&size=50')).toBe(200);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 本模块是只读投影：认领 / 完成 / 转交这类写动作归归属系统，
    //    工作台**不产生任何业务写入** ⇒ 这些写形状在本模块里不存在。
    'channel/sync-failures/replay',
    'crm/message-inbox/messages/reply',
    'warehouse/inventory/safety-stock/alerts/acknowledge',
    'warehouse/inventory/stockout-alerts/stockouts/acknowledge',
    'finance/supplier-reconciliation/resolve',
    'identity/approval-todos/decide',
    // 本模块**没有**自己的汇总端点：汇总是前端从八处投影合出来的，
    // 放行一个不存在的后端端点会让界面以为「工作台有独立的待办服务」。
    'identity/my-todos',
    'trade/my-todos',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'warehouse/inventory/stockout-alerts/stockouts/not-a-uuid',
    'identity/approval-todos/not-a-uuid',
  ]) {
    expect(await call(path), `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤兄弟模块与上游系统的既有形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 兄弟模块 1.1.1.2 异常订单队列（trade）与 1.1.1.3 审批待办（identity）
    'trade/anomaly-orders',
    'trade/anomaly-orders/capabilities',
    'identity/approval-todos',
    // 本模块的六个上游系统里**其它车道已交付**的形状 ——
    // 取错一侧会静默删掉它们，表现同样是页面 404 而所有检查全绿。
    'trade/fulfillment/pending-shipment/board',
    'trade/order-pending-review/summary',
    'warehouse/inventory/sellable/skus',
    'warehouse/inventory/reconciliation',
    'channel/sync-failures/summary',
    'finance/transactions/reconciliation',
    'crm/message-inbox/capabilities',
    'crm/message-inbox/shops',
  ]) {
    expect(await call(path), `${path} 必须仍然放行`).toBe(200);
  }
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('identity/approval-todos');

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
