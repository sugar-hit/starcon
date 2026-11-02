import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 15.6.1.1 会员与等级的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「可以手工挂等级」「积分已经交付」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** `crm/customer-membership/...`：
 * 拿兄弟车道的前缀当「未交付形状」会断言出一个**假红**。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件，
 * 本车道只**新增**一条 crm/ services 条目 —— 那必须被断言钉住。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/crm/customer-membership-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'crm/customer-membership'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'crm/customer-membership';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值（`${query({ ...params })}`）整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
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
      'crm/customer-membership/capabilities',
      'crm/customer-membership/members',
      `crm/customer-membership/members/${ID}`,
      'crm/customer-membership/levels',
      `crm/customer-membership/levels/${ID}`,
      'crm/customer-membership/benefits',
      'crm/customer-membership/level-changes',
      'crm/customer-membership/compute',
      'crm/customer-membership/runs',
    ]),
  );
});

it('写动作（POST / PUT）被放行，且真的转发到 8086', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const createResponse = await POST(request('crm/customer-membership/levels', 'POST'), {
    params: Promise.resolve({ path: ['crm', 'customer-membership', 'levels'] }),
  });
  expect(createResponse.status, 'POST crm/customer-membership/levels 未被放行').toBe(200);

  const computeResponse = await POST(request('crm/customer-membership/compute', 'POST'), {
    params: Promise.resolve({ path: ['crm', 'customer-membership', 'compute'] }),
  });
  expect(computeResponse.status, 'POST crm/customer-membership/compute 未被放行').toBe(200);

  const updateResponse = await PUT(request(`crm/customer-membership/levels/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: ['crm', 'customer-membership', 'levels', ID] }),
  });
  expect(updateResponse.status, 'PUT crm/customer-membership/levels/{uuid} 未被放行').toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8086/crm/customer-membership/compute'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 **没有「手工挂等级」的写路径**：蓝图加粗句「会员等级必须由真实消费数据驱动，
    //   手工挂等级会导致权益成本失控」。后端**没有**任何接受等级代码的端点，
    //   DB 层触发器也拒绝 level_origin='MANUAL_OVERRIDE'。
    `crm/customer-membership/members/${ID}/level`,
    `crm/customer-membership/members/${ID}/set-level`,
    `crm/customer-membership/members/${ID}/override`,
    // 积分与权益核销归 15.6.1.2（未交付）。
    'crm/customer-membership/points',
    // 平台会员体系对接未交付（channel 侧没有这个接口）。
    'crm/customer-membership/platform-sync',
    // 没有导出端点。
    'crm/customer-membership/export',
    // 🔴 **裸路径不放行**：后端第一个子路径就是 capabilities。
    'crm/customer-membership',
    // 等级变更记录按会员读，不按单条记录寻址。
    `crm/customer-membership/level-changes/${ID}`,
    // 非 UUID 段不应被 uuid 分支吃掉（那条分支一放开就等于放行了手工挂等级）。
    'crm/customer-membership/members/not-a-uuid',
    'crm/customer-membership/levels/not-a-uuid',
    // 多一段仍然 404。
    'crm/customer-membership/capabilities/extra',
    `crm/customer-membership/members/${ID}/extra`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已放行的分支（求并，不是二选一）。
 *
 * <p>本车道只新增一条 services 条目，但白名单是累积型共享文件：并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest 全绿。
 */
it('不误伤其余服务已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const cases: [string, string][] = [
    ['catalog', 'catalog/kb/overview'],
    ['channel', 'channel/platforms'],
    ['warehouse', 'warehouse/inventory/overview'],
    ['trade', 'trade/processing/orders'],
    ['identity', 'identity/roles'],
    // 同服务的兄弟车道（15.4.2.1 客服知识库 / 15.2.2.1 索评 / 15.2.2.2 索评规则 /
    // 15.1.2.1 买家邮件 / 15.3.2.1 客户标签 / 15.3.2.2 接触记录 / 15.3.1.1 客户列表 /
    // 15.3.1.2 客户详情 / 15.3.1.3 跨店合并 / 15.6.2.1 生命周期）：
    // 本车道只**新增**一条元素，不能把它们的分支吃掉。
    ['crm', 'crm/knowledge/capabilities'],
    ['crm', 'crm/review-requests/capabilities'],
    ['crm', 'crm/review-rules/capabilities'],
    ['crm', 'crm/buyer-emails/capabilities'],
    ['crm', 'crm/customer-tags/capabilities'],
    ['crm', 'crm/customer-contacts/capabilities'],
    ['crm', 'crm/customers/capabilities'],
    ['crm', 'crm/customer-merge/capabilities'],
    ['crm', 'crm/customer-lifecycle/capabilities'],
  ];
  for (const [service, path] of cases) {
    const { proxy } = await import('../../../lib/api-proxy');
    const req = new NextRequest(`http://localhost:3000/api/${path}`, {
      headers: { cookie: 'starcon_session=x' },
    });
    const response = await proxy(req, {
      params: Promise.resolve({ path: path.split('/') }),
    } as never);
    expect(response.status, `${service} 的 ${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const response = await POST(
    new NextRequest('http://localhost:3000/api/crm/crm/customer-membership/compute', {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: ['crm', 'customer-membership', 'compute'] }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
