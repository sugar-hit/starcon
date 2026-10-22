import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';
import { GET as CRM_GET } from '../crm/[...path]/route';

/**
 * 16.6.1.1 PII 管理 的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(new URL('../../../components/pii-access/pii-access-model.ts', import.meta.url)),
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
 * <p>模型里的端点既有模板串（{@code `channel/pii-access/capabilities`}）也有
 * 带 id 与查询串的（{@code `.../exports/${requestId}/approve`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 *
 * <p>🔴 本模块多一类插值：{@code ${kind}} 是**受控值域**（CUSTOMER / ORDER），
 * 不是 id —— 把它替换成 UUID 会抽出一条真实调用点里**不存在**的路径，
 * 然后那条路径因为不在白名单里而假失败。所以这里替换成真实枚举值。
 */
function realCallSites(): string[] {
  const base = 'channel/pii-access';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    // 🔴 裸前缀常量（BASE 本身）**不是调用点**：后端没有「一条汇总列表接口」。
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
      // `${kind}` 是受控值域（主体类型），不是 id
      .replace(/\$\{kind\}/g, 'ORDER')
      // `${query}` 是「可选的查询串」，展开后为空（不是一段路径）
      .replace(/\$\{query\}/g, '')
      .replace(/\$\{suffix\}/g, '')
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
      'channel/pii-access/capabilities',
      'channel/pii-access/policy',
      'channel/pii-access/grants',
      `channel/pii-access/grants/${ID}/revoke`,
      'channel/pii-access/subjects',
      `channel/pii-access/subjects/ORDER/${ID}`,
      'channel/pii-access/exports',
      `channel/pii-access/exports/${ID}/approve`,
      `channel/pii-access/exports/${ID}/reject`,
      `channel/pii-access/exports/${ID}/execute`,
      'channel/pii-access/audit',
    ]),
  );
});

it('写形状（PUT 策略 / PUT 授权 / POST 申请 / POST 审批）被放行，且真的转发到 8083', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await PUT(request('channel/pii-access/policy', 'PUT'), {
    params: Promise.resolve({ path: 'channel/pii-access/policy'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/pii-access/policy'),
    expect.objectContaining({ method: 'PUT' }),
  );

  await PUT(request('channel/pii-access/grants', 'PUT'), {
    params: Promise.resolve({ path: 'channel/pii-access/grants'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/pii-access/grants'),
    expect.objectContaining({ method: 'PUT' }),
  );

  await POST(request('channel/pii-access/exports', 'POST'), {
    params: Promise.resolve({ path: 'channel/pii-access/exports'.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/pii-access/exports'),
    expect.objectContaining({ method: 'POST' }),
  );

  await POST(request(`channel/pii-access/exports/${ID}/approve`, 'POST'), {
    params: Promise.resolve({ path: `channel/pii-access/exports/${ID}/approve`.split('/') }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(`:8083/channel/pii-access/exports/${ID}/approve`),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有「一条汇总列表接口」，一切入口都带子路径。
    'channel/pii-access',
    // 🔴 **没有「关闭脱敏」的形状** —— 脱敏是硬默认，不是可选开关。
    'channel/pii-access/mask-off',
    'channel/pii-access/purge',
    // 🔴 **没有「一把全量导出」的形状** —— 批量导出必须逐张审批、逐条列明。
    'channel/pii-access/exports/all',
    'channel/pii-access/exports/bulk',
    'channel/pii-access/export-all',
    // 导出申请是审批凭据 ⇒ 不放行删除。
    `channel/pii-access/exports/${ID}/delete`,
    // 审批必须逐张做。
    `channel/pii-access/exports/${ID}/approve-all`,
    // 访问审计 append-only ⇒ 不放行删除。
    `channel/pii-access/audit/${ID}/delete`,
    `channel/pii-access/audit/${ID}`,
    // 撤销授权只认 revoke 这一条路径。
    `channel/pii-access/grants/${ID}/enable`,
    `channel/pii-access/grants/${ID}`,
    // 主体类型是受控值域 ⇒ 别的形状不放行。
    'channel/pii-access/subjects/FROBNATE/anything',
    // 主体详情必须带 ref。
    'channel/pii-access/subjects/ORDER',
    // 段里带斜杠 = 多了一层路径，后端没有。
    `channel/pii-access/subjects/ORDER/${ID}/delete`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'channel/pii-access/exports/not-a-uuid',
    // 只放行本模块真实存在的那十一个形状。
    'channel/pii-access/overview',
    'channel/pii-access/stats',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `pii-access` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的汇总接口」可用。
 */
it('裸路径 pii-access 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'channel/pii-access';
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
    // 16.5.1.2 风险评分（**同批前一条车道**，首 token 与本模块最像的一个）
    'channel/buyer-risk-scores/capabilities',
    // 16.2.2.1 合规预检（卷 16 的同系统模块，根路径不同）
    'channel/compliance-precheck/capabilities',
    // 4.2.3.2 合规预检（卷 04 的同系统模块，根路径不同）
    'channel/listing-compliance-precheck/capabilities',
    // 16.3.1.1 税号与辖区（卷 16 的同系统模块，根路径不同）
    'channel/tax-registration/capabilities',
    // 16.1.2.1 违规与处罚（**同批另一条车道的锚点**）
    'channel/platform-violations/capabilities',
    // 16.4.2.1 品牌授权
    'channel/brand-authorizations/capabilities',
    // 4.1.1.1 listing 列表
    'channel/listings',
    // 2.1.1.1 店铺
    'channel/shops',
    // 4.2.1.1 刊登编辑器
    'channel/listing-editor/capabilities',
    // 14.5.1 规则
    'channel/ad-rules/capabilities',
    // 16.1.1.1 账号健康
    'channel/account-health/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});

/**
 * 🔴 本车道新增的是一条 **channel/** 元素，但白名单是**跨服务**累积的：
 * 同一个 `services[]` 数组里还住着 crm / catalog / identity 等服务的元素。
 * 所以必须钉住「别的服务的入口也没有被本条吃掉」——
 * `buyer-emails`（15.1.2.1 买家邮件）是 **crm** 服务、前缀 `crm/`，
 * 而且它的首 token 与本模块的 `pii-access` 只差几个字符，最容易互相吃。
 */
it('别的服务的入口没有被本车道新增的 channel 元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 15.6.2.2 弃单挽回（**crm** 服务，前缀 crm/）
    'crm/cart-recovery/capabilities',
    // 15.4.2.1 知识库（crm 服务）
    'crm/knowledge/capabilities',
    // 15.1.2.1 买家邮件（crm 服务）
    'crm/buyer-emails/capabilities',
  ]) {
    const response = await CRM_GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
