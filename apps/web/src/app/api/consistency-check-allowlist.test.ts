import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET as channelGet } from './channel/[...path]/route';
import { GET as iamGet } from './iam/[...path]/route';
import { GET as warehouseGet } from './warehouse/[...path]/route';

/**
 * 1.5.1.2 数据一致性体检在 api-proxy 白名单里的**双向**断言。
 *
 * <p>本模块跨**两个**服务（07 warehouse + 02 channel），把四个上游端点与
 * 「会话」这一条租户来源都走一遍 —— 少放行任何一个，那个域的数字就会在浏览器里
 * **静默消失**，而界面看不出是「没权限」还是「没放行」。
 *
 * <p>🔴 **本车道没有改 {@code api-proxy.ts}**：四个上游端点**全部已在基线白名单里放行**
 * （本车道用真跑代理逐条实测命中，不是目测、不是 grep）。所以这个测试的职责是
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
 *       卷 01 卷首写死了「工作台只读 + 跳转」，
 *       放行一个后端不存在的写入口，界面就会以为「体检屏能直接触发核对 / 跑评估 / 建映射」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../components/system-health/consistency-check-workspace.tsx', import.meta.url),
  ),
  'utf8',
);

afterEach(() => vi.unstubAllGlobals());

/** 浏览器出口目录名 → 代理 handler。 */
const ROUTES: Record<string, typeof channelGet> = {
  channel: channelGet,
  warehouse: warehouseGet,
  iam: iamGet,
};

/** 代理出口的目录名（identity 与 auth 共用 {@code iam/}）。 */
const OUTLET: Record<string, string> = {
  channel: 'channel',
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
 * <p>形如 `api<...>('warehouse/inventory/reconciliation...')` 的字面量 ——
 * query 部分截掉（白名单判的是 path，query 由代理单独带上）。
 *
 * <p>⚠️ 模板字面量里的 `${...}` 换成**真实 UUID 形状**的占位符：
 * 白名单的 id 段是 `[0-9a-f-]+` 形状，换成 `x` 会让断言变成永假的假绿灯
 * （那测的是「代理认不认一个不存在的形状」，不是「我源码里的形状放不放行」）。
 */
const UUID_PLACEHOLDER = '11111111-1111-4111-8111-111111111111';

function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{[^}]*\}/g, UUID_PLACEHOLDER)
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

it('四个上游端点 + 会话逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 07 库存：账实核对批次列表（最近一次）
    'warehouse/inventory/reconciliation',
    // 07 库存：某次核对的结论与可疑区间
    'warehouse/inventory/reconciliation/11111111-1111-4111-8111-111111111111',
    // 07 库存：某次核对的差异明细（真分页）
    'warehouse/inventory/reconciliation/11111111-1111-4111-8111-111111111111/findings',
    // 07 库存：库存准确率红线判定（gate）
    'warehouse/inventory/health/gate',
    // 02 渠道：映射缺失队列概览
    'channel/mapping-gaps/summary',
    // 会话：**唯一**的租户与授权来源（前端不自己声明租户或数据范围）
    'auth/session',
  ]) {
    expect(await call(path), `${path} 未被放行`).toBe(200);
  }
});

it('查询串不影响放行判定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  expect(await call('warehouse/inventory/reconciliation?page=0&size=1')).toBe(200);
  expect(
    await call(
      'warehouse/inventory/reconciliation/11111111-1111-4111-8111-111111111111/findings' +
        '?kind=DISCREPANCY&page=0&size=50',
    ),
  ).toBe(200);
  expect(await call('channel/mapping-gaps/summary?size=500&page=0')).toBe(200);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 2.4.2.3 **明确不提供**任何自动匹配（它的能力声明里写着「绝不按名称猜 SKU」），
    //    放行这个形状会让界面以为体检屏能自动补映射。
    'channel/mapping-gaps/auto-match',
    'channel/mapping-gaps/suggest',
    // 07 库存：核对批次没有「刷新」这个形状（重跑核对是 POST 裸路径，见下一条用例）。
    'warehouse/inventory/reconciliation/refresh',
    'warehouse/inventory/reconciliation/latest',
    // 本模块**没有**自己的汇总端点：体检报告是前端从四个上游投影合出来的，
    // 放行一个不存在的后端端点会让界面以为「系统健康中心有独立的一致性服务」。
    'warehouse/consistency-check',
    'channel/consistency-check',
    'warehouse/inventory/consistency',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'warehouse/inventory/reconciliation/not-a-uuid',
  ]) {
    expect(await call(path), `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('共享白名单是**按路径**放行的：上游自己的写端点也在同一路径上', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 7.1.2.2 的「触发一次核对」是 `POST /warehouse/inventory/reconciliation`（裸路径），
  // 7.6.2.1 的「触发一次评估」是 `POST /warehouse/inventory/health/assess`，
  // 2.4.2.3 的「建立映射」是 `POST /channel/mapping-gaps/{uuid}/map` ——
  // 这三条都属于**归属模块自己的页面**，代理按路径放行、不按方法区分（代理这一层
  // 只做路径白名单，方法由各服务的 HTTP 方法语义兜底：写方法打只读端点会得到 405）。
  // 🔴 本模块**只用 GET 读**：下面这条断言与「前端源码里没有任何写调用」那条合起来，
  //    证明体检屏不会写业务数据 —— 写动作的归属仍然是归属系统自己。
  for (const path of [
    'warehouse/inventory/reconciliation',
    'warehouse/inventory/health/assess',
    'channel/mapping-gaps/11111111-1111-4111-8111-111111111111/map',
  ]) {
    expect(await call(path), `${path} 是上游自己的写形状，应仍然放行`).toBe(200);
  }

  const sites = realCallSites();
  expect(sites.length, '一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const site of sites) {
    // ⚠️ 按**末段**判定，不用子串包含：`channel/mapping-gaps/summary` 里含 "map"
    //    但它是个只读概览端点，子串判定会把它误判成写形状。
    expect(
      /(^|\/)(map|assess|ignore|create-sku|refresh|run)$/.test(site),
      `本模块只读：不该调用归属模块的写形状 ${site}`,
    ).toBe(false);
  }
});

it('前端源码里**没有任何写调用** —— 工作台只读 + 跳转', () => {
  // `api(path, 'POST')` 这种形状一旦出现，就说明工作台开始写业务数据了。
  expect(SOURCE).not.toMatch(/api<[^>]*>\([^)]*,\s*'(POST|PUT|PATCH|DELETE)'/);
  expect(SOURCE).not.toMatch(/method:\s*'(POST|PUT|PATCH|DELETE)'/);
  // 可点的出口上也不许出现写动作的动词。
  // ⚠️ 只查**按钮与链接的文案**，不查整份源码：页面上有一句
  // 「不代 12 财务结算与 7.1.2.2 对账执行阻断」的说明，那是**免责声明**，
  // 拿它当写动作会把这张断言变成永假的假绿灯。
  const WRITE_VERBS = [
    '触发核对',
    '重跑核对',
    '跑评估',
    '提交评估',
    '创建映射',
    '忽略缺口',
    '确认差异',
  ];
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
    // 兄弟模块 1.5.1.1 同步与 API 健康（channel）
    'channel/sync-health/summary',
    'channel/sync-health/capabilities',
    // 本模块两个上游系统里**其它车道已交付**的形状 ——
    // 取错一侧会静默删掉它们，表现同样是页面 404 而所有检查全绿。
    'warehouse/inventory/health',
    'warehouse/inventory/health/issues',
    'warehouse/inventory/health/trend',
    'warehouse/inventory/ledger',
    'channel/mapping-gaps',
    'channel/mapping-gaps/filter-options',
    'channel/mapping-gaps/capabilities',
  ]) {
    expect(await call(path), `${path} 必须仍然放行`).toBe(200);
  }
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('warehouse/inventory/health/gate');

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
