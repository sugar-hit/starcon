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
 * 1.3.1.1 通知列表在 api-proxy 白名单里的**双向**断言。
 *
 * <p>本模块跨**六个**服务（identity / warehouse / trade / channel / finance / crm），
 * 所以一条用例把六个前缀都走一遍 —— 少放行任何一个前缀，
 * 那个域的通知就会在浏览器里**静默消失**，而界面看不出是「没权限」还是「没放行」。
 *
 * <p>🔴 **本车道改了 {@code api-proxy.ts}**（新增一条独立的
 * {@code prefix: 'identity/'} 元素，首 token {@code notification-inbox}）：
 * 通知的**个人状态**（已读 / 按类型订阅）需要新的端点，而通知**内容**
 * 仍来自五个域**已在基线放行**的只读端点。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是
 *       {@code notification-inbox/read-all} —— 蓝图要求「批量已读」**限定在当前筛选出的集合内**，
 *       放行一个「把全部标已读」的端点就等于把这个边界抹掉。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
// 本文件在 `app/api/` 下（比 `app/api/iam/` 浅一层），所以是 `../../components/...`。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../components/notifications/notification-inbox-workspace.tsx', import.meta.url)),
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
 * <p>形如 `api<...>('identity/notification-inbox/read')` 的字面量 ——
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

it('本模块的 6 个 identity 端点 + 5 个上游只读端点逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // identity：通知的**个人状态**（已读 / 按类型订阅）
    'identity/notification-inbox/capabilities',
    'identity/notification-inbox/read-state',
    'identity/notification-inbox/read',
    'identity/notification-inbox/unread',
    'identity/notification-inbox/subscriptions',
    // 会话：**唯一**的租户与授权来源（前端不自己声明租户或数据范围）
    'auth/session',
    // 07 库存：缺货预警
    'warehouse/inventory/stockout-alerts/stockouts',
    // 09 订单：履约超时
    'trade/fulfillment/timeliness/overdue',
    // 02 渠道：同步失败
    'channel/sync-failures',
    // 12 财务：对账差异
    'finance/supplier-reconciliation',
    // 15 客户：站内信
    'crm/message-inbox/messages',
  ]) {
    expect(await call(path), `${path} 未被放行`).toBe(200);
  }
});

it('查询串不影响放行判定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  expect(
    await call('warehouse/inventory/stockout-alerts/stockouts?page=0&size=50'),
  ).toBe(200);
  expect(await call('crm/message-inbox/messages?page=1&size=50')).toBe(200);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 「把本租户全部通知标已读」这种端点**刻意不存在**：
    //    批量已读只对**点名**的键生效（调用方负责把集合限定在当前筛选出的那批里）。
    'identity/notification-inbox/read-all',
    'identity/notification-inbox/read-state/all',
    // 🔴 1.3.1.2 通知偏好（渠道 / 免打扰）**未交付**。
    'identity/notification-inbox/preferences',
    // 🔴 1.3.2.1 预警规则**未交付**。
    'identity/notification-inbox/rules',
    // 🔴 1.3.2.2 外部渠道推送**未交付**。
    'identity/notification-inbox/push',
    // 裸路径：后端**没有**「一条汇总列表接口」——
    // 通知内容是各域的只读投影，前端聚合层自己取，
    // 放行一个不存在的端点会让界面以为「通知列表有自己的服务」。
    'identity/notification-inbox',
    'identity/notifications',
    // 通知**内容**的写动作归归属系统：收件箱只读投影 + 跳转，
    // 放行这些写形状会让界面以为「收件箱能直接处置业务单据」。
    'crm/message-inbox/messages/reply',
    'warehouse/inventory/stockout-alerts/stockouts/acknowledge',
    'channel/sync-failures/replay',
    'finance/supplier-reconciliation/resolve',
    // 多一层仍然 404。
    'identity/notification-inbox/read/extra',
  ]) {
    expect(await call(path), `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤兄弟模块与上游系统的既有形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 兄弟模块 1.1.1.1 我的待办（1.1.3.2 资金与回款风险）的形状 ——
    // 取错一侧会静默删掉它们，表现同样是页面 404 而所有检查全绿。
    'trade/fulfillment/timeliness/overdue',
    'warehouse/inventory/stockout-alerts/stockouts',
    'crm/message-inbox/messages',
    'channel/sync-failures',
    'finance/supplier-reconciliation',
    // identity 里**其它车道已交付**的形状。
    'identity/user-preferences',
    'identity/page-favorites',
    'identity/approval-todos',
    // 上游系统里其它模块已交付的形状。
    'crm/message-inbox/capabilities',
    'crm/message-inbox/shops',
    'channel/sync-failures/summary',
    'warehouse/inventory/safety-stock/alerts',
    'trade/order-pending-review/queue',
    'finance/transactions/reconciliation',
  ]) {
    expect(await call(path), `${path} 必须仍然放行`).toBe(200);
  }
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('identity/notification-inbox/capabilities');

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
