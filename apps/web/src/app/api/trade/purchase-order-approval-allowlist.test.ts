import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 6.3.1.3 采购单审批的 api-proxy 白名单**双向**断言。
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
    new URL('../../../components/purchase/purchase-order-approval-model.ts', import.meta.url),
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
 * 'trade/purchase-order-approvals'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/purchase-order-approvals';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串函数：展开成空串（路径本身不带 query）。
      .replace(/\$\{purchaseOrderApprovalQuery\(params\)\}/g, '')
      .replace(/\$\{purchaseOrderApprovalPayableQuery\(params\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
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
      'trade/purchase-order-approvals/capabilities',
      'trade/purchase-order-approvals/rules',
      'trade/purchase-order-approvals/members',
      'trade/purchase-order-approvals/members/buyers',
      'trade/purchase-order-approvals/members/approvers',
      'trade/purchase-order-approvals/orders',
      'trade/purchase-order-approvals/payables',
    ]),
  );
});

it('本模块的写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/purchase-order-approvals/rules',
    'trade/purchase-order-approvals/members/buyers',
    'trade/purchase-order-approvals/members/approvers',
    'trade/purchase-order-approvals/orders/batch',
    `trade/purchase-order-approvals/orders/${ID}/approve`,
    `trade/purchase-order-approvals/orders/${ID}/reject`,
    `trade/purchase-order-approvals/payables/${ID}/confirm`,
    `trade/purchase-order-approvals/payables/${ID}/reject`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/purchase-order-approvals'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('改规则的 PUT 形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const path = `trade/purchase-order-approvals/rules/${ID}`;
  const response = await PUT(request(path, 'PUT'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, `PUT ${path} 未被放行`).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/purchase-order-approvals'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('启用 / 停用规则的形状被放行（带 query）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const path = `trade/purchase-order-approvals/rules/${ID}/status`;
  const response = await POST(request(`${path}?status=ACTIVE&version=0`, 'POST'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, `POST ${path} 未被放行`).toBe(200);
});

it('伪造的租户头与内部键不被转发到上游', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await POST(request('trade/purchase-order-approvals/orders/batch', 'POST'), {
    params: Promise.resolve({ path: 'trade/purchase-order-approvals/orders/batch'.split('/') }),
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
    // 裸路径：本模块没有裸集合读。
    'trade/purchase-order-approvals',
    // 行随 6.3.1.2 的编辑表单一起返回，没有独立的行子资源端点。
    `trade/purchase-order-approvals/orders/${ID}/lines`,
    // 审批链路 / 应付 / 收货放行随详情一起返回。
    `trade/purchase-order-approvals/orders/${ID}/chain`,
    `trade/purchase-order-approvals/orders/${ID}/payable`,
    `trade/purchase-order-approvals/orders/${ID}/receipt-release`,
    // 🔴 不存在「撤销收货放行」：审批通过后只能走 APPROVED → CLOSED。
    `trade/purchase-order-approvals/orders/${ID}/revoke-receipt`,
    // 🔴 12 财务未交付（本仓没有 finance 服务）：不代它开税额形状。
    `trade/purchase-order-approvals/orders/${ID}/tax`,
    // 变更单 / 红冲重下不在本模块内。
    'trade/purchase-order-approvals/change-orders',
    // 这两张表没有按 id 寻址的读端点。
    `trade/purchase-order-approvals/payables/${ID}`,
    `trade/purchase-order-approvals/members/${ID}`,
    // 规则不提供删除（用停用）。
    `trade/purchase-order-approvals/rules/${ID}/delete`,
    // 非 UUID 段不应被放行。
    'trade/purchase-order-approvals/orders/not-a-uuid',
    // 字面量子路径写错时不能被部分匹配吃掉。
    'trade/purchase-order-approvals/capabilitie',
    'trade/purchase-order-approvals/rule',
    'trade/purchase-order-approvals/member',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 白名单是**按路径**放行、不按方法放行 ⇒ 同一个路径换方法仍然会被转发。
 *
 * <p>例如 {@code rules/{uuid}} 是「改规则（PUT）」的形状：GET 同一个路径也会被转发，
 * 由**后端**决定这个方法存不存在（那里没有按 id 读规则的端点 ⇒ 404）。
 * 代理层不做方法区分，所以这一条不能写进上面的「被拒」清单 ——
 * 写进去会得到 503（转发到不存在的上游）而不是 404。
 */
it('rules/{uuid} 按路径放行（供 PUT 用），方法由后端裁定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const path = `trade/purchase-order-approvals/rules/${ID}`;
  const response = await GET(request(path), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/purchase-order-approvals'),
    expect.objectContaining({ method: 'GET' }),
  );
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
    // 6.3.1.1 采购单列表（本模块的兄弟，**首段只差两个连字符** —— 绝不能互相吃掉）
    'trade/purchase-orders/capabilities',
    'trade/purchase-orders',
    // 6.3.1.2 采购单创建与编辑
    'trade/purchase-order-editor/capabilities',
    'trade/purchase-order-editor/orders',
    // 6.2.2.2 需求合并与批量
    'trade/demand-merges/capabilities',
    // 6.3.2.2 到货与逾期
    'trade/purchase-arrivals/capabilities',
    // 6.2.1.3 采购计划
    'trade/purchase-plans/capabilities',
    // 6.5.2.1 采购分析
    'trade/purchase-analytics/capabilities',
    // 6.5.1.1 采购对账
    'trade/purchase-reconciliations/capabilities',
    // 6.4.2.1 质检单 / 6.4.2.2 不良品处理（本锚点两侧的车道）
    'trade/quality-inspections/capabilities',
    'trade/defective-dispositions/capabilities',
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
  const path = 'trade/purchase-order-approvals/orders/batch';
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
