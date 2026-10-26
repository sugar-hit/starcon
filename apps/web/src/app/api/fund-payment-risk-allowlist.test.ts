import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET as financeGet } from './finance/[...path]/route';
import { GET as iamGet } from './iam/[...path]/route';
import { GET as warehouseGet } from './warehouse/[...path]/route';

/**
 * 1.1.3.2 资金与回款风险在 api-proxy 白名单里的**双向**断言。
 *
 * <p>本模块跨**两个**服务（12 finance + 07 warehouse），把六个上游端点与
 * 「会话」这一条租户来源都走一遍 —— 少放行任何一个，那个域的数字就会在浏览器里
 * **静默消失**，而界面看不出是「没权限」还是「没放行」。
 *
 * <p>🔴 **本车道没有改 {@code api-proxy.ts}**：六个上游端点**全部已在基线白名单里放行**
 * （本车道用脚本逐条实测命中，不是目测）。所以这个测试的职责是
 * **守住既有放行不被别的车道取错一侧删掉**，而不是验证「本车道登记了什么」——
 * 取错一侧的后果同样是页面 404，而 typecheck / lint / vitest 全部全绿。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是那几条<b>写形状</b> ——
 *       蓝图 1.1.3.2 与卷 01 卷首都写死了「工作台只读 + 跳转」，
 *       放行一个后端不存在的写入口，界面就会以为「工作台能直接催收 / 核销 / 确认到账」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../components/dashboard/fund-payment-risk-workspace.tsx', import.meta.url)),
  'utf8',
);

afterEach(() => vi.unstubAllGlobals());

/** 浏览器出口目录名 → 代理 handler。 */
const ROUTES: Record<string, typeof financeGet> = {
  finance: financeGet,
  warehouse: warehouseGet,
  iam: iamGet,
};

/** 代理出口的目录名（identity 与 auth 共用 {@code iam/}）。 */
const OUTLET: Record<string, string> = {
  finance: 'finance',
  warehouse: 'warehouse',
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
 * 只拼路径段，查询串由 {@code request.nextUrl.search} 单独带上。
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
 * <p>形如 `api<...>('finance/payables/unpaid')` 的字面量 ——
 * query 部分截掉（白名单判的是 path，query 由代理单独带上）。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{[^}]*\}/g, 'x')
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

it('六个上游端点 + 会话逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 12 财务：应收账龄分布 + 逾期清单
    'finance/receivable-aging/distribution',
    'finance/receivable-aging/overdue',
    // 12 财务：平台待结算（待到账 / 部分到账两档共用同一个列表端点）
    'finance/settlement',
    // 12 财务：供应商应付未付清单
    'finance/payables/unpaid',
    // 12 财务：在途未结算资金
    'finance/deferred-settlement/summary',
    // 07 库存：在途库存数量
    'warehouse/inventory-risk/projection',
    // 会话：**唯一**的租户与授权来源（前端不自己声明租户或数据范围）
    'auth/session',
  ]) {
    expect(await call(path), `${path} 未被放行`).toBe(200);
  }
});

it('查询串不影响放行判定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  expect(await call('finance/settlement?status=PENDING&size=200')).toBe(200);
  expect(await call('finance/receivable-aging/overdue?size=200')).toBe(200);
  expect(await call('warehouse/inventory-risk/projection?size=500&page=0')).toBe(200);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 本模块是只读聚合层：催收 / 确认 / 核销 / 调整这类写动作归 12 财务，
    //    工作台**不产生任何业务写入** ⇒ 这些写形状在本模块里不存在。
    'finance/receivable-aging/collect',
    'finance/receivable-aging/distribution/refresh',
    'finance/payables/unpaid/void',
    'finance/settlement/PENDING/arrival',
    'finance/deferred-settlement/summary/void',
    'warehouse/inventory-risk/projection/refresh',
    'warehouse/inventory-risk/confirm',
    // 本模块**没有**自己的汇总端点：汇总是前端从六处投影合出来的，
    // 放行一个不存在的后端端点会让界面以为「工作台有独立的资金服务」。
    'finance/fund-payment-risk',
    'warehouse/fund-payment-risk',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'finance/receivable-aging/overdue/not-a-uuid',
  ]) {
    expect(await call(path), `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('前端源码里**没有任何写调用** —— 工作台只读 + 跳转', () => {
  // `api(path, 'POST')` 这种形状一旦出现，就说明工作台开始写业务数据了。
  expect(SOURCE).not.toMatch(/api<[^>]*>\([^)]*,\s*'(POST|PUT|PATCH|DELETE)'/);
  expect(SOURCE).not.toMatch(/method:\s*'(POST|PUT|PATCH|DELETE)'/);
  // 可点的出口上也不许出现写动作的动词。
  // ⚠️ 只查**按钮与链接的文案**，不查整份源码：页面上有一句
  // 「确认 / 催收 / 调整 / 核销都回 12 财务去做」的说明，那是**免责声明**，
  // 拿它当写动作会把这张断言变成永假的假绿灯。
  const WRITE_VERBS = ['催收', '核销', '确认到账', '写回', '提交', '作废', '审批通过'];
  const clickables = [
    ...SOURCE.matchAll(/<Button[^>]*>([\s\S]*?)<\/Button>/g),
    ...SOURCE.matchAll(/<Link[^>]*>([\s\S]*?)<\/Link>/g),
  ].map((match) => match[1] ?? '');
  expect(clickables.length, '一个按钮/链接都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const label of clickables) {
    for (const verb of WRITE_VERBS) {
      expect(label.includes(verb), `可点出口上出现了写动作「${verb}」：${label}`).toBe(false);
    }
  }
});

it('不误伤兄弟模块与上游系统的既有形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 兄弟模块 1.1.3.1 库存风险（warehouse）
    'warehouse/inventory-risk/capabilities',
    'warehouse/inventory-risk/projection',
    // 本模块两个上游系统里**其它车道已交付**的形状 ——
    // 取错一侧会静默删掉它们，表现同样是页面 404 而所有检查全绿。
    'finance/receivable-aging/capabilities',
    'finance/receivable-aging/buckets',
    'finance/receivables',
    'finance/receivables/aging',
    'finance/payables/capabilities',
    'finance/payables/policies',
    'finance/settlement/capabilities',
    'finance/settlement/cycles',
    'finance/deferred-settlement/capabilities',
    'finance/deferred-settlement/rules',
    'finance/payment-plans',
    'finance/receipts',
  ]) {
    expect(await call(path), `${path} 必须仍然放行`).toBe(200);
  }
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('finance/payables/unpaid');

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
