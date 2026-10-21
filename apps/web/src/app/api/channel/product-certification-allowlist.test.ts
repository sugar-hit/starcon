import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 16.2.2.2 认证与资质 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `product-certifications` 元素，既有的 `listings`（4.1.1.1）、
 * `listing-mappings`（4.1.2.1）、`listing-editor`（4.2.1.1）、
 * `compliance-precheck`（16.2.2.1）、`return-disposals`（10.7.1.3）、
 * `inventory/stockout-alerts`（7.8.2.2）等分支一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/channel.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/channel/product-certifications';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不**削掉 `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // {code} 是认证类型编码（CE / FCC / UL …），其余路径参数都是 uuid。
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{code\}/g, 'CE')
      .replace(/\{[^}]+\}/g, ID);
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

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/product-certifications/capabilities',
      'channel/product-certifications/overview',
      'channel/product-certifications/types',
      'channel/product-certifications/types/CE',
      'channel/product-certifications/policy',
      'channel/product-certifications/certifications',
      `channel/product-certifications/certifications/${ID}`,
      `channel/product-certifications/certifications/${ID}/revoke`,
      `channel/product-certifications/certifications/${ID}/skus`,
      `channel/product-certifications/certifications/${ID}/skus/${ID}`,
      `channel/product-certifications/certifications/${ID}/categories`,
      `channel/product-certifications/certifications/${ID}/categories/${ID}`,
      `channel/product-certifications/certifications/${ID}/documents`,
      `channel/product-certifications/documents/${ID}`,
      `channel/product-certifications/documents/${ID}/file`,
      'channel/product-certifications/requirements',
      `channel/product-certifications/requirements/${ID}`,
      'channel/product-certifications/coverage',
      `channel/product-certifications/coverage/skus/${ID}`,
      'channel/product-certifications/expiry-reminders',
      'channel/product-certifications/sku-candidates',
      'channel/product-certifications/category-candidates',
    ]),
  );
});

it('本模块的写方法确实存在于契约里 —— 白名单不该把它们挡掉', () => {
  const methods = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    for (const method of Object.keys(SPEC.paths[path] ?? {})) methods.add(method);
  }
  expect([...methods].sort()).toEqual(['delete', 'get', 'post', 'put']);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/product-certifications';
  for (const path of [
    // 裸路径：后端没有这个映射。
    base,
    // 🔴 认证档案是合规凭据，不可删（能做的只有撤销）。
    `${base}/certifications/${ID}/delete`,
    // 🔴 16.2.1.1 规则库与 16.2.1.2 拦截执行都未交付，本模块不代它们开形状。
    `${base}/rules`,
    `${base}/library`,
    `${base}/publish-gate`,
    `${base}/blocking-decision`,
    // 续期不是独立动作（续期 = 再上传一份扫描件 + 改有效期）。
    `${base}/certifications/${ID}/renew`,
    // 字面量子路径不得带 id 段。
    `${base}/capabilities/${ID}`,
    `${base}/overview/${ID}`,
    `${base}/coverage/${ID}`,
    // 伪造的 uuid 形状必须被拒：短前缀 / 多一位都不是 uuid。
    `${base}/certifications/not-a-uuid`,
    `${base}/certifications/11111111-1111-1111-1111-1111111111111`,
    `${base}/documents/not-a-uuid`,
    // 多余路径段。
    `${base}/a/b`,
    // 认证类型编码只允许字母数字下划线短横线，带点的形状不是编码。
    `${base}/types/CE.2024`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('写动作真的转发到 channel 出口（8083），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'channel/product-certifications/certifications';
  const response = await callProxy(path, {
    method: 'POST',
    headers: {
      cookie: 'starcon_session=private-session',
      // 🔴 代理对写请求校验 Origin（防跨站 CSRF）：不带头一律 403，连转发都到不了。
      origin: 'http://localhost:3000',
      'X-Tenant-Id': '22222222-2222-2222-2222-222222222222',
    },
    body: JSON.stringify({ code: 'CE-1' }),
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/product-certifications/certifications'),
    expect.anything(),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  const forwarded = new Headers(init.headers);
  expect(forwarded.get('X-Tenant-Id'), '伪造的租户头不得被转发到上游').toBeNull();
  expect(forwarded.get('Authorization')).toBe('Bearer private-session');
});

it('不误伤其他车道已登记的分支：既有 channel / warehouse 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 4.1.1.1 Listing 列表
    'channel/listings',
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
    // 4.2.1.1 刊登编辑器
    'channel/listing-editor/capabilities',
    'channel/listing-editor/drafts',
    // 🔴 16.2.2.1 合规预检（**同 L3 的兄弟模块**：前缀是 `compliance-precheck`，
    //    与本模块的 `product-certifications` 刻意不撞车）。
    'channel/compliance-precheck/capabilities',
    'channel/compliance-precheck/drafts',
    `channel/compliance-precheck/drafts/${ID}/check`,
    // 10.7.1.3 销毁单（**插入锚点的前一条既有 channel 分支**）
    'channel/return-disposals/capabilities',
    'channel/return-disposals/orders',
    // 7.8.2.2 缺货与饥饿预警（**插入锚点的前一条既有分支**）
    'warehouse/inventory/stockout-alerts/capabilities',
    'warehouse/inventory/stockout-alerts/stockouts',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把认证与资质挂到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 认证与资质的写入所有权在 channel（合规与风控 + 平台操作语义），
  // catalog 那边是商品主数据，trade 那边是供应商资质，挂过去只会让「这条路由归谁」永远答不出来。
  for (const path of [
    'catalog/product-certifications/capabilities',
    'trade/product-certifications/capabilities',
    'warehouse/product-certifications/capabilities',
    'crm/product-certifications/capabilities',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
