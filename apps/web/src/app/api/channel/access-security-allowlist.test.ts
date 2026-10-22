import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 16.5.2.1 登录与访问安全 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/access-security/access-security-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  // 浏览器侧的真实形状：/api/<服务前缀>/<路径>（服务前缀就是路径的第一段）
  const service = path.split('/')[0];
  return new NextRequest(`http://localhost:3000/api/${service}/${path}`, {
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
 * 从模型源码里反向抽出**真实存在**的调用点。
 *
 * <p>模型里的端点既有模板串（{@code `channel/access-security/policy`}）也有
 * 带 id 与查询串的（{@code `.../alerts/${alertId}`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'channel/access-security';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    // 🔴 裸前缀常量（BASE 本身）**不是调用点**：后端没有「一条汇总列表接口」，
    //    裸路径刻意不放行（下面单独有一条用例钉住它必须 404）。
    if (raw === base) continue;
    found.add(raw);
  }
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    // 模型里用 `${BASE}` 插值拼路径（BASE 是导出的前缀常量），先把它还原成真实前缀
    const expanded = raw.replace(/\$\{BASE\}/g, base);
    if (!expanded || !expanded.startsWith(base)) continue;
    const cleaned = expanded
      // `${query}` 是「可选的查询串」，展开后为空（不是一段路径）
      .replace(/\$\{query\}/g, '')
      // 剩下的 `${...}` 段都是路径里的 id 段
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    if (cleaned === base) continue;
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/access-security/capabilities',
      'channel/access-security/policy',
      'channel/access-security/mfa/registrations',
      'channel/access-security/mfa/challenges',
      'channel/access-security/login-audit',
      'channel/access-security/login-audit/scan',
      'channel/access-security/alerts',
      `channel/access-security/alerts/${ID}`,
      `channel/access-security/alerts/${ID}/acknowledge`,
      `channel/access-security/alerts/${ID}/ignore`,
      `channel/access-security/alerts/${ID}/resolve`,
      'channel/access-security/ip-allowlist',
      'channel/access-security/ip-allowlist/check',
      'channel/access-security/credential-access',
      'channel/access-security/credential-access/credentials',
      `channel/access-security/credential-access/${ID}`,
      'channel/access-security/session',
    ]),
  );
});

it('写形状（PUT 策略 / POST 扫描 / POST 处置 / DELETE 白名单）被放行，且真的转发到 8083', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await PUT(request('channel/access-security/policy', 'PUT'), {
    params: Promise.resolve({ path: 'channel/access-security/policy'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/access-security/policy'),
    expect.objectContaining({ method: 'PUT' }),
  );

  await POST(request('channel/access-security/login-audit/scan', 'POST'), {
    params: Promise.resolve({ path: 'channel/access-security/login-audit/scan'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/access-security/login-audit/scan'),
    expect.objectContaining({ method: 'POST' }),
  );

  await POST(request(`channel/access-security/alerts/${ID}/resolve`, 'POST'), {
    params: Promise.resolve({ path: `channel/access-security/alerts/${ID}/resolve`.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/access-security/alerts/'),
    expect.objectContaining({ method: 'POST' }),
  );

  await DELETE(request(`channel/access-security/ip-allowlist/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `channel/access-security/ip-allowlist/${ID}`.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/access-security/ip-allowlist/'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有「一条汇总列表接口」，一切入口都带子路径。
    'channel/access-security',
    // 登录审计是 append-only 的凭据 ⇒ 不放行删除，也不放行按 id 取详情（后端没这个映射）。
    `channel/access-security/login-audit/${ID}`,
    'channel/access-security/login-audit/delete',
    `channel/access-security/login-audit/${ID}/delete`,
    // 告警的处置走状态机，不是删除；「已忽略 / 已处置」是终态，不提供「重开」。
    `channel/access-security/alerts/${ID}/delete`,
    `channel/access-security/alerts/${ID}/reopen`,
    // 凭据访问审计 append-only ⇒ 不放行删除。
    `channel/access-security/credential-access/${ID}/delete`,
    // MFA 登记的状态机是「停用 / 撤销」，不提供物理删除。
    `channel/access-security/mfa/registrations/${ID}/delete`,
    // 挑战没有「按 id 取详情」的映射（列表行已带全字段）。
    `channel/access-security/mfa/challenges/${ID}`,
    // 会话实体在 17 identity ⇒ 不造第二套会话系统。
    'channel/access-security/sessions',
    `channel/access-security/session/${ID}`,
    // 16.5.1.1 黑名单**未交付** ⇒ 不放行黑名单入口（本模块**不写黑名单**）。
    'channel/access-security/blacklist',
    // 16.5.2.2 关联风险**未交付** ⇒ 不放行关联账号入口（本模块**不编一张关联账号表**）。
    'channel/access-security/related-accounts',
    // 下游 2.2.1.3 凭据引用**已交付**，那个写端点归 2.2.1.3，不是本模块的形状。
    `channel/access-security/connections/${ID}/credential`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'channel/access-security/alerts/not-a-uuid',
    'channel/access-security/ip-allowlist/not-a-uuid',
    'channel/access-security/credential-access/not-a-uuid',
    // 只放行本模块真实存在的形状。
    'channel/access-security/overview',
    'channel/access-security/stats',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `access-security` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的汇总接口」可用。
 */
it('裸路径 access-security 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'channel/access-security';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 🔴 本车道往 `services[]` 里**新增了一条 channel/ 元素**（不并既有 channel 分支的 alternation），
 * 所以必须钉住「既有 channel 模块的入口没有被本条吃掉」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 channel 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 16.5.1.2 风险评分（卷 16 的同系统模块，根路径不同）
    'channel/buyer-risk-scores/capabilities',
    // 16.4.1.1 侵权库
    'channel/ip-risk-library/capabilities',
    // 16.2.2.1 合规预检
    'channel/compliance-precheck/capabilities',
    // 14.1.2.1 广告组（**紧邻本条锚点的前一条元素**）
    'channel/ad-groups/capabilities',
    // 4.2.3.2 合规预检
    'channel/listing-compliance-precheck/capabilities',
    // 16.3.1.1 税号与辖区
    'channel/tax-registration/capabilities',
    // 16.1.2.1 违规与处罚
    'channel/platform-violations/capabilities',
    // 16.4.2.1 品牌授权
    'channel/brand-authorizations/capabilities',
    // 2.1.1.1 店铺
    'channel/shops',
    // 16.1.1.1 账号健康
    'channel/account-health/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
